package com.spdb.report;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ReportExportCommandService {
    private static final int MAX_ERROR_LENGTH = 4000;
    private static final DateTimeFormatter BATCH_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<ReportExportTaskLauncher> launcherProvider;
    private final Clock clock;

    @Autowired
    public ReportExportCommandService(NamedParameterJdbcTemplate jdbc,
                                      ObjectProvider<ReportExportTaskLauncher> launcherProvider) {
        this(jdbc, launcherProvider, Clock.systemDefaultZone());
    }

    ReportExportCommandService(NamedParameterJdbcTemplate jdbc,
                               ObjectProvider<ReportExportTaskLauncher> launcherProvider,
                               Clock clock) {
        this.jdbc = jdbc;
        this.launcherProvider = launcherProvider;
        this.clock = clock;
    }

    public String createAndStart(String reportDate) {
        String normalizedReportDate = normalizeReportDate(reportDate);
        String batchId = nextBatchId();
        jdbc.update("""
                insert into ana_report_export_command(batch_id, report_date, status)
                values (:batchId, :reportDate, 'PENDING')
                """, params(batchId).addValue("reportDate", normalizedReportDate));
        ReportExportTaskLauncher launcher = launcherProvider == null ? null : launcherProvider.getIfAvailable();
        if (launcher != null) {
            launcher.launch(batchId);
        }
        return batchId;
    }

    public String nextBatchId() {
        return "RPT" + BATCH_TIME.format(LocalDateTime.now(clock)) + "-" + String.format("%04d", RANDOM.nextInt(10_000));
    }

    public ReportExportCommandRow findByBatchId(String batchId) {
        List<ReportExportCommandRow> rows = jdbc.query("""
                select command_id, batch_id, report_date, status, started_time, ended_time, error_message, created_time
                  from ana_report_export_command
                 where batch_id = :batchId
                """, params(batchId), (rs, rowNum) -> new ReportExportCommandRow(
                rs.getLong("command_id"), rs.getString("batch_id"), rs.getString("report_date"),
                rs.getString("status"), localDateTime(rs.getTimestamp("started_time")),
                localDateTime(rs.getTimestamp("ended_time")), rs.getString("error_message"),
                localDateTime(rs.getTimestamp("created_time"))));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ReportExportSummaryRow> findSummaries(String batchId) {
        return jdbc.query("""
                select summary_id, batch_id, report_date, module_name, covered_528_interface_count,
                       sent_transaction_count, comp_result_1_count, comp_result_2_count, comp_result_3_count,
                       comp_result_4_count, comp_result_8_count, diff_528_field_count, success_rate
                  from ana_report_export_summary
                 where batch_id = :batchId
                 order by module_name
                """, params(batchId), (rs, rowNum) -> new ReportExportSummaryRow(
                rs.getLong("summary_id"), rs.getString("batch_id"), rs.getString("report_date"),
                rs.getString("module_name"), rs.getLong("covered_528_interface_count"),
                rs.getLong("sent_transaction_count"), rs.getLong("comp_result_1_count"),
                rs.getLong("comp_result_2_count"), rs.getLong("comp_result_3_count"),
                rs.getLong("comp_result_4_count"), rs.getLong("comp_result_8_count"),
                rs.getLong("diff_528_field_count"), rs.getBigDecimal("success_rate")));
    }

    public boolean markRunning(String batchId) {
        return jdbc.update("""
                update ana_report_export_command
                   set status = 'RUNNING', started_time = current_timestamp, ended_time = null,
                       error_message = null, updated_at = current_timestamp
                 where batch_id = :batchId and status = 'PENDING'
                """, params(batchId)) == 1;
    }

    public void markSucceeded(String batchId) {
        jdbc.update("""
                update ana_report_export_command
                   set status = 'SUCCEEDED', ended_time = current_timestamp,
                       error_message = null, updated_at = current_timestamp
                 where batch_id = :batchId and status = 'RUNNING'
                """, params(batchId));
    }

    public void markFailed(String batchId, String errorMessage) {
        jdbc.update("""
                update ana_report_export_command
                   set status = 'FAILED', ended_time = current_timestamp,
                       error_message = :errorMessage, updated_at = current_timestamp
                 where batch_id = :batchId and status in ('PENDING', 'RUNNING')
                """, params(batchId).addValue("errorMessage", abbreviate(errorMessage)));
    }

    private String normalizeReportDate(String reportDate) {
        String value = reportDate == null ? "" : reportDate.trim();
        if (!value.matches("\\d{8}")) {
            throw new IllegalArgumentException("report_date必须是8位日期，例如20260725");
        }
        return value;
    }

    private MapSqlParameterSource params(String batchId) {
        return new MapSqlParameterSource("batchId", batchId);
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String abbreviate(String errorMessage) {
        if (!StringUtils.hasText(errorMessage)) {
            return null;
        }
        String value = errorMessage.trim();
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
