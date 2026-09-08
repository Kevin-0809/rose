package com.spdb.message;

import com.spdb.message.utils.AuthUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AnaMessageSendService {

    private static final Logger log = LoggerFactory.getLogger(AnaMessageSendService.class);
    private static final int FLUSH_SIZE = 500;
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int FETCH_SIZE = 1_000;
    private static final long RESET_RANGE_MILLIS = 3600_000L;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ResponseMessageParser parser = new ResponseMessageParser();
    private final HttpRequestMessageSender sender = new HttpRequestMessageSender();
    private final ThreadLocal<Integer> sentCount = ThreadLocal.withInitial(() -> 0);
    private final Map<String, String> configCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> addressCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> authCache = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> respBuffer = new ArrayList<>();
    private final List<Map<String, Object>> statusBuffer = new ArrayList<>();
    private volatile boolean running;

    public AnaMessageSendService(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.jdbc.getJdbcTemplate().setFetchSize(FETCH_SIZE);
    }

    public synchronized void start(String target, int concurrency, int rangeMinutes, int retries, int timeout) {
        if (running) return;
        running = true;
        configCache.clear();
        addressCache.clear();
        authCache.clear();
        int recovered = jdbc.update("update ana_msg_flow_log_request set send_status='PENDING' where send_status='SENDING'",
                new MapSqlParameterSource());
        if (recovered > 0) {
            log.info("报文发送启动恢复: {} 笔遗留 SENDING 置回 PENDING", recovered);
        }
        long rangeMillis = Math.max(1, rangeMinutes) * 60_000L;
        log.info("报文发送启动: target={}, concurrency={}, rangeMinutes={}, retries={}, timeout={}s",
                target, concurrency, rangeMillis / 60_000L, retries, timeout);
        long[] range = minMax(retries);
        if (range == null) {
            log.info("报文发送结束: target={}, 无待发送记录", target);
            running = false;
            return;
        }
        int window = Math.max(1, concurrency);
        LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        ExecutorService pool = Executors.newFixedThreadPool(window);
        Map<String, Object> poison = new HashMap<>();
        AtomicInteger consumed = new AtomicInteger();
        for (int i = 0; i < window; i++) {
            pool.submit(() -> {
                try {
                    while (true) {
                        Map<String, Object> row = queue.take();
                        if (row == poison) break;
                        sendOne(row, target, retries, timeout);
                        consumed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        long readTotal = 0;
        try {
            long cursor = range[0];
            long max = range[1];
            while (running && cursor <= max) {
                long to = cursor + rangeMillis;
                int count = loadRange(cursor, to, retries, queue);
                if (count > 0) {
                    log.info("报文发送区间: target={}, [{}, {}) 共 {} 笔", target, cursor, to, count);
                    readTotal += count;
                }
                cursor = to;
            }
            for (int i = 0; i < window; i++) {
                queue.put(poison);
            }
            if (!running) {
                log.info("报文发送停止: 不再读取后续区间, 队列剩余记录继续消费完");
            }
            pool.shutdown();
            if (!pool.awaitTermination(7, TimeUnit.DAYS)) {
                log.warn("报文发送等待消费者结束超时: target={}", target);
            }
            log.info("报文发送结束: target={}, 共读取 {} 笔, 已消费 {} 笔", target, readTotal, consumed.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("报文发送被中断: target={}, 已读取 {} 笔, 已消费 {} 笔", target, readTotal, consumed.get());
        } catch (Exception e) {
            log.error("报文发送异常终止: target={}", target, e);
        } finally {
            pool.shutdownNow();
            flush();
            running = false;
        }
    }

    public void stop() {
        if (running) {
            running = false;
            log.info("报文发送停止: 不再读取后续区间, 队列剩余记录继续消费完");
        }
    }

    public synchronized int reset(String scope) {
        if (running) throw new IllegalStateException("发送任务运行中, 请先停止后再重置");
        String statusFilter = "failed".equals(scope) ? "send_status='FAILED'" : "send_status in ('SUCCESS','FAILED')";
        long[] range = resetRange(statusFilter);
        if (range == null) {
            log.info("报文重置: scope={}, 无符合条件的记录", scope);
            return 0;
        }
        log.info("报文重置开始: scope={}, 区间 [{}, {}]", scope, range[0], range[1]);
        long cursor = range[0];
        int total = 0;
        int batches = 0;
        while (cursor <= range[1]) {
            long to = cursor + RESET_RANGE_MILLIS;
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("from", cursor)
                    .addValue("to", to);
            Integer updated = tx.execute(s -> {
                jdbc.update("delete from ana_msg_flow_log_response r using ana_msg_flow_log_request q " +
                                "where r.source_ip=q.source_ip and r.trans_id=q.trans_id " +
                                "and q." + statusFilter + " and q.txn_time >= :from and q.txn_time < :to", params);
                return jdbc.update("update ana_msg_flow_log_request set send_status='PENDING', send_attempts=0, " +
                                "send_time=null, send_http_status=null, send_error=null " +
                                "where " + statusFilter + " and txn_time >= :from and txn_time < :to", params);
            });
            if (updated != null) total += updated;
            if (++batches % 100 == 0) {
                log.info("报文重置进度: 已处理到 {}, 累计 {} 笔", to, total);
            }
            cursor = to;
        }
        log.info("报文重置完成: scope={}, 共重置 {} 笔", scope, total);
        return total;
    }

    private long[] resetRange(String statusFilter) {
        List<Map<String, Object>> rows = jdbc.query(
                "select min(txn_time) as min_t, max(txn_time) as max_t from ana_msg_flow_log_request " +
                        "where " + statusFilter + " and txn_time is not null",
                new MapSqlParameterSource(),
                (rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("min_t", rs.getObject(1));
                    m.put("max_t", rs.getObject(2));
                    return m;
                });
        if (rows.isEmpty() || rows.get(0).get("min_t") == null || rows.get(0).get("max_t") == null) return null;
        return new long[]{
                ((Number) rows.get(0).get("min_t")).longValue(),
                ((Number) rows.get(0).get("max_t")).longValue()
        };
    }

    public boolean isRunning() {
        return running;
    }

    public Map<String, Object> stats() {
        return jdbc.queryForMap("select count(*) filter(where send_status='PENDING') pending, " +
                "count(*) filter(where send_status='SENDING') sending, " +
                "count(*) filter(where send_status='SUCCESS') success, " +
                "count(*) filter(where send_status='FAILED') failed from ana_msg_flow_log_request",
                new MapSqlParameterSource());
    }

    private long[] minMax(int retries) {
        List<Map<String, Object>> rows = jdbc.query(
                "select min(txn_time) as min_t, max(txn_time) as max_t from ana_msg_flow_log_request " +
                        "where send_status in ('PENDING','FAILED') and send_attempts <= :retries and txn_time is not null",
                new MapSqlParameterSource("retries", retries),
                (rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("min_t", rs.getObject(1));
                    m.put("max_t", rs.getObject(2));
                    return m;
                });
        if (rows.isEmpty() || rows.get(0).get("min_t") == null || rows.get(0).get("max_t") == null) return null;
        return new long[]{
                ((Number) rows.get(0).get("min_t")).longValue(),
                ((Number) rows.get(0).get("max_t")).longValue()
        };
    }

    private int loadRange(long from, long to, int retries, LinkedBlockingQueue<Map<String, Object>> queue) {
        Integer count = tx.execute(status -> jdbc.query("select source_ip, trans_id, txn_code, message_type, request_message, send_attempts " +
                        "from ana_msg_flow_log_request " +
                        "where txn_time >= :from and txn_time < :to and txn_time is not null " +
                        "and send_status in ('PENDING','FAILED') and send_attempts <= :retries " +
                        "order by txn_time",
                new MapSqlParameterSource()
                        .addValue("from", from)
                        .addValue("to", to)
                        .addValue("retries", retries),
                (ResultSetExtractor<Integer>) rs -> {
                    int c = 0;
                    while (rs.next()) {
                        putRow(queue, toRow(rs));
                        c++;
                    }
                    return c;
                }));
        return count == null ? 0 : count;
    }

    private void putRow(LinkedBlockingQueue<Map<String, Object>> queue, Map<String, Object> row) {
        try {
            queue.put(row);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("报文发送读取被中断", e);
        }
    }

    private Map<String, Object> toRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("source_ip", rs.getString(1));
        m.put("trans_id", rs.getString(2));
        m.put("txn_code", rs.getString(3));
        m.put("message_type", rs.getString(4));
        m.put("request_message", rs.getBytes(5));
        m.put("send_attempts", rs.getInt(6));
        return m;
    }

    private void sendOne(Map<String, Object> r, String target, int retries, int timeout) {
        String ip = (String) r.get("source_ip");
        String id = (String) r.get("trans_id");
        String type = (String) r.get("message_type");
        long begin = System.currentTimeMillis();
        try {
            String mic = val("micServId", "10530013");
            String protocol = target.toLowerCase() + "_" + (type == null ? "" : type.toLowerCase());
            String address = pickAddress(protocol);

            String authType = switch (type == null ? "" : type.trim().toLowerCase()) {
                case "bzjson" -> "json";
                case "soap" -> "xml";
                case "sop" -> "sop";
                case "sop2cbsp" -> "spec";
                default -> type == null ? "" : type.toLowerCase();
            };
            String authProtocol = target.toLowerCase() + "_" + authType;
            Map<String, Object> au = auth(authProtocol);
            String auth = AuthUtil.packToken((String) au.get("sid"), (String) au.get("gk"),
                    (String) au.get("pk"), (String) au.get("wk"));

            var response = sender.send(address, type, (byte[]) r.get("request_message"), mic, auth, timeout);
            String code = parser.parseReturnCode(type, response.body());

            Map<String, Object> resp = new HashMap<>();
            resp.put("ip", ip);
            resp.put("id", id);
            resp.put("t", System.currentTimeMillis());
            resp.put("txn", r.get("txn_code"));
            resp.put("type", type);
            resp.put("body", response.body());
            resp.put("code", code);
            resp.put("status", response.statusCode());
            resp.put("addr", address);
            Map<String, Object> stat = new HashMap<>();
            stat.put("ip", ip);
            stat.put("id", id);
            stat.put("s", response.statusCode() / 100 == 2 ? "SUCCESS" : "FAILED");
            stat.put("h", response.statusCode());
            stat.put("e", null);
            buffer(resp, stat);
            log.debug("报文发送成功: target={}, ip={}, transId={}, type={}, address={}, httpStatus={}, returnCode={}, 耗时={}ms",
                    target, ip, id, type, address, response.statusCode(), code, System.currentTimeMillis() - begin);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            Map<String, Object> stat = new HashMap<>();
            stat.put("ip", ip);
            stat.put("id", id);
            stat.put("s", "FAILED");
            stat.put("h", null);
            stat.put("e", msg.substring(0, Math.min(1000, msg.length())));
            buffer(null, stat);
            log.warn("报文发送失败: target={}, ip={}, transId={}, type={}, 耗时={}ms, error={}",
                    target, ip, id, type, System.currentTimeMillis() - begin, msg);
        } finally {
            int n = sentCount.get() + 1;
            sentCount.set(n);
            if (n % 100 == 0) {
                log.info("报文发送进度: target={}, 当前线程累计已发送 {} 笔", target, n);
            }
        }
    }

    private String pickAddress(String protocol) {
        List<String> all = addressCache.computeIfAbsent(protocol, p -> {
            List<String> addresses = jdbc.query("select service_address from tss_service_control where protocol_id=:p",
                    new MapSqlParameterSource("p", p), (rs, n) -> rs.getString(1));
            if (addresses.isEmpty()) throw new IllegalStateException("未找到服务地址:" + p);
            List<String> list = new ArrayList<>();
            for (String a : addresses) {
                for (String x : a.split(",")) {
                    if (!x.isBlank()) list.add(x.trim());
                }
            }
            if (list.isEmpty()) throw new IllegalStateException("服务地址为空");
            return list;
        });
        return all.get(ThreadLocalRandom.current().nextInt(all.size()));
    }

    private Map<String, Object> auth(String authProtocol) {
        return authCache.computeIfAbsent(authProtocol, p -> {
            List<Map<String, Object>> auths = jdbc.query(
                    "select sid, gk, wk, pk from tss_service_auth where protocol_id=:p",
                    new MapSqlParameterSource("p", p),
                    (rs, n) -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("sid", rs.getString(1));
                        m.put("gk", rs.getString(2));
                        m.put("wk", rs.getString(3));
                        m.put("pk", rs.getString(4));
                        return m;
                    });
            if (auths.isEmpty()) throw new IllegalStateException("未找到服务密钥:" + p);
            Map<String, Object> au = auths.get(0);
            if (au.get("gk") == null || au.get("pk") == null || au.get("wk") == null) {
                throw new IllegalStateException("服务密钥不完整:" + p);
            }
            return au;
        });
    }

    private String val(String k, String d) {
        return configCache.computeIfAbsent(k, key -> {
            List<String> v = jdbc.query("select config_value from system_config where config_key=:k",
                    new MapSqlParameterSource("k", key), (rs, n) -> rs.getString(1));
            return v.isEmpty() ? d : v.get(0);
        });
    }

    private synchronized void buffer(Map<String, Object> resp, Map<String, Object> stat) {
        if (resp != null) respBuffer.add(resp);
        statusBuffer.add(stat);
        if (statusBuffer.size() >= FLUSH_SIZE) flush();
    }

    private synchronized void flush() {
        if (statusBuffer.isEmpty()) return;
        List<Map<String, Object>> respRows = new ArrayList<>(respBuffer);
        List<Map<String, Object>> statRows = new ArrayList<>(statusBuffer);
        respBuffer.clear();
        statusBuffer.clear();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                tx.executeWithoutResult(status -> {
                    if (!respRows.isEmpty()) {
                        jdbc.batchUpdate("insert into ana_msg_flow_log_response(source_ip, trans_id, response_time, " +
                                        "txn_code, message_type, response_message, return_code, http_status, service_address) " +
                                        "values(:ip, :id, :t, :txn, :type, :body, :code, :status, :addr)",
                                respRows.toArray(new Map[0]));
                    }
                    jdbc.batchUpdate("update ana_msg_flow_log_request set send_status=:s, send_time=current_timestamp, " +
                                    "send_http_status=:h, send_error=:e, send_attempts=send_attempts+1 " +
                                    "where source_ip=:ip and trans_id=:id",
                            statRows.toArray(new Map[0]));
                });
                return;
            } catch (Exception e) {
                if (attempt == 2) {
                    log.error("报文发送批量落库失败: 响应 {} 条, 状态 {} 条, 对应记录保持原状态可在下轮重发",
                            respRows.size(), statRows.size(), e);
                } else {
                    log.warn("报文发送批量落库失败, 重试一次: {}", String.valueOf(e.getMessage()));
                }
            }
        }
    }
}
