package com.spdb.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiffIssueLedgerServiceTest {
    private JdbcTemplate jdbc;
    private DiffIssueLedgerService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:diff_issue_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        service = new DiffIssueLedgerService(new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void firstOccurrenceCreatesOpenLedgerAndWritesZeroHistory() {
        insertTransactionDetail("B1", 1, "TRAN|svc-a|E1|D1", "svc-a", "E1", "D1");

        service.materializeBatch("B1", LocalDate.of(2026, 7, 27));

        assertThat(service.findById(issueId("TRAN|svc-a|E1|D1"))).satisfies(issue -> {
            assertThat(issue.issueStatus()).isEqualTo("OPEN");
            assertThat(issue.occurrenceBatchCount()).isEqualTo(1);
            assertThat(issue.firstSeenDate()).isEqualTo(LocalDate.of(2026, 7, 27));
            assertThat(issue.lastSeenDate()).isEqualTo(LocalDate.of(2026, 7, 27));
        });
        assertThat(jdbc.queryForMap("select issue_id, historical_occurrence_count, first_seen_date, previous_seen_date from ana_tran_diff_tracking_export where source_batch_id = 'B1'") )
                .satisfies(snapshot -> {
                    assertThat(((Number) snapshot.get("issue_id")).longValue()).isPositive();
                    assertThat(((Number) snapshot.get("historical_occurrence_count")).longValue()).isZero();
                    assertThat(snapshot.get("first_seen_date")).isEqualTo(java.sql.Date.valueOf("2026-07-27"));
                    assertThat(snapshot.get("previous_seen_date")).isNull();
                });
    }

    @Test
    void laterBatchIncrementsOnceAndCopiesMaintenanceFields() {
        insertTransactionDetail("B1", 1, "TRAN|svc-a|E1|D1", "svc-a", "E1", "D1");
        service.materializeBatch("B1", LocalDate.of(2026, 7, 20));
        DiffIssueRow first = service.findById(issueId("TRAN|svc-a|E1|D1"));
        service.update(first.issueId(), new DiffIssueUpdate("TYPE", "analysis", "solution", "OPEN", "yes", "Lee",
                LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 22)), first.updatedAt());
        insertTransactionDetail("B2", 1, "TRAN|svc-a|E1|D1", "svc-a", "E1", "D1");

        service.materializeBatch("B2", LocalDate.of(2026, 7, 27));

        assertThat(jdbc.queryForMap("select historical_occurrence_count, first_seen_date, previous_seen_date, problem_type, preliminary_analysis, final_solution, resolver, resolution_date from ana_tran_diff_tracking_export where source_batch_id = 'B2'"))
                .satisfies(snapshot -> {
                    assertThat(((Number) snapshot.get("historical_occurrence_count")).longValue()).isEqualTo(1);
                    assertThat(snapshot.get("first_seen_date")).isEqualTo(java.sql.Date.valueOf("2026-07-20"));
                    assertThat(snapshot.get("previous_seen_date")).isEqualTo(java.sql.Date.valueOf("2026-07-20"));
                    assertThat(snapshot.get("problem_type")).isEqualTo("TYPE");
                    assertThat(snapshot.get("preliminary_analysis")).isEqualTo("analysis");
                    assertThat(snapshot.get("final_solution")).isEqualTo("solution");
                    assertThat(snapshot.get("resolver")).isEqualTo("Lee");
                    assertThat(snapshot.get("resolution_date")).isEqualTo(java.sql.Date.valueOf("2026-07-21"));
                });
    }

    @Test
    void resolvedIssueReopensWhenSeenAgainButKeepsSolution() {
        insertFieldDetail("B1", 1, "FIELD|svc-a|amount", "svc-a", "amount");
        service.materializeBatch("B1", LocalDate.of(2026, 7, 20));
        DiffIssueRow first = service.findById(issueId("FIELD|svc-a|amount"));
        service.update(first.issueId(), new DiffIssueUpdate("TYPE", "analysis", "solution", "RESOLVED", "no", "Lee",
                LocalDate.of(2026, 7, 21), null), first.updatedAt());
        insertFieldDetail("B2", 1, "FIELD|svc-a|amount", "svc-a", "amount");

        service.materializeBatch("B2", LocalDate.of(2026, 7, 27));

        assertThat(service.findById(first.issueId())).satisfies(issue -> {
            assertThat(issue.issueStatus()).isEqualTo("OPEN");
            assertThat(issue.finalSolution()).isEqualTo("solution");
            assertThat(issue.resolver()).isEqualTo("Lee");
        });
    }

    @Test
    void oneKeyRepeatedInOneBatchIncrementsOnlyOnce() {
        insertTransactionDetail("B1", 1, "TRAN|svc-a|E1|D1", "svc-a", "E1", "D1");
        service.materializeBatch("B1", LocalDate.of(2026, 7, 20));
        insertTransactionDetail("B2", 1, "TRAN|svc-a|E1|D1", "svc-a", "E1", "D1");
        insertTransactionDetail("B2", 2, "TRAN|svc-a|E1|D1", "svc-a", "E1", "D1");

        service.materializeBatch("B2", LocalDate.of(2026, 7, 27));

        assertThat(service.findById(issueId("TRAN|svc-a|E1|D1")).occurrenceBatchCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(distinct issue_id) from ana_tran_diff_tracking_export where source_batch_id = 'B2'", Long.class)).isEqualTo(1);
    }

    @Test
    void updateRejectsResolvedWithoutResolutionDate() {
        insertTransactionDetail("B1", 1, "TRAN|svc-a|E1|D1", "svc-a", "E1", "D1");
        service.materializeBatch("B1", LocalDate.of(2026, 7, 27));
        DiffIssueRow issue = service.findById(issueId("TRAN|svc-a|E1|D1"));

        assertThatThrownBy(() -> service.update(issue.issueId(), new DiffIssueUpdate(null, null, null, "RESOLVED", null, null, null, null), issue.updatedAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolution date");
    }

    private long issueId(String key) {
        return jdbc.queryForObject("select issue_id from ana_diff_issue where issue_key = ?", Long.class, key);
    }

    private void insertTransactionDetail(String batchId, long rowNo, String issueKey, String serviceCode, String origCode, String destCode) {
        jdbc.update("insert into ana_tran_diff_tracking_export(source_batch_id, row_no, issue_key, service_code, orig_error_code, dest_error_code, tran_code, tran_name, module_name, transaction_owner, problem_description) values (?, ?, ?, ?, ?, ?, 'T1', 'Transaction', 'Module', 'Owner', 'description')",
                batchId, rowNo, issueKey, serviceCode, origCode, destCode);
    }

    private void insertFieldDetail(String batchId, long rowNo, String issueKey, String serviceCode, String fieldName) {
        jdbc.update("insert into ana_field_diff_tracking_export(source_batch_id, row_no, issue_key, service_code, tran_code, tran_name, module_name, transaction_owner, field_name, problem_description) values (?, ?, ?, ?, 'T1', 'Transaction', 'Module', 'Owner', ?, 'description')",
                batchId, rowNo, issueKey, serviceCode, fieldName);
    }

    private void createSchema() {
        jdbc.execute("create table ana_diff_issue(issue_id bigint generated by default as identity primary key, issue_key varchar(600) not null unique, issue_level varchar(16) not null, service_code varchar(200) not null, tran_code varchar(32), tran_name varchar(200), module_name varchar(100), transaction_owner varchar(100), orig_error_code varchar(64), dest_error_code varchar(64), normalized_source_field_name varchar(500), problem_type varchar(100), problem_description varchar(2000), preliminary_analysis varchar(2000), final_solution varchar(2000), issue_status varchar(16) not null, coordination_required varchar(100), resolver varchar(100), resolution_date date, defect_fix_date date, first_seen_date date not null, last_seen_date date not null, first_seen_batch_id varchar(64) not null, last_seen_batch_id varchar(64) not null, occurrence_batch_count bigint not null, created_at timestamp not null default current_timestamp, updated_at timestamp not null default current_timestamp)");
        jdbc.execute("create table ana_tran_diff_tracking_export(export_id bigint generated by default as identity primary key, source_batch_id varchar(64) not null, row_no bigint not null, issue_id bigint, issue_key varchar(600), historical_occurrence_count bigint not null default 0, first_seen_date date, previous_seen_date date, service_code varchar(200) not null, orig_error_code varchar(64), dest_error_code varchar(64), tran_code varchar(32), tran_name varchar(200), module_name varchar(100), transaction_owner varchar(100), problem_description varchar(2000), problem_type varchar(100), preliminary_analysis varchar(2000), final_solution varchar(2000), issue_status varchar(16), coordination_required varchar(100), resolver varchar(100), resolution_date date, defect_fix_date date)");
        jdbc.execute("create table ana_field_diff_tracking_export(export_id bigint generated by default as identity primary key, source_batch_id varchar(64) not null, row_no bigint not null, issue_id bigint, issue_key varchar(600), historical_occurrence_count bigint not null default 0, first_seen_date date, previous_seen_date date, service_code varchar(200) not null, tran_code varchar(32), tran_name varchar(200), module_name varchar(100), transaction_owner varchar(100), field_name varchar(500), problem_description varchar(2000), problem_type varchar(100), preliminary_analysis varchar(2000), final_solution varchar(2000), issue_status varchar(16), coordination_required varchar(100), resolver varchar(100), resolution_date date, defect_fix_date date)");
    }
}
