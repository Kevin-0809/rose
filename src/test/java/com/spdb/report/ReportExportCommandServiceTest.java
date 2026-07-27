package com.spdb.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.spdb.web.PageRequestParams;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReportExportCommandServiceTest {
    private JdbcTemplate jdbc;
    private ReportExportCommandService service;
    private String launchedBatchId;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:report_export_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        service = new ReportExportCommandService(new NamedParameterJdbcTemplate(dataSource), launcherProvider(),
                Clock.fixed(Instant.parse("2026-07-26T01:02:03Z"), ZoneOffset.UTC));
        createSchema();
    }

    @Test
    void createsPendingCommandForReportDateAndLaunchesIt() {
        String batchId = service.createAndStart();

        assertThat(batchId).matches("RPT20260726-010203-\\d{4}");
        assertThat(launchedBatchId).isEqualTo(batchId);
        assertThat(service.findByBatchId(batchId)).satisfies(row -> {
            assertThat(row.reportDate()).isEqualTo("20260726");
            assertThat(row.status()).isEqualTo("PENDING");
        });
    }

    @Test
    void transitionsOnlyThroughAllowedLifecycleStatesAndTruncatesFailure() {
        String batchId = service.createAndStart();

        assertThat(service.markRunning(batchId)).isTrue();
        assertThat(service.markRunning(batchId)).isFalse();
        service.markSucceeded(batchId);
        service.markFailed(batchId, "ignored");
        assertThat(service.findByBatchId(batchId).status()).isEqualTo("SUCCEEDED");

        String failedBatch = service.createAndStart();
        service.markFailed(failedBatch, "x".repeat(5000));
        assertThat(service.findByBatchId(failedBatch)).satisfies(row -> {
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.errorMessage()).hasSize(4000);
        });
    }

    @Test
    void persistsCurrentStageWhileRunningRetainsItOnFailureAndClearsItOnSuccess() {
        String failedBatch = service.createAndStart();
        assertThat(service.markRunning(failedBatch)).isTrue();
        service.markStage(failedBatch, ReportExportStage.TRANSACTION_DETAILS);
        assertThat(service.findByBatchId(failedBatch).currentStage()).isEqualTo("TRANSACTION_DETAILS");

        service.markFailed(failedBatch, "failed");
        assertThat(service.findByBatchId(failedBatch).currentStage()).isEqualTo("TRANSACTION_DETAILS");

        String succeededBatch = service.createAndStart();
        assertThat(service.markRunning(succeededBatch)).isTrue();
        service.markStage(succeededBatch, ReportExportStage.SUMMARY);
        service.markSucceeded(succeededBatch);
        assertThat(service.findByBatchId(succeededBatch).currentStage()).isNull();
    }

    @Test
    void ignoresStageUpdatesUnlessTheCommandIsRunning() {
        String batchId = service.createAndStart();

        service.markStage(batchId, ReportExportStage.TRANSACTION_DETAILS);
        assertThat(service.findByBatchId(batchId).currentStage()).isNull();

        assertThat(service.markRunning(batchId)).isTrue();
        service.markFailed(batchId, "failed");
        service.markStage(batchId, ReportExportStage.SUMMARY);
        assertThat(service.findByBatchId(batchId).currentStage()).isNull();
    }

    @Test
    void returnsOnlyExportSummaryRowsForTheRequestedBatch() {
        String batchId = service.createAndStart();
        jdbc.update("insert into ana_report_export_summary(batch_id, report_date, module_name, sent_transaction_count) values (?, ?, ?, ?)",
                batchId, "20260726", "模块A", 11L);
        jdbc.update("insert into ana_report_export_summary(batch_id, report_date, module_name) values (?, ?, ?)",
                "RPT-OTHER", "20260724", "模块B");

        List<ReportExportSummaryRow> summaries = service.findSummaries(batchId);

        assertThat(summaries).singleElement().satisfies(row -> {
            assertThat(row.batchId()).isEqualTo(batchId);
            assertThat(row.moduleName()).isEqualTo("模块A");
            assertThat(row.sentTransactionCount()).isEqualTo(11L);
        });
    }

    @Test
    void returnsOnlyTrackingDetailsForTheRequestedBatchInRowOrder() {
        jdbc.update("insert into ana_tran_diff_tracking_export(source_batch_id, row_no, service_code, tran_code) values (?, ?, ?, ?)",
                "RPT-OTHER", 1L, "other", "T0");
        jdbc.update("insert into ana_tran_diff_tracking_export(source_batch_id, row_no, service_code, orig_error_code, dest_error_code, tran_code, module_name) values (?, ?, ?, ?, ?, ?, ?)",
                "RPT1", 2L, "svc-b", "O2", "D2", "T2", "支付");
        jdbc.update("insert into ana_tran_diff_tracking_export(source_batch_id, row_no, service_code, tran_code) values (?, ?, ?, ?)",
                "RPT1", 1L, "svc-a", "T1");
        jdbc.update("insert into ana_field_diff_tracking_export(source_batch_id, row_no, service_code, soap_field_name, field_name, mapping_status) values (?, ?, ?, ?, ?, ?)",
                "RPT1", 1L, "svc-a", "items.0", "items.0", "UNMAPPED");
        jdbc.update("insert into ana_field_diff_tracking_export(source_batch_id, row_no, service_code, soap_field_name) values (?, ?, ?, ?)",
                "RPT-OTHER", 1L, "other", "ignored");

        assertThat(service.findTransactionDetails("RPT1")).extracting(ReportExportTransactionDetailRow::rowNo)
                .containsExactly(1L, 2L);
        assertThat(service.findTransactionDetails("RPT1").get(1)).satisfies(row -> {
            assertThat(row.origErrorCode()).isEqualTo("O2");
            assertThat(row.destErrorCode()).isEqualTo("D2");
            assertThat(row.moduleName()).isEqualTo("支付");
        });
        assertThat(service.findFieldDetails("RPT1")).singleElement().satisfies(row -> {
            assertThat(row.serviceCode()).isEqualTo("svc-a");
            assertThat(row.soapFieldName()).isEqualTo("items.0");
            assertThat(row.mappingStatus()).isEqualTo("UNMAPPED");
        });
    }

    @Test
    void returnsTheLastAvailableDetailPageWhenTheRequestedPageIsTooLarge() {
        jdbc.update("insert into ana_tran_diff_tracking_export(source_batch_id, row_no, service_code, tran_code) values (?, ?, ?, ?)",
                "RPT1", 1L, "svc-a", "T1");
        jdbc.update("insert into ana_field_diff_tracking_export(source_batch_id, row_no, service_code, soap_field_name) values (?, ?, ?, ?)",
                "RPT1", 1L, "svc-a", "amount");

        assertThat(service.searchTransactionDetails("RPT1", PageRequestParams.of(9, 50)))
                .satisfies(result -> {
                    assertThat(result.page()).isEqualTo(1);
                    assertThat(result.rows()).extracting(ReportExportTransactionDetailRow::rowNo).containsExactly(1L);
                });
        assertThat(service.searchFieldDetails("RPT1", PageRequestParams.of(9, 50)))
                .satisfies(result -> {
                    assertThat(result.page()).isEqualTo(1);
                    assertThat(result.rows()).extracting(ReportExportFieldDetailRow::rowNo).containsExactly(1L);
                });
    }

    private ObjectProvider<ReportExportTaskLauncher> launcherProvider() {
        ReportExportTaskLauncher launcher = batchId -> launchedBatchId = batchId;
        return new ObjectProvider<>() {
            @Override public ReportExportTaskLauncher getObject(Object... args) { return launcher; }
            @Override public ReportExportTaskLauncher getObject() { return launcher; }
            @Override public ReportExportTaskLauncher getIfAvailable() { return launcher; }
            @Override public ReportExportTaskLauncher getIfUnique() { return launcher; }
        };
    }

    private void createSchema() {
        jdbc.execute("create table ana_report_export_command(command_id bigint generated by default as identity primary key, batch_id varchar(64) not null unique, report_date varchar(8) not null, status varchar(32) not null, current_stage varchar(32), started_time timestamp, ended_time timestamp, error_message varchar(4000), created_time timestamp not null default current_timestamp, updated_at timestamp not null default current_timestamp)");
        jdbc.execute("create table ana_report_export_summary(summary_id bigint generated by default as identity primary key, batch_id varchar(64) not null, report_date varchar(8) not null, module_name varchar(100) not null, covered_528_interface_count bigint not null default 0, sent_transaction_count bigint not null default 0, comp_result_1_count bigint not null default 0, comp_result_2_count bigint not null default 0, comp_result_3_count bigint not null default 0, comp_result_4_count bigint not null default 0, comp_result_8_count bigint not null default 0, diff_528_field_count bigint not null default 0, success_rate decimal(12,8) not null default 0, created_time timestamp not null default current_timestamp, updated_at timestamp not null default current_timestamp)");
        jdbc.execute("create table ana_tran_diff_tracking_export(export_id bigint generated by default as identity primary key, source_batch_id varchar(64) not null, row_no bigint not null, service_code varchar(200) not null, orig_error_code varchar(64), dest_error_code varchar(64), tran_code varchar(32), tran_name varchar(200), module_name varchar(100), orig_error_desc varchar(500), dest_error_desc varchar(500))");
        jdbc.execute("create table ana_field_diff_tracking_export(export_id bigint generated by default as identity primary key, source_batch_id varchar(64) not null, row_no bigint not null, service_code varchar(200) not null, tran_code varchar(32), tran_name varchar(200), module_name varchar(100), soap_field_name varchar(200), field_name varchar(500), mapping_status varchar(32), orig_field_value varchar(2000), dest_field_value varchar(2000))");
    }
}
