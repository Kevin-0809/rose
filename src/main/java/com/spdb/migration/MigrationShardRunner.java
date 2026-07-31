package com.spdb.migration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MigrationShardRunner {
    private static final Logger log = LoggerFactory.getLogger(MigrationShardRunner.class);
    private static final int MAX_TARGET_CHUNK_SIZE = 500;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final List<String> TRAN_CODE_MESSAGE_TYPES = List.of("bzjson", "sop", "soap");
    private static final String SOURCE_QUERY = """
            with filtered_response as (
                select source_ip, trans_id, txn_code, response_time, message_type,
                       response_message, return_code, return_msg
                from msg_flow_log_response
                where response_time >= ?
                  and response_time < ?
            )
            select req.source_ip,
                   req.trans_id,
                   req.txn_code as request_txn_code,
                   req.txn_time,
                   req.message_type as request_message_type,
                   req.request_message,
                   req.global_seq_no,
                   req.tran_teller_no,
                   resp.txn_code as response_txn_code,
                   resp.response_time,
                   resp.message_type as response_message_type,
                   resp.response_message,
                   resp.return_code,
                   resp.return_msg
            from filtered_response resp
            join msg_flow_log_request req
              on req.trans_id = resp.trans_id
             and req.source_ip = resp.source_ip
            order by resp.response_time, resp.source_ip, resp.trans_id
            """;

    private final NamedParameterJdbcTemplate sourceJdbc;
    private final NamedParameterJdbcTemplate targetJdbc;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public MigrationShardRunner(@Qualifier("bxdsJdbcTemplate") NamedParameterJdbcTemplate sourceJdbc,
                                NamedParameterJdbcTemplate targetJdbc,
                                PlatformTransactionManager transactionManager) {
        this(sourceJdbc, targetJdbc, transactionManager, Clock.system(SHANGHAI));
    }

    MigrationShardRunner(@Qualifier("bxdsJdbcTemplate") NamedParameterJdbcTemplate sourceJdbc,
                         NamedParameterJdbcTemplate targetJdbc,
                         PlatformTransactionManager transactionManager,
                         Clock clock) {
        this.sourceJdbc = sourceJdbc;
        this.targetJdbc = targetJdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public MigrationShardResult run(long shardId, long timeFrom, long timeTo, int fetchSize) {
        int fetchAndBatchSize = Math.max(fetchSize, 1);
        long droppedRows = countDroppedRows(timeFrom, timeTo);
        StreamingAccumulator accumulator = new StreamingAccumulator(fetchAndBatchSize);

        streamPairedRows(timeFrom, timeTo, fetchAndBatchSize, row -> {
            accumulator.rows.add(row);
            if (accumulator.rows.size() >= fetchAndBatchSize) {
                accumulator.add(flushBatch(accumulator.rows));
            }
        });
        if (!accumulator.rows.isEmpty()) {
            accumulator.add(flushBatch(accumulator.rows));
        }

        return new MigrationShardResult(accumulator.migratedRows, accumulator.skippedRows, droppedRows);
    }

    public MigrationShardResult runSql(long shardId, String responseSql, int fetchSize) {
        int fetchAndBatchSize = Math.max(fetchSize, 1);
        SqlResponseAccumulator accumulator = new SqlResponseAccumulator(fetchAndBatchSize);

        streamSqlResponseRows(responseSql, fetchAndBatchSize, row -> {
            accumulator.responses.add(row);
            if (accumulator.responses.size() >= fetchAndBatchSize) {
                accumulator.add(flushSqlResponseBatch(accumulator));
            }
        });
        if (!accumulator.responses.isEmpty()) {
            accumulator.add(flushSqlResponseBatch(accumulator));
        }

        return new MigrationShardResult(accumulator.migratedRows, accumulator.skippedRows, accumulator.droppedRows);
    }

    public MigrationShardResult runTranCode(long shardId, String tranCode, int maxRowsPerMessageType) {
        return runTranCode(shardId, tranCode, maxRowsPerMessageType, MigrationTranCodeCommandForm.DEFAULT_LOOKBACK_DAYS);
    }

    public MigrationShardResult runTranCode(long shardId,
                                             String tranCode,
                                             int maxRowsPerMessageType,
                                             int lookbackDays) {
        if (maxRowsPerMessageType <= 0) {
            log.info("Transaction-code migration skipped, shardId={}, tranCode={}, reason=sample size is not positive",
                    shardId, tranCode);
            return new MigrationShardResult(0L, 0L, 0L, 0);
        }
        if (lookbackDays <= 0) {
            log.info("Transaction-code migration skipped, shardId={}, tranCode={}, reason=lookback days is not positive",
                    shardId, tranCode);
            return new MigrationShardResult(0L, 0L, 0L, 0);
        }
        List<ServiceCodeMapping> serviceMappings = loadServiceCodeMappings(tranCode);
        if (serviceMappings.isEmpty()) {
            log.info("Transaction-code migration skipped, shardId={}, tranCode={}, reason=no service code mapping",
                    shardId, tranCode);
            return new MigrationShardResult(0L, 0L, 0L, 0);
        }

        BatchResult total = new BatchResult();
        int actualLookbackDays = 0;
        LocalDate currentDate = LocalDate.now(clock.withZone(SHANGHAI));
        long currentTime = clock.instant().toEpochMilli();
        log.info("Transaction-code migration started, shardId={}, tranCode={}, serviceCodeCount={}, sampleSizePerMessageType={}, lookbackDays={}, currentDate={}",
                shardId, tranCode, serviceMappings.size(), maxRowsPerMessageType, lookbackDays, currentDate);
        for (String messageType : TRAN_CODE_MESSAGE_TYPES) {
            Map<String, String> targetTxnCodeBySourceTxnCode = txnCodeMappings(serviceMappings, messageType);
            List<String> txnCodes = new ArrayList<>(targetTxnCodeBySourceTxnCode.keySet());
            int effectiveLookbackDays = effectiveLookbackDays(txnCodes, lookbackDays, currentDate);
            actualLookbackDays = Math.max(actualLookbackDays, effectiveLookbackDays);
            long migratedRows = 0L;
            for (int dayOffset = 0; dayOffset < effectiveLookbackDays && migratedRows < maxRowsPerMessageType; dayOffset++) {
                long dayFrom = currentDate.minusDays(dayOffset).atStartOfDay(SHANGHAI).toInstant().toEpochMilli();
                long dayTo = dayOffset == 0
                        ? currentTime
                        : currentDate.minusDays(dayOffset - 1L).atStartOfDay(SHANGHAI).toInstant().toEpochMilli();
                log.info("Transaction-code migration scanning window, shardId={}, tranCode={}, messageType={}, dayOffset={}, timeFrom={}, timeTo={}, migratedRows={}",
                        shardId, tranCode, messageType, dayOffset, dayFrom, dayTo, migratedRows);
                int offset = 0;
                while (migratedRows < maxRowsPerMessageType) {
                    int remainingRows = (int) (maxRowsPerMessageType - migratedRows);
                    List<MigrationSourceRow> rows = loadTranCodeRows(txnCodes, dayFrom, dayTo, remainingRows, offset);
                    if (rows.isEmpty()) {
                        break;
                    }
                    List<MigrationSourceRow> targetRows = rows.stream()
                            .map(row -> withTxnCode(row, targetTxnCodeBySourceTxnCode.getOrDefault(
                                    row.responseTxnCode(),
                                    row.responseTxnCode()
                            )))
                            .toList();
                    BatchResult result = transactionTemplate.execute(status -> writeBatch(targetRows));
                    BatchResult batchResult = result == null ? new BatchResult() : result;
                    total.add(batchResult);
                    migratedRows += batchResult.migratedRows;
                    offset += rows.size();
                }
            }
            log.info("Transaction-code migration message type completed, shardId={}, tranCode={}, messageType={}, migratedRows={}, targetRows={}",
                    shardId, tranCode, messageType, migratedRows, maxRowsPerMessageType);
        }
        log.info("Transaction-code migration completed, shardId={}, tranCode={}, migratedRows={}, skippedRows={}",
                shardId, tranCode, total.migratedRows, total.skippedRows);
        return new MigrationShardResult(total.migratedRows, total.skippedRows, 0L, actualLookbackDays);
    }

    private List<ServiceCodeMapping> loadServiceCodeMappings(String tranCode) {
        List<ServiceCodeMapping> mappedServiceCodes = targetJdbc.query("""
                select "528_service_code" as lookup_service_code,
                       ccbs_service_code as target_service_code
                from ana_tran_code_service_mapping
                where tran_code = :tranCode
                order by mapping_id
                """, new MapSqlParameterSource("tranCode", tranCode), (rs, rowNum) -> new ServiceCodeMapping(
                rs.getString("lookup_service_code"),
                rs.getString("target_service_code")
        ));
        if (!mappedServiceCodes.isEmpty()) {
            return distinctServiceMappings(mappedServiceCodes);
        }
        List<ServiceCodeMapping> onlineServiceCodes = targetJdbc.query("""
                select esf_service_code
                from tp_online_service_in
                where tran_code = :tranCode
                """, new MapSqlParameterSource("tranCode", tranCode), (rs, rowNum) -> {
            String serviceCode = rs.getString("esf_service_code");
            return new ServiceCodeMapping(serviceCode, serviceCode);
        });
        return distinctServiceMappings(onlineServiceCodes);
    }

    private List<ServiceCodeMapping> distinctServiceMappings(List<ServiceCodeMapping> serviceMappings) {
        Map<String, ServiceCodeMapping> mappings = new LinkedHashMap<>();
        for (ServiceCodeMapping mapping : serviceMappings) {
            String lookupServiceCode = normalizeServiceCode(mapping.lookupServiceCode());
            String targetServiceCode = normalizeServiceCode(mapping.targetServiceCode());
            if (lookupServiceCode.isEmpty() || targetServiceCode.isEmpty()) {
                continue;
            }
            mappings.putIfAbsent(lookupServiceCode + "\n" + targetServiceCode,
                    new ServiceCodeMapping(lookupServiceCode, targetServiceCode));
        }
        return new ArrayList<>(mappings.values());
    }

    private Map<String, String> txnCodeMappings(List<ServiceCodeMapping> serviceMappings, String messageType) {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (ServiceCodeMapping mapping : serviceMappings) {
            mappings.putIfAbsent(
                    mapping.lookupServiceCode() + "&" + messageType,
                    mapping.targetServiceCode() + "&" + messageType
            );
        }
        return mappings;
    }

    private String normalizeServiceCode(String serviceCode) {
        return serviceCode == null ? "" : serviceCode.trim().replace(".", "");
    }

    private MigrationSourceRow withTxnCode(MigrationSourceRow row, String txnCode) {
        return new MigrationSourceRow(
                row.sourceIp(),
                row.transId(),
                txnCode,
                row.txnTime(),
                row.requestMessageType(),
                row.requestMessage(),
                row.globalSeqNo(),
                row.tranTellerNo(),
                txnCode,
                row.responseTime(),
                row.responseMessageType(),
                row.responseMessage(),
                row.returnCode(),
                row.returnMsg()
        );
    }

    private int effectiveLookbackDays(List<String> txnCodes, int lookbackDays, LocalDate currentDate) {
        if (lookbackDays != MigrationTranCodeCommandForm.ALL_LOOKBACK_DAYS) {
            return lookbackDays;
        }
        Long earliestResponseTime = earliestResponseTime(txnCodes);
        if (earliestResponseTime == null) {
            return 0;
        }
        LocalDate earliestDate = Instant.ofEpochMilli(earliestResponseTime).atZone(SHANGHAI).toLocalDate();
        long days = ChronoUnit.DAYS.between(earliestDate, currentDate) + 1;
        if (days <= 0) {
            return 1;
        }
        return days > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) days;
    }

    private Long earliestResponseTime(List<String> txnCodes) {
        if (txnCodes.isEmpty()) {
            return null;
        }
        return sourceJdbc.queryForObject("""
                select min(response_time)
                from msg_flow_log_response
                where txn_code in (:txnCodes)
                """, new MapSqlParameterSource("txnCodes", txnCodes), Long.class);
    }

    private List<MigrationSourceRow> loadTranCodeRows(List<String> txnCodes,
                                                       long timeFrom,
                                                       long timeTo,
                                                       int limit,
                                                       int offset) {
        if (txnCodes.isEmpty() || limit <= 0) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("txnCodes", txnCodes)
                .addValue("timeFrom", timeFrom)
                .addValue("timeTo", timeTo)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return sourceJdbc.query("""
                with filtered_response as (
                    select source_ip, trans_id, txn_code, response_time, message_type,
                           response_message, return_code, return_msg
                    from msg_flow_log_response
                    where txn_code in (:txnCodes)
                      and response_time >= :timeFrom
                      and response_time < :timeTo
                )
                select req.source_ip,
                       req.trans_id,
                       req.txn_code as request_txn_code,
                       req.txn_time,
                       req.message_type as request_message_type,
                       req.request_message,
                       req.global_seq_no,
                       req.tran_teller_no,
                       resp.txn_code as response_txn_code,
                       resp.response_time,
                       resp.message_type as response_message_type,
                       resp.response_message,
                       resp.return_code,
                       resp.return_msg
                from filtered_response resp
                join msg_flow_log_request req
                  on req.trans_id = resp.trans_id
                 and req.source_ip = resp.source_ip
                 and req.txn_time >= :timeFrom
                 and req.txn_time < :timeTo
                order by resp.source_ip, resp.trans_id
                limit :limit
                offset :offset
                """, params, (rs, rowNum) -> mapSourceRow(rs));
    }

    private void streamPairedRows(long timeFrom,
                                  long timeTo,
                                  int fetchSize,
                                  MigrationSourceRowConsumer consumer) {
        sourceJdbc.getJdbcTemplate().execute((Connection connection) -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    SOURCE_QUERY,
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY
            )) {
                statement.setFetchSize(fetchSize);
                statement.setLong(1, timeFrom);
                statement.setLong(2, timeTo);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        consumer.accept(mapSourceRow(rs));
                    }
                }
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
            return null;
        });
    }

    private void streamSqlResponseRows(String responseSql,
                                       int fetchSize,
                                       SqlResponseRowConsumer consumer) {
        sourceJdbc.getJdbcTemplate().execute((Connection connection) -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    sqlResponseQuery(responseSql),
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY
            )) {
                statement.setFetchSize(fetchSize);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        consumer.accept(mapSqlResponseRow(rs));
                    }
                }
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
            return null;
        });
    }

    private String sqlResponseQuery(String responseSql) {
        return """
                select resp.source_ip,
                       resp.trans_id,
                       resp.txn_code,
                       resp.response_time,
                       resp.message_type,
                       resp.response_message,
                       resp.return_code,
                       resp.return_msg
                from (
                %s
                ) resp
                order by resp.response_time, resp.source_ip, resp.trans_id
                """.formatted(responseSql);
    }

    private long countDroppedRows(long timeFrom, long timeTo) {
        Long count = sourceJdbc.queryForObject("""
                select count(*)
                from msg_flow_log_response resp
                where resp.response_time >= :timeFrom
                  and resp.response_time < :timeTo
                  and not exists (
                      select 1
                      from msg_flow_log_request req
                      where req.trans_id = resp.trans_id
                        and req.source_ip = resp.source_ip
                  )
                """, shardParams(timeFrom, timeTo), Long.class);
        return count == null ? 0L : count;
    }

    private MapSqlParameterSource shardParams(long timeFrom, long timeTo) {
        return new MapSqlParameterSource()
                .addValue("timeFrom", timeFrom)
                .addValue("timeTo", timeTo);
    }

    private MigrationSourceRow mapSourceRow(ResultSet rs) throws SQLException {
        return new MigrationSourceRow(
                rs.getString("source_ip"),
                rs.getString("trans_id"),
                rs.getString("request_txn_code"),
                nullableLong(rs, "txn_time"),
                rs.getString("request_message_type"),
                rs.getBytes("request_message"),
                rs.getString("global_seq_no"),
                rs.getString("tran_teller_no"),
                rs.getString("response_txn_code"),
                nullableLong(rs, "response_time"),
                rs.getString("response_message_type"),
                rs.getBytes("response_message"),
                rs.getString("return_code"),
                rs.getString("return_msg")
        );
    }

    private SqlResponseRow mapSqlResponseRow(ResultSet rs) throws SQLException {
        return new SqlResponseRow(
                rs.getString("source_ip"),
                rs.getString("trans_id"),
                rs.getString("txn_code"),
                nullableLong(rs, "response_time"),
                rs.getString("message_type"),
                rs.getBytes("response_message"),
                rs.getString("return_code"),
                rs.getString("return_msg")
        );
    }

    private Long nullableLong(ResultSet rs, String columnLabel) throws SQLException {
        long value = rs.getLong(columnLabel);
        return rs.wasNull() ? null : value;
    }

    private BatchResult flushBatch(List<MigrationSourceRow> rows) {
        BatchResult batchResult = transactionTemplate.execute(status -> writeBatch(rows));
        rows.clear();
        return batchResult == null ? new BatchResult() : batchResult;
    }

    private BatchResult flushSqlResponseBatch(SqlResponseAccumulator accumulator) {
        List<MigrationSourceRow> rows = pairSqlResponsesWithRequests(accumulator.responses);
        accumulator.droppedRows += accumulator.responses.size() - rows.size();
        accumulator.responses.clear();
        return rows.isEmpty() ? new BatchResult() : flushBatch(rows);
    }

    private List<MigrationSourceRow> pairSqlResponsesWithRequests(List<SqlResponseRow> responses) {
        if (responses.isEmpty()) {
            return List.of();
        }
        Map<MigrationKey, SqlResponseRow> responseByKey = new LinkedHashMap<>();
        for (SqlResponseRow response : responses) {
            responseByKey.putIfAbsent(MigrationKey.from(response), response);
        }
        Map<MigrationKey, RequestLookupRow> requests = loadSourceRequests(new ArrayList<>(responseByKey.keySet()));
        List<MigrationSourceRow> rows = new ArrayList<>();
        for (Map.Entry<MigrationKey, SqlResponseRow> entry : responseByKey.entrySet()) {
            RequestLookupRow request = requests.get(entry.getKey());
            if (request == null) {
                continue;
            }
            rows.add(toMigrationSourceRow(request, entry.getValue()));
        }
        return rows;
    }

    private MigrationSourceRow toMigrationSourceRow(RequestLookupRow request, SqlResponseRow response) {
        return new MigrationSourceRow(
                response.sourceIp(),
                response.transId(),
                request.txnCode(),
                request.txnTime(),
                request.messageType(),
                request.requestMessage(),
                request.globalSeqNo(),
                request.tranTellerNo(),
                response.txnCode(),
                response.responseTime(),
                response.messageType(),
                response.responseMessage(),
                response.returnCode(),
                response.returnMsg()
        );
    }

    private Map<MigrationKey, RequestLookupRow> loadSourceRequests(List<MigrationKey> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        String predicate = keyPredicateForKeys(keys, params);
        Map<MigrationKey, RequestLookupRow> requests = new LinkedHashMap<>();
        sourceJdbc.query("""
                select source_ip, trans_id, txn_code, txn_time, message_type,
                       request_message, global_seq_no, tran_teller_no
                from msg_flow_log_request
                where %s
                """.formatted(predicate), params, rs -> {
            RequestLookupRow row = new RequestLookupRow(
                    rs.getString("source_ip"),
                    rs.getString("trans_id"),
                    rs.getString("txn_code"),
                    nullableLong(rs, "txn_time"),
                    rs.getString("message_type"),
                    rs.getBytes("request_message"),
                    rs.getString("global_seq_no"),
                    rs.getString("tran_teller_no")
            );
            requests.putIfAbsent(MigrationKey.from(row), row);
        });
        return requests;
    }

    private BatchResult writeBatch(List<MigrationSourceRow> rows) {
        BatchResult result = new BatchResult();
        for (int start = 0; start < rows.size(); start += MAX_TARGET_CHUNK_SIZE) {
            int end = Math.min(start + MAX_TARGET_CHUNK_SIZE, rows.size());
            result.add(writeTargetChunk(rows.subList(start, end)));
        }
        return result;
    }

    private BatchResult writeTargetChunk(List<MigrationSourceRow> rows) {
        BatchResult result = new BatchResult();
        Set<MigrationKey> existingKeys = loadExistingTargetKeys(rows);
        List<MigrationSourceRow> inserts = new ArrayList<>();
        for (MigrationSourceRow row : rows) {
            MigrationKey key = MigrationKey.from(row);
            if (existingKeys.contains(key)) {
                result.skippedRows++;
                continue;
            }
            inserts.add(row);
            existingKeys.add(key);
        }
        batchInsertRequests(inserts);
        batchInsertResponses(inserts);
        result.migratedRows += inserts.size();
        return result;
    }

    private Set<MigrationKey> loadExistingTargetKeys(List<MigrationSourceRow> rows) {
        if (rows.isEmpty()) {
            return Set.of();
        }
        Set<MigrationKey> keys = new HashSet<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        String predicate = keyPredicate(rows, params);
        collectExistingKeys(keys, "msg_flow_log_request", predicate, params);
        collectExistingKeys(keys, "msg_flow_log_response", predicate, params);
        return keys;
    }

    private String keyPredicate(List<MigrationSourceRow> rows, MapSqlParameterSource params) {
        List<MigrationKey> keys = rows.stream().map(MigrationKey::from).toList();
        return keyPredicateForKeys(keys, params);
    }

    private String keyPredicateForKeys(List<MigrationKey> keys, MapSqlParameterSource params) {
        StringBuilder predicate = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                predicate.append(" or ");
            }
            predicate.append("(source_ip = :sourceIp").append(i)
                    .append(" and trans_id = :transId").append(i)
                    .append(")");
            MigrationKey key = keys.get(i);
            params.addValue("sourceIp" + i, key.sourceIp());
            params.addValue("transId" + i, key.transId());
        }
        return predicate.toString();
    }

    private void collectExistingKeys(Set<MigrationKey> keys,
                                     String tableName,
                                     String predicate,
                                     MapSqlParameterSource params) {
        targetJdbc.query("""
                select source_ip, trans_id
                from %s
                where %s
                """.formatted(tableName, predicate), params, rs -> {
            keys.add(new MigrationKey(rs.getString("source_ip"), rs.getString("trans_id")));
        });
    }

    private void batchInsertRequests(List<MigrationSourceRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        targetJdbc.batchUpdate("""
                insert into msg_flow_log_request (
                    source_ip, trans_id, txn_code, txn_time, message_type,
                    request_message, global_seq_no, tran_teller_no
                ) values (
                    :sourceIp, :transId, :txnCode, :txnTime, :messageType,
                    :requestMessage, :globalSeqNo, :tranTellerNo
                )
                """, requestParams(rows));
    }

    private void batchInsertResponses(List<MigrationSourceRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        targetJdbc.batchUpdate("""
                insert into msg_flow_log_response (
                    source_ip, trans_id, txn_code, response_time, message_type,
                    response_message, return_code, return_msg
                ) values (
                    :sourceIp, :transId, :txnCode, :responseTime, :messageType,
                    :responseMessage, :returnCode, :returnMsg
                )
                """, responseParams(rows));
    }

    private SqlParameterSource[] requestParams(List<MigrationSourceRow> rows) {
        return rows.stream()
                .map(RequestInsert::from)
                .map(BeanPropertySqlParameterSource::new)
                .toArray(SqlParameterSource[]::new);
    }

    private SqlParameterSource[] responseParams(List<MigrationSourceRow> rows) {
        return rows.stream()
                .map(ResponseInsert::from)
                .map(BeanPropertySqlParameterSource::new)
                .toArray(SqlParameterSource[]::new);
    }

    private static class BatchResult {
        private long migratedRows;
        private long skippedRows;

        private void add(BatchResult result) {
            migratedRows += result.migratedRows;
            skippedRows += result.skippedRows;
        }
    }

    @FunctionalInterface
    private interface MigrationSourceRowConsumer {
        void accept(MigrationSourceRow row) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlResponseRowConsumer {
        void accept(SqlResponseRow row) throws SQLException;
    }

    private static class StreamingAccumulator {
        private final List<MigrationSourceRow> rows;
        private long migratedRows;
        private long skippedRows;

        private StreamingAccumulator(int fetchAndBatchSize) {
            this.rows = new ArrayList<>(fetchAndBatchSize);
        }

        private void add(BatchResult result) {
            migratedRows += result.migratedRows;
            skippedRows += result.skippedRows;
        }
    }

    private static class SqlResponseAccumulator {
        private final List<SqlResponseRow> responses;
        private long migratedRows;
        private long skippedRows;
        private long droppedRows;

        private SqlResponseAccumulator(int fetchAndBatchSize) {
            this.responses = new ArrayList<>(fetchAndBatchSize);
        }

        private void add(BatchResult result) {
            migratedRows += result.migratedRows;
            skippedRows += result.skippedRows;
        }
    }

    private record MigrationKey(String sourceIp, String transId) {
        private static MigrationKey from(MigrationSourceRow row) {
            return new MigrationKey(row.sourceIp(), row.transId());
        }

        private static MigrationKey from(SqlResponseRow row) {
            return new MigrationKey(row.sourceIp(), row.transId());
        }

        private static MigrationKey from(RequestLookupRow row) {
            return new MigrationKey(row.sourceIp(), row.transId());
        }
    }

    private record RequestLookupRow(
            String sourceIp,
            String transId,
            String txnCode,
            Long txnTime,
            String messageType,
            byte[] requestMessage,
            String globalSeqNo,
            String tranTellerNo
    ) {
    }

    private record SqlResponseRow(
            String sourceIp,
            String transId,
            String txnCode,
            Long responseTime,
            String messageType,
            byte[] responseMessage,
            String returnCode,
            String returnMsg
    ) {
    }

    private record ServiceCodeMapping(
            String lookupServiceCode,
            String targetServiceCode
    ) {
    }

    private record RequestInsert(
            String sourceIp,
            String transId,
            String txnCode,
            Long txnTime,
            String messageType,
            String requestMessage,
            String globalSeqNo,
            String tranTellerNo
    ) {
        private static RequestInsert from(MigrationSourceRow row) {
            return new RequestInsert(
                    row.sourceIp(),
                    row.transId(),
                    row.requestTxnCode(),
                    row.txnTime(),
                    row.requestMessageType(),
                    encodeBlobText(row.requestMessage()),
                    row.globalSeqNo(),
                    row.tranTellerNo()
            );
        }
    }

    private record ResponseInsert(
            String sourceIp,
            String transId,
            String txnCode,
            Long responseTime,
            String messageType,
            String responseMessage,
            String returnCode,
            String returnMsg
    ) {
        private static ResponseInsert from(MigrationSourceRow row) {
            return new ResponseInsert(
                    row.sourceIp(),
                    row.transId(),
                    row.responseTxnCode(),
                    row.responseTime(),
                    row.responseMessageType(),
                    encodeBlobText(row.responseMessage()),
                    row.returnCode(),
                    row.returnMsg()
            );
        }
    }

    private static String encodeBlobText(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.toUpperCase(Character.forDigit((b >>> 4) & 0xF, 16)));
            hex.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
        }
        return hex.toString();
    }
}
