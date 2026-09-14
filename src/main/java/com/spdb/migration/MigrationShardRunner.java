package com.spdb.migration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

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
    private final TranCodeSourcePartition tranCodePartition;

    @Autowired
    public MigrationShardRunner(@Qualifier("bxdsJdbcTemplate") NamedParameterJdbcTemplate sourceJdbc,
                                NamedParameterJdbcTemplate targetJdbc,
                                PlatformTransactionManager transactionManager) {
        this(sourceJdbc, targetJdbc, transactionManager, Clock.system(SHANGHAI),
                new JdbcTranCodeSourcePartition(sourceJdbc));
    }

    MigrationShardRunner(@Qualifier("bxdsJdbcTemplate") NamedParameterJdbcTemplate sourceJdbc,
                         NamedParameterJdbcTemplate targetJdbc,
                         PlatformTransactionManager transactionManager,
                         Clock clock) {
        this(sourceJdbc, targetJdbc, transactionManager, clock, new JdbcTranCodeSourcePartition(sourceJdbc));
    }

    MigrationShardRunner(@Qualifier("bxdsJdbcTemplate") NamedParameterJdbcTemplate sourceJdbc,
                         NamedParameterJdbcTemplate targetJdbc,
                         PlatformTransactionManager transactionManager,
                         Clock clock,
                         TranCodeSourcePartition tranCodePartition) {
        this.sourceJdbc = sourceJdbc;
        this.targetJdbc = targetJdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.tranCodePartition = tranCodePartition;
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

    public MigrationShardResult runSql(long shardId, String requestSql, int fetchSize) {
        int fetchAndBatchSize = Math.max(fetchSize, 1);
        SqlRequestAccumulator accumulator = new SqlRequestAccumulator(fetchAndBatchSize);

        streamSqlRequestRows(requestSql, fetchAndBatchSize, row -> {
            accumulator.requests.add(row);
            if (accumulator.requests.size() >= fetchAndBatchSize) {
                accumulator.add(flushSqlRequestBatch(accumulator));
            }
        });
        if (!accumulator.requests.isEmpty()) {
            accumulator.add(flushSqlRequestBatch(accumulator));
        }

        return new MigrationShardResult(accumulator.migratedRows, accumulator.skippedRows, 0L);
    }

    public MigrationShardResult runTranCode(long shardId, String tranCode, int maxRowsPerMessageType) {
        return runTranCode(shardId, tranCode, maxRowsPerMessageType, MigrationTranCodeCommandForm.DEFAULT_LOOKBACK_DAYS);
    }

    public MigrationShardResult runTranCode(long shardId,
                                             String tranCode,
                                             int maxRowsPerMessageType,
                                             int lookbackDays) {
        return runTranCode(shardId, tranCode, maxRowsPerMessageType, lookbackDays, null, null, null);
    }

    public MigrationShardResult runTranCode(long shardId, String tranCode, int maxRowsPerMessageType,
                                             int lookbackDays, String collectionType, LocalDate baseDate) {
        return runTranCode(shardId, tranCode, maxRowsPerMessageType, lookbackDays, collectionType, baseDate, null, () -> false);
    }

    public MigrationShardResult runTranCode(long shardId, String tranCode, int maxRowsPerMessageType,
                                             int lookbackDays, String collectionType, LocalDate baseDate,
                                             List<ServiceCodeMapping> preloadedMappings) {
        return runTranCode(shardId, tranCode, maxRowsPerMessageType, lookbackDays, collectionType, baseDate,
                preloadedMappings, () -> false);
    }

    public MigrationShardResult runTranCode(long shardId, String tranCode, int maxRowsPerMessageType,
                                             int lookbackDays, String collectionType, LocalDate baseDate,
                                             List<ServiceCodeMapping> preloadedMappings,
                                             BooleanSupplier cancelRequested) {
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
        List<ServiceCodeMapping> serviceMappings = preloadedMappings != null
                ? preloadedMappings
                : loadServiceCodeMappings(tranCode);
        if (serviceMappings.isEmpty()) {
            log.info("Transaction-code migration skipped, shardId={}, tranCode={}, reason=no service code mapping",
                    shardId, tranCode);
            return new MigrationShardResult(0L, 0L, 0L, 0);
        }

        BatchResult total = new BatchResult();
        LocalDate currentDate = LocalDate.now(clock.withZone(SHANGHAI));
        int effectiveLookbackDays = effectiveLookbackDays(lookbackDays, currentDate);
        Map<LocalDate, Boolean> partitionExists = new LinkedHashMap<>();
        log.info("Transaction-code migration started, shardId={}, tranCode={}, serviceCodeCount={}, sampleSizePerMessageType={}, lookbackDays={}, currentDate={}",
                shardId, tranCode, serviceMappings.size(), maxRowsPerMessageType, lookbackDays, currentDate);
        for (String messageType : TRAN_CODE_MESSAGE_TYPES) {
            if (cancelRequested.getAsBoolean()) {
                break;
            }
            Map<String, String> targetTxnCodeBySourceTxnCode = txnCodeMappings(serviceMappings, messageType);
            List<String> sourceTxnCodes = new ArrayList<>(targetTxnCodeBySourceTxnCode.keySet());
            long migratedRows = 0L;
            List<LocalDate> scanDates = scanDates(currentDate, effectiveLookbackDays, collectionType, baseDate);
            for (LocalDate scanDate : scanDates) {
                if (migratedRows >= maxRowsPerMessageType || cancelRequested.getAsBoolean()) {
                    break;
                }
                if (!partitionExists.computeIfAbsent(scanDate, tranCodePartition::exists)) {
                    log.debug("Transaction-code migration skipped missing partition, shardId={}, tranCode={}, messageType={}, scanDate={}",
                            shardId, tranCode, messageType, scanDate);
                    continue;
                }
                log.debug("Transaction-code migration scanning partition, shardId={}, tranCode={}, messageType={}, scanDate={}, partition={}, migratedRows={}",
                        shardId, tranCode, messageType, scanDate, tranCodePartition.requestTableRef(scanDate), migratedRows);
                int remainingRows = (int) (maxRowsPerMessageType - migratedRows);
                boolean ascending = baseDate != null && scanDate.isAfter(baseDate);
                List<TranCodeRequestRow> rows = loadTranCodeRequestRows(scanDate, sourceTxnCodes, remainingRows, ascending);
                if (rows.isEmpty()) {
                    continue;
                }
                List<TranCodeRequestRow> targetRows = rows.stream()
                        .map(row -> withTargetTxnCode(row, targetTxnCodeBySourceTxnCode.getOrDefault(
                                row.txnCode(),
                                row.txnCode()
                        )))
                        .toList();
                BatchResult result = transactionTemplate.execute(status -> writeRequestBatch(targetRows));
                BatchResult batchResult = result == null ? new BatchResult() : result;
                total.add(batchResult);
                migratedRows += batchResult.migratedRows;
            }
            log.info("Transaction-code migration message type completed, shardId={}, tranCode={}, messageType={}, migratedRows={}, targetRows={}",
                    shardId, tranCode, messageType, migratedRows, maxRowsPerMessageType);
        }
        log.info("Transaction-code migration completed, shardId={}, tranCode={}, migratedRows={}, skippedRows={}, cancelled={}",
                shardId, tranCode, total.migratedRows, total.skippedRows, cancelRequested.getAsBoolean());
        return new MigrationShardResult(total.migratedRows, total.skippedRows, 0L, effectiveLookbackDays);
    }

    private List<LocalDate> scanDates(LocalDate currentDate, int lookbackDays, String collectionType, LocalDate baseDate) {
        if (baseDate == null) {
            return java.util.stream.IntStream.range(0, lookbackDays).mapToObj(currentDate::minusDays).toList();
        }
        LocalDate base = baseDate.isAfter(currentDate) ? currentDate : baseDate;
        List<LocalDate> dates = new ArrayList<>();
        Set<LocalDate> seen = new LinkedHashSet<>();
        if (MigrationTranCodeCommandForm.COLLECTION_TYPE_ACCOUNT.equals(collectionType)) {
            for (LocalDate date = base; !date.isAfter(currentDate); date = date.plusDays(1)) {
                if (seen.add(date)) {
                    dates.add(date);
                }
            }
            for (int dayOffset = 1; dayOffset <= lookbackDays; dayOffset++) {
                LocalDate date = base.minusDays(dayOffset);
                if (seen.add(date)) {
                    dates.add(date);
                }
            }
            return dates;
        }
        for (int dayOffset = 0; dayOffset < lookbackDays; dayOffset++) {
            LocalDate date = base.minusDays(dayOffset);
            if (seen.add(date)) {
                dates.add(date);
            }
        }
        for (LocalDate date = base.plusDays(1); !date.isAfter(currentDate); date = date.plusDays(1)) {
            if (seen.add(date)) {
                dates.add(date);
            }
        }
        return dates;
    }

    public Map<String, List<ServiceCodeMapping>> loadTranCodeMappings(List<String> tranCodes) {
        List<String> distinctCodes = tranCodes == null ? List.of() : tranCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
        if (distinctCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, List<ServiceCodeMapping>> mappedByCode = new LinkedHashMap<>();
        targetJdbc.query("""
                select tran_code,
                       "528_service_code" as lookup_service_code,
                       ccbs_service_code as target_service_code
                from ana_tran_code_service_mapping
                where tran_code in (:tranCodes)
                order by tran_code, mapping_id
                """, new MapSqlParameterSource("tranCodes", distinctCodes), rs -> {
            String tranCode = rs.getString("tran_code");
            mappedByCode.computeIfAbsent(tranCode, key -> new ArrayList<>())
                    .add(new ServiceCodeMapping(rs.getString("lookup_service_code"), rs.getString("target_service_code")));
        });
        Map<String, List<ServiceCodeMapping>> result = new LinkedHashMap<>();
        List<String> fallbackCodes = new ArrayList<>();
        for (String code : distinctCodes) {
            List<ServiceCodeMapping> effective = distinctServiceMappings(mappedByCode.getOrDefault(code, List.of()));
            result.put(code, effective);
            if (effective.isEmpty()) {
                fallbackCodes.add(code);
            }
        }
        if (!fallbackCodes.isEmpty()) {
            Map<String, List<ServiceCodeMapping>> onlineByCode = new LinkedHashMap<>();
            targetJdbc.query("""
                    select tran_code, esf_service_code
                    from tp_online_service_in
                    where tran_code in (:tranCodes)
                    """, new MapSqlParameterSource("tranCodes", fallbackCodes), rs -> {
                String tranCode = rs.getString("tran_code");
                String serviceCode = rs.getString("esf_service_code");
                onlineByCode.computeIfAbsent(tranCode, key -> new ArrayList<>())
                        .add(new ServiceCodeMapping(serviceCode, serviceCode));
            });
            for (String code : fallbackCodes) {
                List<ServiceCodeMapping> effective = distinctServiceMappings(onlineByCode.getOrDefault(code, List.of()));
                if (!effective.isEmpty()) {
                    result.put(code, effective);
                }
            }
        }
        return result;
    }

    private List<ServiceCodeMapping> loadServiceCodeMappings(String tranCode) {
        return loadTranCodeMappings(List.of(tranCode)).getOrDefault(tranCode, List.of());
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

    private int effectiveLookbackDays(int lookbackDays, LocalDate currentDate) {
        if (lookbackDays != MigrationTranCodeCommandForm.ALL_LOOKBACK_DAYS) {
            return lookbackDays;
        }
        LocalDate earliestDate = tranCodePartition.earliestPartitionDate();
        if (earliestDate == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(earliestDate, currentDate) + 1;
        if (days <= 0) {
            return 1;
        }
        return days > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) days;
    }

    private List<TranCodeRequestRow> loadTranCodeRequestRows(LocalDate scanDate,
                                                              List<String> txnCodes,
                                                              int limit,
                                                              boolean ascending) {
        if (txnCodes.isEmpty() || limit <= 0) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("txnCodes", txnCodes)
                .addValue("limit", limit);
        String orderDirection = ascending ? "asc" : "desc";
        return sourceJdbc.query("""
                select source_ip, trans_id, txn_code, txn_time, message_type,
                       request_message, global_seq_no, tran_teller_no
                from %s
                where txn_code in (:txnCodes)
                order by txn_time %s
                limit :limit
                """.formatted(tranCodePartition.requestTableRef(scanDate), orderDirection), params,
                (rs, rowNum) -> mapTranCodeRequestRow(rs));
    }

    private BatchResult writeRequestBatch(List<TranCodeRequestRow> rows) {
        BatchResult result = new BatchResult();
        for (int start = 0; start < rows.size(); start += MAX_TARGET_CHUNK_SIZE) {
            int end = Math.min(start + MAX_TARGET_CHUNK_SIZE, rows.size());
            result.add(writeRequestChunk(rows.subList(start, end)));
        }
        return result;
    }

    private BatchResult writeRequestChunk(List<TranCodeRequestRow> rows) {
        BatchResult result = new BatchResult();
        Set<MigrationKey> existingKeys = loadExistingRequestKeys(rows);
        List<TranCodeRequestRow> inserts = new ArrayList<>();
        for (TranCodeRequestRow row : rows) {
            MigrationKey key = MigrationKey.from(row);
            if (existingKeys.contains(key)) {
                result.skippedRows++;
                continue;
            }
            inserts.add(row);
            existingKeys.add(key);
        }
        batchInsertRequestRows(inserts);
        result.migratedRows += inserts.size();
        return result;
    }

    private Set<MigrationKey> loadExistingRequestKeys(List<TranCodeRequestRow> rows) {
        if (rows.isEmpty()) {
            return Set.of();
        }
        Set<MigrationKey> keys = new HashSet<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        String predicate = keyPredicateForKeys(rows.stream().map(MigrationKey::from).toList(), params);
        collectExistingKeys(keys, "msg_flow_log_request", predicate, params);
        return keys;
    }

    private void batchInsertRequestRows(List<TranCodeRequestRow> rows) {
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
                """, rows.stream()
                .map(row -> new MapSqlParameterSource()
                        .addValue("sourceIp", row.sourceIp())
                        .addValue("transId", row.transId())
                        .addValue("txnCode", row.txnCode())
                        .addValue("txnTime", row.txnTime())
                        .addValue("messageType", row.messageType())
                        .addValue("requestMessage", row.requestMessage(), Types.BINARY)
                        .addValue("globalSeqNo", row.globalSeqNo())
                        .addValue("tranTellerNo", row.tranTellerNo()))
                .toArray(SqlParameterSource[]::new));
    }

    private TranCodeRequestRow mapTranCodeRequestRow(ResultSet rs) throws SQLException {
        return new TranCodeRequestRow(
                rs.getString("source_ip"),
                rs.getString("trans_id"),
                rs.getString("txn_code"),
                nullableLong(rs, "txn_time"),
                rs.getString("message_type"),
                rs.getBytes("request_message"),
                rs.getString("global_seq_no"),
                rs.getString("tran_teller_no")
        );
    }

    private TranCodeRequestRow withTargetTxnCode(TranCodeRequestRow row, String txnCode) {
        return new TranCodeRequestRow(
                row.sourceIp(),
                row.transId(),
                txnCode,
                row.txnTime(),
                row.messageType(),
                row.requestMessage(),
                row.globalSeqNo(),
                row.tranTellerNo()
        );
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

    private void streamSqlRequestRows(String requestSql,
                                      int fetchSize,
                                      TranCodeRequestRowConsumer consumer) {
        sourceJdbc.getJdbcTemplate().execute((Connection connection) -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    sqlRequestQuery(requestSql),
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY
            )) {
                statement.setFetchSize(fetchSize);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        consumer.accept(mapSqlRequestRow(rs));
                    }
                }
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
            return null;
        });
    }

    private String sqlRequestQuery(String requestSql) {
        return """
                select req.source_ip,
                       req.trans_id,
                       req.txn_code,
                       req.txn_time,
                       req.message_type,
                       req.request_message,
                       req.global_seq_no,
                       req.tran_teller_no
                from (
                %s
                ) req
                order by req.txn_time, req.source_ip, req.trans_id
                """.formatted(requestSql);
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

    private TranCodeRequestRow mapSqlRequestRow(ResultSet rs) throws SQLException {
        return new TranCodeRequestRow(
                rs.getString("source_ip"),
                rs.getString("trans_id"),
                rs.getString("txn_code"),
                nullableLong(rs, "txn_time"),
                rs.getString("message_type"),
                rs.getBytes("request_message"),
                rs.getString("global_seq_no"),
                rs.getString("tran_teller_no")
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

    private BatchResult flushSqlRequestBatch(SqlRequestAccumulator accumulator) {
        List<TranCodeRequestRow> rows = List.copyOf(accumulator.requests);
        accumulator.requests.clear();
        if (rows.isEmpty()) {
            return new BatchResult();
        }
        BatchResult batchResult = transactionTemplate.execute(status -> writeRequestBatch(rows));
        return batchResult == null ? new BatchResult() : batchResult;
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
                .map(row -> new MapSqlParameterSource()
                        .addValue("sourceIp", row.sourceIp())
                        .addValue("transId", row.transId())
                        .addValue("txnCode", row.requestTxnCode())
                        .addValue("txnTime", row.txnTime())
                        .addValue("messageType", row.requestMessageType())
                        .addValue("requestMessage", row.requestMessage(), Types.BINARY)
                        .addValue("globalSeqNo", row.globalSeqNo())
                        .addValue("tranTellerNo", row.tranTellerNo()))
                .toArray(SqlParameterSource[]::new);
    }

    private SqlParameterSource[] responseParams(List<MigrationSourceRow> rows) {
        return rows.stream()
                .map(row -> new MapSqlParameterSource()
                        .addValue("sourceIp", row.sourceIp())
                        .addValue("transId", row.transId())
                        .addValue("txnCode", row.responseTxnCode())
                        .addValue("responseTime", row.responseTime())
                        .addValue("messageType", row.responseMessageType())
                        .addValue("responseMessage", row.responseMessage(), Types.BINARY)
                        .addValue("returnCode", row.returnCode())
                        .addValue("returnMsg", row.returnMsg()))
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
    private interface TranCodeRequestRowConsumer {
        void accept(TranCodeRequestRow row) throws SQLException;
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

    private static class SqlRequestAccumulator {
        private final List<TranCodeRequestRow> requests;
        private long migratedRows;
        private long skippedRows;

        private SqlRequestAccumulator(int fetchAndBatchSize) {
            this.requests = new ArrayList<>(fetchAndBatchSize);
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

        private static MigrationKey from(TranCodeRequestRow row) {
            return new MigrationKey(row.sourceIp(), row.transId());
        }
    }

    private record TranCodeRequestRow(
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

    public record ServiceCodeMapping(
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
            byte[] requestMessage,
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
                    row.requestMessage(),
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
            byte[] responseMessage,
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
                    row.responseMessage(),
                    row.returnCode(),
                    row.returnMsg()
            );
        }
    }

}
