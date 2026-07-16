package com.spdb.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class BatchDomainReportServiceTest {
    private JdbcTemplate jdbc;
    private BatchDomainReportService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:batch_domain_report;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        service = new BatchDomainReportService(new NamedParameterJdbcTemplate(dataSource), new JdbcTransactionManager(dataSource));
        createSchema();
        seedCompletedBatch();
    }

    @Test
    void generatesDomainFieldAndGapStatisticsFromRetainedBatchDetails() {
        service.generate("BATCH-1");

        assertThat(service.findTransactionStats("BATCH-1")).singleElement().satisfies(row -> {
            assertThat(row.moduleName()).isEqualTo("存款");
            assertThat(row.coveredServiceCount()).isEqualTo(2);
            assertThat(row.sentTransactionCount()).isEqualTo(3);
            assertThat(row.compResult2Count()).isEqualTo(1);
            assertThat(row.compResult8Count()).isEqualTo(1);
        });
        assertThat(service.findFieldStats("BATCH-1")).singleElement().satisfies(row -> {
            assertThat(row.moduleName()).isEqualTo("存款");
            assertThat(row.totalFieldCount()).isEqualTo(2);
            assertThat(row.diffFieldCount()).isEqualTo(1);
            assertThat(row.noDiffFieldCount()).isEqualTo(1);
        });
        assertThat(service.findGaps("BATCH-1"))
                .extracting(BatchReportGapRow::gapType, BatchReportGapRow::serviceCode, BatchReportGapRow::fieldKey, BatchReportGapRow::affectedCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("UNCONFIGURED_SERVICE", "UNKNOWN", null, 1L),
                        org.assertj.core.groups.Tuple.tuple("UNMAPPED_FIELD", "SVC1", "unknownSoap", 2L),
                        org.assertj.core.groups.Tuple.tuple("UNMAPPED_FIELD", "SVC1", "unknownBiz", 3L));
    }

    @Test
    void rejectsIncompleteBatchAndCompletedBatchWhoseSourceDetailsWereCleaned() {
        jdbc.update("update ana_sampling_command set status = 'RUNNING' where batch_id = 'BATCH-1'");
        assertThatThrownBy(() -> service.generate("BATCH-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");

        jdbc.update("update ana_sampling_command set status = 'COMPLETED' where batch_id = 'BATCH-1'");
        jdbc.update("delete from msg_flow_log_response");
        assertThatThrownBy(() -> service.generate("BATCH-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("源明细已清理");
    }

    @Test
    void completedReportIsHistoricalAndCanOnlyBeRead() {
        service.createAndStartCommand("BATCH-1");
        new BatchDomainReportRunner(service).run("BATCH-1");

        assertThat(service.findCommand("BATCH-1").status()).isEqualTo("SUCCEEDED");
        assertThat(service.findTransactionStats("BATCH-1")).singleElement()
                .extracting(BatchDomainReportRow::sentTransactionCount).isEqualTo(3L);
        assertThatThrownBy(() -> service.generate("BATCH-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("历史");
        assertThatThrownBy(() -> service.createAndStartCommand("BATCH-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("历史");
    }

    @Test
    void commandRunnerPersistsPendingSuccessFailureAndTruncatedError() {
        service.createAndStartCommand("BATCH-1");
        assertThat(service.findCommand("BATCH-1").status()).isEqualTo("PENDING");
        new BatchDomainReportRunner(service).run("BATCH-1");
        assertThat(service.findCommand("BATCH-1").status()).isEqualTo("SUCCEEDED");

        jdbc.update("insert into ana_sampling_command values ('BATCH-EMPTY', '20260715', 'COMPLETED')");
        service.createAndStartCommand("BATCH-EMPTY");
        new BatchDomainReportRunner(service).run("BATCH-EMPTY");
        assertThat(service.findCommand("BATCH-EMPTY").status()).isEqualTo("FAILED");
        jdbc.update("update ana_batch_domain_report_command set status = 'RUNNING' where batch_id = 'BATCH-EMPTY'");
        service.markFailed("BATCH-EMPTY", "x".repeat(5000));
        assertThat(service.findCommand("BATCH-EMPTY").errorMessage()).hasSize(4000);
        BatchDomainReportExecutionConfig config = new BatchDomainReportExecutionConfig();
        var executor = config.batchDomainReportTaskExecutor();
        try {
            assertThat(executor.getThreadNamePrefix()).isEqualTo("batch-domain-report-");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void duplicateSubmissionAndStaleRunnerCannotOverwriteSucceededCommand() {
        service.createAndStartCommand("BATCH-1");
        assertThatThrownBy(() -> service.createAndStartCommand("BATCH-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("正在执行");
        BatchDomainReportRunner runner = new BatchDomainReportRunner(service);
        runner.run("BATCH-1");
        runner.run("BATCH-1");
        assertThat(service.findCommand("BATCH-1").status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void failedRetryUsesCompareAndSetAndRejectedExecutorMarksPendingCommandFailed() {
        service.createAndStartCommand("BATCH-1");
        jdbc.update("update ana_batch_domain_report_command set status = 'FAILED' where batch_id = 'BATCH-1'");
        jdbc.update("update ana_batch_domain_report_command set status = 'RUNNING' where batch_id = 'BATCH-1'");
        assertThatThrownBy(() -> service.createAndStartCommand("BATCH-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("正在执行");

        jdbc.update("update ana_batch_domain_report_command set status = 'PENDING' where batch_id = 'BATCH-1'");
        ThreadPoolTaskExecutor rejectingExecutor = new ThreadPoolTaskExecutor() {
            @Override
            public void execute(Runnable task) {
                throw new TaskRejectedException("queue full");
            }
        };
        BatchDomainReportAsyncExecutor async = new BatchDomainReportAsyncExecutor(
                provider(new BatchDomainReportRunner(service)), provider(service), rejectingExecutor);
        assertThatThrownBy(() -> async.launch("BATCH-1")).isInstanceOf(TaskRejectedException.class);
        assertThat(service.findCommand("BATCH-1").status()).isEqualTo("FAILED");
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    private void createSchema() {
        jdbc.execute("drop table if exists ana_batch_report_gap");
        jdbc.execute("drop table if exists ana_batch_domain_field_stat");
        jdbc.execute("drop table if exists ana_batch_domain_transaction_stat");
        jdbc.execute("drop table if exists ana_field_diff_result");
        jdbc.execute("drop table if exists ana_field_mapping");
        jdbc.execute("drop table if exists ana_tran_catalog");
        jdbc.execute("drop table if exists tss_tran_comp");
        jdbc.execute("drop table if exists msg_flow_log_response");
        jdbc.execute("drop table if exists ana_sampling_command");
        jdbc.execute("drop table if exists ana_batch_domain_report_command");
        jdbc.execute("create alias if not exists split_part for \"com.spdb.report.BatchDomainReportServiceTest.splitPart\"");
        jdbc.execute("create table ana_sampling_command(batch_id varchar(64) primary key, orig_cdate varchar(8), status varchar(32))");
        jdbc.execute("create table msg_flow_log_response(trans_id varchar(64), txn_code varchar(200), response_time timestamp)");
        jdbc.execute("create table tss_tran_comp(mesg_seq varchar(64), orig_cdate varchar(8), dest_trcd varchar(200), comp_result varchar(1))");
        jdbc.execute("create table ana_tran_catalog(tran_code varchar(32), service_code varchar(200), module_name varchar(100))");
        jdbc.execute("create table ana_field_mapping(tran_code varchar(32), service_code varchar(200), std_field_name varchar(200), sop_field_name varchar(200), soap_field_name varchar(200), bizjson_field_name varchar(200))");
        jdbc.execute("create table ana_field_diff_result(batch_id varchar(64), tran_code varchar(32), service_code varchar(200), message_type varchar(32), sop_field_name varchar(200), soap_field_name varchar(200), bizjson_field_name varchar(200), mapping_status varchar(32), affected_tran_count bigint)");
        jdbc.execute("create table ana_batch_domain_report_command(command_id bigint generated by default as identity primary key, batch_id varchar(64), status varchar(32), started_time timestamp, ended_time timestamp, error_message varchar(4000), created_time timestamp default current_timestamp, updated_at timestamp default current_timestamp)");
        jdbc.execute("create table ana_batch_domain_transaction_stat(batch_id varchar(64), module_name varchar(100), covered_service_count bigint, sent_transaction_count bigint, comp_result_1_count bigint, comp_result_2_count bigint, comp_result_3_count bigint, comp_result_4_count bigint, comp_result_8_count bigint)");
        jdbc.execute("create table ana_batch_domain_field_stat(batch_id varchar(64), module_name varchar(100), total_field_count bigint, diff_field_count bigint, no_diff_field_count bigint)");
        jdbc.execute("create table ana_batch_report_gap(batch_id varchar(64), gap_type varchar(32), service_code varchar(200), message_type varchar(32), field_key varchar(500), affected_count bigint)");
    }

    private void seedCompletedBatch() {
        jdbc.update("insert into ana_sampling_command values ('BATCH-1', '20260714', 'COMPLETED')");
        jdbc.update("insert into ana_tran_catalog values ('T001', 'SVC1', '存款'), ('T009', 'SVC1', '存款'), ('T002', 'SVC2', '存款')");
        jdbc.update("insert into ana_field_mapping values ('T001', 'SVC1', 'account_std', 'accountSop', 'accountSoap', 'accountBiz'), ('T001', 'SVC1', 'currency_std', 'currencySop', 'currencySoap', 'currencyBiz')");
        jdbc.update("insert into msg_flow_log_response values ('1', 'SVC1&bizjson', timestamp '2026-07-14 09:00:00'), ('2', 'SVC1&sop', timestamp '2026-07-14 09:01:00'), ('3', 'SVC2&bizjson', timestamp '2026-07-14 09:02:00'), ('4', 'UNKNOWN&bizjson', timestamp '2026-07-14 09:03:00')");
        jdbc.update("insert into tss_tran_comp values ('1', '20260714', 'WRONG&bizjson', '2'), ('2', '20260714', 'WRONG&sop', '8'), ('3', '20260714', 'SVC2&bizjson', '4')");
        jdbc.update("insert into ana_field_diff_result values ('BATCH-1', 'T001', 'SVC1', 'soap', null, 'accountSoap', null, 'MAPPED', 1), ('BATCH-1', 'T001', 'SVC1', 'soap', null, 'unknownSoap', null, 'UNMAPPED', 2), ('BATCH-1', 'T001', 'SVC1', 'bizjson', null, null, 'unknownBiz', 'UNMAPPED', 3)");
    }

    public static String splitPart(String value, String delimiter, int part) {
        if (value == null || part < 1) {
            return null;
        }
        String[] pieces = value.split(java.util.regex.Pattern.quote(delimiter), -1);
        return part <= pieces.length ? pieces[part - 1] : "";
    }
}
