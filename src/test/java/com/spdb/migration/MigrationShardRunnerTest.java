package com.spdb.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationShardRunnerTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-21T04:00:00Z"), SHANGHAI);

    @Test
    void tranCodeQueryUsesTransactionKeyOrderInsteadOfResponseTimeOrder() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/spdb/migration/MigrationShardRunner.java"));

        assertThat(source).contains("order by resp.source_ip, resp.trans_id");
        assertThat(source).doesNotContain("order by resp.response_time desc, resp.source_ip, resp.trans_id");
    }

    @Test
    void migrationQueriesFilterResponsesBeforeJoiningRequests() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/spdb/migration/MigrationShardRunner.java"));

        assertThat(source).contains("with filtered_response as (");
        assertThat(source).contains("from filtered_response resp");
    }

    @Test
    void tranCodeMigrationUsesConfiguredLookbackDays() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/spdb/migration/MigrationShardRunner.java"));

        assertThat(source).contains("int lookbackDays");
        assertThat(source).contains("effectiveLookbackDays(txnCodes, lookbackDays, currentDate)");
        assertThat(source).contains("dayOffset < effectiveLookbackDays");
    }

    private JdbcTemplate sourceJdbc;
    private JdbcTemplate targetJdbc;
    private MigrationShardRunner runner;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource sourceDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:migration_shard_source;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        DriverManagerDataSource targetDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:migration_shard_target;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        sourceJdbc = new JdbcTemplate(sourceDataSource);
        targetJdbc = new JdbcTemplate(targetDataSource);
        runner = new MigrationShardRunner(
                new NamedParameterJdbcTemplate(sourceDataSource),
                new NamedParameterJdbcTemplate(targetDataSource),
                new JdbcTransactionManager(targetDataSource)
        );
        createSchema(sourceJdbc);
        createSchema(targetJdbc);
    }

    @Test
    void pairedRowMigrates() {
        insertSourcePair("10.0.0.1", "TXN-1", "PAY001", 1000L, 1010L);

        MigrationShardResult result = runner.run(4L, 1000L, 2000L, 100);

        assertThat(result.migratedRows()).isEqualTo(1L);
        assertThat(result.skippedRows()).isZero();
        assertThat(result.droppedRows()).isZero();

        Map<String, Object> request = targetJdbc.queryForMap("""
                select *
                from msg_flow_log_request
                where source_ip = '10.0.0.1'
                  and trans_id = 'TXN-1'
                """);
        assertThat(request.get("txn_code")).isEqualTo("PAY001");
        assertThat(request.get("txn_time")).isEqualTo(1000L);
        assertThat(new String((byte[]) request.get("request_message"), StandardCharsets.UTF_8))
                .isEqualTo("726571756573742D54584E2D31");

        Map<String, Object> response = targetJdbc.queryForMap("""
                select *
                from msg_flow_log_response
                where source_ip = '10.0.0.1'
                  and trans_id = 'TXN-1'
                """);
        assertThat(response.get("txn_code")).isEqualTo("PAY001");
        assertThat(response.get("response_time")).isEqualTo(1010L);
        assertThat(new String((byte[]) response.get("response_message"), StandardCharsets.UTF_8))
                .isEqualTo("726573706F6E73652D54584E2D31");
    }

    @Test
    void sourceResponseOnlyRowIsDropped() {
        insertSourceResponseOnly("10.0.0.2", "TXN-DROPPED", "PAY002", 1500L);

        MigrationShardResult result = runner.run(4L, 1000L, 2000L, 100);

        assertThat(result.migratedRows()).isZero();
        assertThat(result.skippedRows()).isZero();
        assertThat(result.droppedRows()).isEqualTo(1L);
        assertThat(targetCount("msg_flow_log_request")).isZero();
        assertThat(targetCount("msg_flow_log_response")).isZero();
    }

    @Test
    void sqlMigrationStreamsResponsesAndLooksUpRequestsByTransactionKey() {
        insertSourcePair("10.0.0.20", "TXN-SQL-PAIR", "PAY020", 1000L, 1100L);
        insertRequest(sourceJdbc, "10.0.0.21", "TXN-SQL-REQUEST-ONLY", "PAY021", 1001L);
        insertResponse(sourceJdbc, "10.0.0.22", "TXN-SQL-RESPONSE-ONLY", "PAY022", 1102L, "000000");

        MigrationShardResult result = runner.runSql(
                9L,
                """
                        select source_ip, trans_id, txn_code, response_time, message_type,
                               response_message, return_code, return_msg
                        from msg_flow_log_response
                        where txn_code like 'PAY02%'
                        """,
                100
        );

        assertThat(result.migratedRows()).isEqualTo(1L);
        assertThat(result.skippedRows()).isZero();
        assertThat(result.droppedRows()).isEqualTo(1L);
        assertThat(targetExists("msg_flow_log_request", "10.0.0.20", "TXN-SQL-PAIR")).isTrue();
        assertThat(targetExists("msg_flow_log_response", "10.0.0.20", "TXN-SQL-PAIR")).isTrue();
        assertThat(targetExists("msg_flow_log_request", "10.0.0.21", "TXN-SQL-REQUEST-ONLY")).isFalse();
        assertThat(targetExists("msg_flow_log_response", "10.0.0.22", "TXN-SQL-RESPONSE-ONLY")).isFalse();
    }

    @Test
    void tranCodeMigrationTakesAtMostNCompletePairsPerMessageTypeFromNewestDayFirst() {
        insertServiceCode("TRAN001", "ABC.DEF");
        long today = dayStartMillis(0);
        long yesterday = dayStartMillis(-1);
        long sixDaysAgo = dayStartMillis(-6);

        insertSourcePair("10.0.1.1", "BZ-TODAY", "ABCDEF&bzjson", today, today + 10_000L);
        insertSourcePair("10.0.1.2", "BZ-YESTERDAY-NEW", "ABCDEF&bzjson", yesterday, yesterday + 20_000L);
        insertSourcePair("10.0.1.3", "BZ-YESTERDAY-OLD", "ABCDEF&bzjson", yesterday, yesterday + 10_000L);
        insertSourcePair("10.0.1.4", "BZ-OUTSIDE", "ABCDEF&bzjson", sixDaysAgo, sixDaysAgo + 10_000L);
        insertSourcePair("10.0.2.1", "SOP-NEW", "ABCDEF&sop", today, today + 20_000L);
        insertSourcePair("10.0.2.2", "SOP-OLD", "ABCDEF&sop", today, today + 10_000L);
        insertSourcePair("10.0.2.3", "SOP-NOT-TAKEN", "ABCDEF&sop", today, today + 5_000L);
        insertSourcePair("10.0.3.1", "SOAP-TODAY", "ABCDEF&soap", today, today + 10_000L);
        insertSourcePair("10.0.3.2", "SOAP-YESTERDAY", "ABCDEF&soap", yesterday, yesterday + 10_000L);

        MigrationShardResult result = runnerWithFixedClock().runTranCode(10L, "TRAN001", 2);

        assertThat(result.migratedRows()).isEqualTo(6L);
        assertThat(result.skippedRows()).isZero();
        assertThat(result.droppedRows()).isZero();
        assertThat(result.actualLookbackDays()).isEqualTo(5);
        assertThat(targetExists("msg_flow_log_response", "10.0.1.1", "BZ-TODAY")).isTrue();
        assertThat(targetExists("msg_flow_log_response", "10.0.1.2", "BZ-YESTERDAY-NEW")).isTrue();
        assertThat(targetExists("msg_flow_log_response", "10.0.1.3", "BZ-YESTERDAY-OLD")).isFalse();
        assertThat(targetExists("msg_flow_log_response", "10.0.1.4", "BZ-OUTSIDE")).isFalse();
        assertThat(targetExists("msg_flow_log_response", "10.0.2.1", "SOP-NEW")).isTrue();
        assertThat(targetExists("msg_flow_log_response", "10.0.2.2", "SOP-OLD")).isTrue();
        assertThat(targetExists("msg_flow_log_response", "10.0.2.3", "SOP-NOT-TAKEN")).isFalse();
        assertThat(targetCount("msg_flow_log_request")).isEqualTo(6L);
        assertThat(targetCount("msg_flow_log_response")).isEqualTo(6L);
    }

    @Test
    void tranCodeMigrationUsesMappingServiceCodesWhenMappingExists() {
        insertServiceMapping("TRANMAP", "528.SVC", "CCBS.SVC");
        insertServiceCode("TRANMAP", "CATALOG.SVC");
        long today = dayStartMillis(0);
        insertSourcePair("10.0.8.1", "BZ-MAPPED", "528SVC&bzjson", today, today + 1_000L);
        insertSourcePair("10.0.8.2", "BZ-CATALOG", "CATALOGSVC&bzjson", today, today + 2_000L);

        MigrationShardResult result = runnerWithFixedClock().runTranCode(16L, "TRANMAP", 1);

        assertThat(result.migratedRows()).isEqualTo(1L);
        assertThat(targetTxnCode("msg_flow_log_request", "10.0.8.1", "BZ-MAPPED")).isEqualTo("CCBSSVC&bzjson");
        assertThat(targetTxnCode("msg_flow_log_response", "10.0.8.1", "BZ-MAPPED")).isEqualTo("CCBSSVC&bzjson");
        assertThat(targetExists("msg_flow_log_response", "10.0.8.2", "BZ-CATALOG")).isFalse();
    }

    @Test
    void tranCodeMigrationFallsBackToOnlineServiceCodeWhenMappingIsMissing() {
        insertServiceCode("TRANCAT", "CATALOG.SVC");
        long today = dayStartMillis(0);
        insertSourcePair("10.0.9.1", "BZ-CATALOG", "CATALOGSVC&bzjson", today, today + 1_000L);

        MigrationShardResult result = runnerWithFixedClock().runTranCode(17L, "TRANCAT", 1);

        assertThat(result.migratedRows()).isEqualTo(1L);
        assertThat(targetTxnCode("msg_flow_log_request", "10.0.9.1", "BZ-CATALOG")).isEqualTo("CATALOGSVC&bzjson");
        assertThat(targetTxnCode("msg_flow_log_response", "10.0.9.1", "BZ-CATALOG")).isEqualTo("CATALOGSVC&bzjson");
    }

    @Test
    void tranCodeMigrationReturnsZeroWhenServiceIsMissingOrNoCompletePairExists() {
        insertServiceCode("TRAN002", "ABC.DEF");
        insertSourceResponseOnly("10.0.4.1", "ORPHAN", "ABCDEF&bzjson", dayStartMillis(0) + 1_000L);

        MigrationShardResult missingService = runnerWithFixedClock().runTranCode(11L, "UNKNOWN", 3);
        MigrationShardResult noCompletePair = runnerWithFixedClock().runTranCode(12L, "TRAN002", 3);

        assertThat(missingService).isEqualTo(new MigrationShardResult(0L, 0L, 0L, 0));
        assertThat(noCompletePair).isEqualTo(new MigrationShardResult(0L, 0L, 0L, 5));
        assertThat(targetCount("msg_flow_log_request")).isZero();
        assertThat(targetCount("msg_flow_log_response")).isZero();
    }

    @Test
    void tranCodeMigrationBackfillsFromEarlierDaysWhenNewestCandidateIsAlreadyMigrated() {
        insertServiceCode("TRAN003", "ABC.DEF");
        long today = dayStartMillis(0);
        long yesterday = dayStartMillis(-1);
        insertSourcePair("10.0.5.1", "BZ-EXISTING", "ABCDEF&bzjson", today, today + 10_000L);
        insertSourcePair("10.0.5.2", "BZ-TODAY-NEW", "ABCDEF&bzjson", today, today + 9_000L);
        insertSourcePair("10.0.5.3", "BZ-YESTERDAY-NEW", "ABCDEF&bzjson", yesterday, yesterday + 10_000L);
        insertTargetRequest("10.0.5.1", "BZ-EXISTING", "ABCDEF&bzjson", today);
        insertTargetResponse("10.0.5.1", "BZ-EXISTING", "ABCDEF&bzjson", today + 10_000L);

        MigrationShardResult result = runnerWithFixedClock().runTranCode(13L, "TRAN003", 2);

        assertThat(result.migratedRows()).isEqualTo(2L);
        assertThat(result.skippedRows()).isEqualTo(1L);
        assertThat(result.actualLookbackDays()).isEqualTo(5);
        assertThat(targetExists("msg_flow_log_response", "10.0.5.2", "BZ-TODAY-NEW")).isTrue();
        assertThat(targetExists("msg_flow_log_response", "10.0.5.3", "BZ-YESTERDAY-NEW")).isTrue();
    }

    @Test
    void tranCodeMigrationUsesApplicationDateAsFiveDayWindowAnchor() {
        insertServiceCode("TRAN004", "ABC.DEF");
        long latestDay = dayStartMillis(-14);
        insertSourcePair("10.0.6.1", "BZ-HISTORICAL", "ABCDEF&bzjson", latestDay, latestDay + 2_000L);
        insertSourcePair("10.0.6.2", "SOP-HISTORICAL", "ABCDEF&sop", latestDay, latestDay + 4_000L);
        insertSourcePair("10.0.6.3", "SOAP-HISTORICAL", "ABCDEF&soap", latestDay, latestDay + 6_000L);

        MigrationShardResult result = runnerWithFixedClock().runTranCode(14L, "TRAN004", 1);

        assertThat(result).isEqualTo(new MigrationShardResult(0L, 0L, 0L, 5));
        assertThat(targetCount("msg_flow_log_request")).isZero();
        assertThat(targetCount("msg_flow_log_response")).isZero();
    }

    @Test
    void tranCodeMigrationAllLookbackDaysScansBackToEarliestResponseDate() {
        insertServiceCode("TRANALL", "ABC.DEF");
        long earliestDay = dayStartMillis(-20);
        long historicalDay = dayStartMillis(-14);
        insertSourceResponseOnly("10.0.10.1", "BZ-EARLIEST-ORPHAN", "ABCDEF&bzjson", earliestDay + 1_000L);
        insertSourcePair("10.0.10.2", "BZ-HISTORICAL", "ABCDEF&bzjson", historicalDay, historicalDay + 2_000L);

        MigrationShardResult result = runnerWithFixedClock().runTranCode(
                18L,
                "TRANALL",
                1,
                MigrationTranCodeCommandForm.ALL_LOOKBACK_DAYS
        );

        assertThat(result.migratedRows()).isEqualTo(1L);
        assertThat(result.actualLookbackDays()).isEqualTo(21);
        assertThat(targetExists("msg_flow_log_response", "10.0.10.2", "BZ-HISTORICAL")).isTrue();
    }

    @Test
    void tranCodeMigrationLimitsCurrentDayToTheCurrentTime() {
        insertServiceCode("TRAN005", "ABC.DEF");
        long today = dayStartMillis(0);
        insertSourcePair("10.0.7.1", "FUTURE", "ABCDEF&bzjson", today + 13 * 60 * 60 * 1000L, today + 13 * 60 * 60 * 1000L);
        insertSourcePair("10.0.7.2", "CURRENT", "ABCDEF&bzjson", today + 11 * 60 * 60 * 1000L, today + 11 * 60 * 60 * 1000L);

        MigrationShardResult result = runnerWithFixedClock().runTranCode(15L, "TRAN005", 1);

        assertThat(result.migratedRows()).isEqualTo(1L);
        assertThat(result.actualLookbackDays()).isEqualTo(5);
        assertThat(targetExists("msg_flow_log_response", "10.0.7.1", "FUTURE")).isFalse();
        assertThat(targetExists("msg_flow_log_response", "10.0.7.2", "CURRENT")).isTrue();
    }

    @Test
    void responseTimeWindowIsHalfOpen() {
        insertSourcePair("10.0.0.7", "TXN-AT-FROM", "PAY007", 900L, 1000L);
        insertSourcePair("10.0.0.8", "TXN-AT-TO", "PAY008", 900L, 2000L);

        MigrationShardResult result = runner.run(4L, 1000L, 2000L, 100);

        assertThat(result.migratedRows()).isEqualTo(1L);
        assertThat(result.skippedRows()).isZero();
        assertThat(result.droppedRows()).isZero();
        assertThat(targetExists("msg_flow_log_request", "10.0.0.7", "TXN-AT-FROM")).isTrue();
        assertThat(targetExists("msg_flow_log_response", "10.0.0.7", "TXN-AT-FROM")).isTrue();
        assertThat(targetExists("msg_flow_log_request", "10.0.0.8", "TXN-AT-TO")).isFalse();
        assertThat(targetExists("msg_flow_log_response", "10.0.0.8", "TXN-AT-TO")).isFalse();
    }

    @Test
    void pairedRowIsSkippedIfTargetRequestAlreadyContainsKey() {
        insertSourcePair("10.0.0.3", "TXN-SKIP-REQUEST", "PAY003", 1000L, 1100L);
        insertTargetRequest("10.0.0.3", "TXN-SKIP-REQUEST", "EXISTING", 900L);

        MigrationShardResult result = runner.run(4L, 1000L, 2000L, 100);

        assertThat(result.migratedRows()).isZero();
        assertThat(result.skippedRows()).isEqualTo(1L);
        assertThat(result.droppedRows()).isZero();
        assertThat(targetCount("msg_flow_log_request")).isEqualTo(1L);
        assertThat(targetCount("msg_flow_log_response")).isZero();
    }

    @Test
    void pairedRowIsSkippedIfTargetResponseAlreadyContainsKey() {
        insertSourcePair("10.0.0.4", "TXN-SKIP-RESPONSE", "PAY004", 1000L, 1100L);
        insertTargetResponse("10.0.0.4", "TXN-SKIP-RESPONSE", "EXISTING", 900L);

        MigrationShardResult result = runner.run(4L, 1000L, 2000L, 100);

        assertThat(result.migratedRows()).isZero();
        assertThat(result.skippedRows()).isEqualTo(1L);
        assertThat(result.droppedRows()).isZero();
        assertThat(targetCount("msg_flow_log_request")).isZero();
        assertThat(targetCount("msg_flow_log_response")).isEqualTo(1L);
    }

    @Test
    void duplicateSourceKeyInSameBatchIsSkippedAfterFirstInsert() {
        insertSourcePair("10.0.0.13", "TXN-DUPLICATE", "PAY013", 1000L, 1100L);
        insertResponse(sourceJdbc, "10.0.0.13", "TXN-DUPLICATE", "PAY013", 1101L, "000000");

        MigrationShardResult result = runner.run(4L, 1000L, 2000L, 10);

        assertThat(result.migratedRows()).isEqualTo(1L);
        assertThat(result.skippedRows()).isEqualTo(1L);
        assertThat(targetCount("msg_flow_log_request")).isEqualTo(1L);
        assertThat(targetCount("msg_flow_log_response")).isEqualTo(1L);
    }

    @Test
    void duplicateSourceKeyAcrossFlushesIsSkippedAfterFirstCommit() {
        insertSourcePair("10.0.0.14", "TXN-DUPLICATE-FLUSH", "PAY014", 1000L, 1100L);
        insertResponse(sourceJdbc, "10.0.0.14", "TXN-DUPLICATE-FLUSH", "PAY014", 1101L, "000000");

        MigrationShardResult result = runner.run(4L, 1000L, 2000L, 1);

        assertThat(result.migratedRows()).isEqualTo(1L);
        assertThat(result.skippedRows()).isEqualTo(1L);
        assertThat(targetCount("msg_flow_log_request")).isEqualTo(1L);
        assertThat(targetCount("msg_flow_log_response")).isEqualTo(1L);
    }

    @Test
    void sourceStreamingUsesFetchSizeAndDisablesAutocommit() {
        DriverManagerDataSource rawSourceDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:migration_shard_fetch_size_source;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        DriverManagerDataSource rawTargetDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:migration_shard_fetch_size_target;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        List<Integer> fetchSizes = new ArrayList<>();
        List<Boolean> autoCommitValues = new ArrayList<>();
        DataSource recordingSourceDataSource = recordingSourceDataSource(rawSourceDataSource, fetchSizes, autoCommitValues);
        sourceJdbc = new JdbcTemplate(rawSourceDataSource);
        targetJdbc = new JdbcTemplate(rawTargetDataSource);
        runner = new MigrationShardRunner(
                new NamedParameterJdbcTemplate(recordingSourceDataSource),
                new NamedParameterJdbcTemplate(rawTargetDataSource),
                new JdbcTransactionManager(rawTargetDataSource)
        );
        createSchema(sourceJdbc);
        createSchema(targetJdbc);
        insertSourcePair("10.0.0.9", "TXN-FETCH-SIZE", "PAY009", 1000L, 1100L);

        runner.run(4L, 1000L, 2000L, 2);

        assertThat(fetchSizes).contains(2);
        assertThat(autoCommitValues).containsExactly(false, true);
    }

    @Test
    void targetBlobColumnsAreBoundAsHexText() {
        DriverManagerDataSource rawSourceDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:migration_shard_blob_source;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        DriverManagerDataSource rawTargetDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:migration_shard_blob_target;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        List<Object> boundValues = new ArrayList<>();
        DataSource recordingTargetDataSource = recordingTargetDataSource(rawTargetDataSource, boundValues);
        sourceJdbc = new JdbcTemplate(rawSourceDataSource);
        targetJdbc = new JdbcTemplate(rawTargetDataSource);
        runner = new MigrationShardRunner(
                new NamedParameterJdbcTemplate(rawSourceDataSource),
                new NamedParameterJdbcTemplate(recordingTargetDataSource),
                new JdbcTransactionManager(recordingTargetDataSource)
        );
        createSchema(sourceJdbc);
        createSchema(targetJdbc);
        insertSourcePair("10.0.0.15", "TXN-BLOB", "PAY015", 1000L, 1100L);

        runner.run(4L, 1000L, 2000L, 100);

        assertThat(boundValues).doesNotHaveAnyElementsOfTypes(byte[].class);
        assertThat(boundValues).contains(
                "726571756573742D54584E2D424C4F42",
                "726573706F6E73652D54584E2D424C4F42"
        );
    }

    @Test
    void committedBatchesAreRetriedAsSkipsWhenLaterBatchFails() {
        insertSourcePair("10.0.0.10", "TXN-BATCH-1A", "PAY010", 1000L, 1100L);
        insertSourcePair("10.0.0.11", "TXN-BATCH-1B", "PAY011", 1001L, 1101L);
        insertSourcePair("10.0.0.12", "TXN-BATCH-2-FAIL", "PAY012", 1002L, 1102L);
        targetJdbc.execute("alter table msg_flow_log_response add constraint fail_late_response_code check (return_code <> 'FAIL')");
        sourceJdbc.update("""
                update msg_flow_log_response
                set return_code = 'FAIL'
                where source_ip = ?
                  and trans_id = ?
                """, "10.0.0.12", "TXN-BATCH-2-FAIL");

        assertThatThrownBy(() -> runner.run(4L, 1000L, 2000L, 2))
                .isInstanceOf(DataAccessException.class);

        assertThat(targetExists("msg_flow_log_request", "10.0.0.10", "TXN-BATCH-1A")).isTrue();
        assertThat(targetExists("msg_flow_log_response", "10.0.0.10", "TXN-BATCH-1A")).isTrue();
        assertThat(targetExists("msg_flow_log_request", "10.0.0.11", "TXN-BATCH-1B")).isTrue();
        assertThat(targetExists("msg_flow_log_response", "10.0.0.11", "TXN-BATCH-1B")).isTrue();
        assertThat(targetExists("msg_flow_log_request", "10.0.0.12", "TXN-BATCH-2-FAIL")).isFalse();
        assertThat(targetExists("msg_flow_log_response", "10.0.0.12", "TXN-BATCH-2-FAIL")).isFalse();

        sourceJdbc.update("""
                update msg_flow_log_response
                set return_code = '000000'
                where source_ip = ?
                  and trans_id = ?
                """, "10.0.0.12", "TXN-BATCH-2-FAIL");
        MigrationShardResult retryResult = runner.run(4L, 1000L, 2000L, 2);

        assertThat(retryResult.migratedRows()).isEqualTo(1L);
        assertThat(retryResult.skippedRows()).isEqualTo(2L);
        assertThat(targetCount("msg_flow_log_request")).isEqualTo(3L);
        assertThat(targetCount("msg_flow_log_response")).isEqualTo(3L);
    }

    @Test
    void targetWritesRollbackWholeBatchWhenInsertFails() {
        insertSourcePair("10.0.0.5", "TXN-OK-IN-FAILED-BATCH", "PAY005", 1000L, 1100L);
        insertSourcePair("10.0.0.6", "TXN-FAIL-IN-BATCH", "PAY006", 1001L, 1101L);
        targetJdbc.execute("alter table msg_flow_log_response add constraint fail_response_code check (return_code <> 'FAIL')");
        sourceJdbc.update("""
                update msg_flow_log_response
                set return_code = 'FAIL'
                where source_ip = ?
                  and trans_id = ?
                """, "10.0.0.6", "TXN-FAIL-IN-BATCH");

        assertThatThrownBy(() -> runner.run(4L, 1000L, 2000L, 2))
                .isInstanceOf(DataAccessException.class);

        assertThat(targetCount("msg_flow_log_request")).isZero();
        assertThat(targetCount("msg_flow_log_response")).isZero();
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("drop table if exists msg_flow_log_request");
        jdbc.execute("drop table if exists msg_flow_log_response");
        jdbc.execute("drop table if exists tp_online_service_in");
        jdbc.execute("drop table if exists ana_tran_code_service_mapping");
        jdbc.execute("""
                create table msg_flow_log_request (
                    source_ip varchar(64) not null,
                    trans_id varchar(64) not null,
                    txn_code varchar(64) not null,
                    txn_time bigint not null,
                    message_type varchar(32),
                    request_message bytea,
                    global_seq_no varchar(64),
                    tran_teller_no varchar(32)
                )
                """);
        jdbc.execute("""
                create table tp_online_service_in (
                    tran_code varchar(64) not null,
                    esf_service_code varchar(128) not null
                )
                """);
        jdbc.execute("""
                create table ana_tran_code_service_mapping (
                    mapping_id bigint generated by default as identity primary key,
                    tran_code varchar(32) not null,
                    "528_service_code" varchar(200) not null,
                    ccbs_service_code varchar(200) not null
                )
                """);
        jdbc.execute("""
                create table msg_flow_log_response (
                    source_ip varchar(64) not null,
                    trans_id varchar(64) not null,
                    txn_code varchar(64) not null,
                    response_time bigint,
                    message_type varchar(32),
                    response_message bytea,
                    return_code varchar(32),
                    return_msg varchar(512)
                )
                """);
    }

    private MigrationShardRunner runnerWithFixedClock() {
        return new MigrationShardRunner(
                new NamedParameterJdbcTemplate(sourceJdbc),
                new NamedParameterJdbcTemplate(targetJdbc),
                new JdbcTransactionManager(targetJdbc.getDataSource()),
                FIXED_CLOCK
        );
    }

    private long dayStartMillis(int dayOffset) {
        return LocalDate.now(FIXED_CLOCK).plusDays(dayOffset).atStartOfDay(SHANGHAI).toInstant().toEpochMilli();
    }

    private void insertServiceCode(String tranCode, String serviceCode) {
        targetJdbc.update("insert into tp_online_service_in (tran_code, esf_service_code) values (?, ?)", tranCode, serviceCode);
    }

    private void insertServiceMapping(String tranCode, String service528Code, String ccbsServiceCode) {
        targetJdbc.update("""
                insert into ana_tran_code_service_mapping (tran_code, "528_service_code", ccbs_service_code)
                values (?, ?, ?)
                """, tranCode, service528Code, ccbsServiceCode);
    }

    private void insertSourcePair(String sourceIp, String transId, String txnCode, long txnTime, long responseTime) {
        insertRequest(sourceJdbc, sourceIp, transId, txnCode, txnTime);
        insertResponse(sourceJdbc, sourceIp, transId, txnCode, responseTime, "000000");
    }

    private void insertSourceResponseOnly(String sourceIp, String transId, String txnCode, long responseTime) {
        insertResponse(sourceJdbc, sourceIp, transId, txnCode, responseTime, "000000");
    }

    private void insertTargetRequest(String sourceIp, String transId, String txnCode, long txnTime) {
        insertRequest(targetJdbc, sourceIp, transId, txnCode, txnTime);
    }

    private void insertTargetResponse(String sourceIp, String transId, String txnCode, long responseTime) {
        insertResponse(targetJdbc, sourceIp, transId, txnCode, responseTime, "000000");
    }

    private void insertRequest(JdbcTemplate jdbc, String sourceIp, String transId, String txnCode, long txnTime) {
        jdbc.update("""
                insert into msg_flow_log_request (
                    source_ip, trans_id, txn_code, txn_time, message_type,
                    request_message, global_seq_no, tran_teller_no
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sourceIp,
                transId,
                txnCode,
                txnTime,
                "JSON",
                ("request-" + transId).getBytes(StandardCharsets.UTF_8),
                "GLOBAL-" + transId,
                "TELLER01"
        );
    }

    private void insertResponse(JdbcTemplate jdbc,
                                String sourceIp,
                                String transId,
                                String txnCode,
                                long responseTime,
                                String returnCode) {
        jdbc.update("""
                insert into msg_flow_log_response (
                    source_ip, trans_id, txn_code, response_time, message_type,
                    response_message, return_code, return_msg
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sourceIp,
                transId,
                txnCode,
                responseTime,
                "JSON",
                ("response-" + transId).getBytes(StandardCharsets.UTF_8),
                returnCode,
                "success"
        );
    }

    private long targetCount(String tableName) {
        Long count = targetJdbc.queryForObject("select count(*) from " + tableName, Long.class);
        return count == null ? 0L : count;
    }

    private boolean targetExists(String tableName, String sourceIp, String transId) {
        Long count = targetJdbc.queryForObject(
                "select count(*) from " + tableName + " where source_ip = ? and trans_id = ?",
                Long.class,
                sourceIp,
                transId
        );
        return count != null && count > 0L;
    }

    private String targetTxnCode(String tableName, String sourceIp, String transId) {
        return targetJdbc.queryForObject(
                "select txn_code from " + tableName + " where source_ip = ? and trans_id = ?",
                String.class,
                sourceIp,
                transId
        );
    }

    private DataSource recordingSourceDataSource(DataSource delegate,
                                                 List<Integer> fetchSizes,
                                                 List<Boolean> autoCommitValues) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = method.invoke(delegate, args);
                    if (result instanceof Connection connection) {
                        return recordingConnection(connection, fetchSizes, autoCommitValues);
                    }
                    return result;
                }
        );
    }

    private DataSource recordingTargetDataSource(DataSource delegate, List<Object> boundValues) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = method.invoke(delegate, args);
                    if (result instanceof Connection connection) {
                        return recordingTargetConnection(connection, boundValues);
                    }
                    return result;
                }
        );
    }

    private Connection recordingConnection(Connection delegate,
                                           List<Integer> fetchSizes,
                                           List<Boolean> autoCommitValues) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("setAutoCommit".equals(method.getName())) {
                        autoCommitValues.add((Boolean) args[0]);
                    }
                    Object result = method.invoke(delegate, args);
                    if (result instanceof PreparedStatement statement) {
                        return recordingPreparedStatement(statement, fetchSizes);
                    }
                    return result;
                }
        );
    }

    private PreparedStatement recordingPreparedStatement(PreparedStatement delegate, List<Integer> fetchSizes) {
        return recordingPreparedStatement(delegate, fetchSizes, null);
    }

    private Connection recordingTargetConnection(Connection delegate, List<Object> boundValues) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = method.invoke(delegate, args);
                    if (result instanceof PreparedStatement statement) {
                        return recordingPreparedStatement(statement, null, boundValues);
                    }
                    return result;
                }
        );
    }

    private PreparedStatement recordingPreparedStatement(PreparedStatement delegate,
                                                         List<Integer> fetchSizes,
                                                         List<Object> boundValues) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("setFetchSize".equals(method.getName()) && fetchSizes != null) {
                        fetchSizes.add((Integer) args[0]);
                    } else if (method.getName().startsWith("set") && args != null && args.length >= 2 && boundValues != null) {
                        Object value = args[1];
                        boundValues.add(value instanceof byte[] bytes ? Arrays.copyOf(bytes, bytes.length) : value);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (ReflectiveOperationException ex) {
                        Throwable cause = ex.getCause();
                        if (cause instanceof SQLException sqlException) {
                            throw sqlException;
                        }
                        throw ex;
                    }
                }
        );
    }
}
