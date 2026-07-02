package com.spdb.sampling;

import com.spdb.web.PageRequestParams;
import com.spdb.web.PagedResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class SamplingCommandService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<SamplingTaskLauncher> samplingTaskLauncher;
    private final SamplingBatchRunner samplingBatchRunner;
    private final Clock clock;

    @Autowired
    public SamplingCommandService(NamedParameterJdbcTemplate jdbc,
                                  ObjectProvider<SamplingTaskLauncher> samplingTaskLauncher,
                                  SamplingBatchRunner samplingBatchRunner) {
        this(jdbc, samplingTaskLauncher, samplingBatchRunner, Clock.systemDefaultZone());
    }

    SamplingCommandService(NamedParameterJdbcTemplate jdbc, ObjectProvider<SamplingTaskLauncher> samplingTaskLauncher, Clock clock) {
        this(jdbc, samplingTaskLauncher, null, clock);
    }

    SamplingCommandService(NamedParameterJdbcTemplate jdbc,
                           ObjectProvider<SamplingTaskLauncher> samplingTaskLauncher,
                           SamplingBatchRunner samplingBatchRunner,
                           Clock clock) {
        this.jdbc = jdbc;
        this.samplingTaskLauncher = samplingTaskLauncher;
        this.samplingBatchRunner = samplingBatchRunner;
        this.clock = clock;
    }

    public String nextBatchId(String origCdate) {
        LocalDateTime now = LocalDateTime.now(clock);
        int suffix = RANDOM.nextInt(10_000);
        return "SMP" + normalizeOrigCdate(origCdate) + "-" + TIME.format(now) + "-" + String.format("%04d", suffix);
    }

    public String createCommand(SamplingCommandForm form) {
        validate(form);
        String origCdate = normalizeOrigCdate(form.origCdate());
        String batchId = nextBatchId(origCdate);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("batchId", batchId)
                .addValue("origCdate", origCdate)
                .addValue("remark", textOrNull(form.remark()));
        jdbc.update("""
                insert into ana_sampling_command (
                    batch_id, orig_cdate, status, remark, created_by
                ) values (
                    :batchId, :origCdate, 'CREATED', :remark, '系统'
                )
                """, params);
        SamplingTaskLauncher launcher = samplingTaskLauncher == null ? null : samplingTaskLauncher.getIfAvailable();
        if (launcher != null) {
            launcher.launch(batchId);
        }
        return batchId;
    }

    public PagedResult<SamplingCommandRow> search(SamplingCommandSearchCriteria criteria, PageRequestParams page) {
        QueryParts query = where(criteria);
        query.params.addValue("limit", page.size()).addValue("offset", page.offset());
        List<SamplingCommandRow> rows = jdbc.query("""
                        select c.command_id, c.batch_id, c.orig_cdate, c.sample_type, c.tran_code, c.service_code,
                               c.status, c.job_execution_id,
                               case
                                   when c.started_time is null then null
                                   else extract(epoch from (coalesce(c.ended_time, current_timestamp) - c.started_time))::bigint
                               end as duration_seconds,
                               coalesce(s.total_tran_count, c.total_tran_count, 0) as total_tran_count,
                               coalesce(s.comp_result_1_count, 0) as comp_result_1_count,
                               coalesce(s.comp_result_2_count, 0) as comp_result_2_count,
                               coalesce(s.comp_result_3_count, 0) as comp_result_3_count,
                               coalesce(s.comp_result_4_count, 0) as comp_result_4_count,
                               coalesce(s.comp_result_8_count, 0) as comp_result_8_count,
                               coalesce(s.pass_tran_count, 0) as pass_tran_count,
                               coalesce(s.tran_issue_count, 0) as tran_issue_count,
                               coalesce(s.return_code_issue_count, 0) as return_code_issue_count,
                               coalesce(s.issue_field_count, c.field_diff_count, 0) as issue_field_count,
                               coalesce(s.field_diff_tran_count, 0) as field_diff_tran_count,
                               coalesce(s.unconfigured_service_count, 0) as unconfigured_service_count,
                               coalesce(s.unmapped_field_count, 0) as unmapped_field_count,
                               coalesce(s.fully_matched_count, 0) as fully_matched_count,
                               c.field_diff_count,
                               coalesce(s.sample_group_count, c.sample_group_count, 0) as sample_group_count,
                               coalesce(s.sample_detail_count, c.sample_detail_count, 0) as sample_detail_count,
                               c.error_message, c.remark,
                               created_time, started_time, ended_time
                        from ana_sampling_command c
                        left join ana_sampling_summary s on s.batch_id = c.batch_id
                        """ + query.where + " order by c.created_time desc limit :limit offset :offset",
                query.params, (rs, i) -> new SamplingCommandRow(
                        rs.getLong("command_id"),
                        rs.getString("batch_id"),
                        rs.getString("orig_cdate"),
                        rs.getString("sample_type"),
                        rs.getString("tran_code"),
                        rs.getString("service_code"),
                        rs.getString("status"),
                        getLongOrNull(rs.getObject("job_execution_id")),
                        formatDuration(getLongOrNull(rs.getObject("duration_seconds"))),
                        rs.getLong("total_tran_count"),
                        rs.getLong("comp_result_1_count"),
                        rs.getLong("comp_result_2_count"),
                        rs.getLong("comp_result_3_count"),
                        rs.getLong("comp_result_4_count"),
                        rs.getLong("comp_result_8_count"),
                        rs.getLong("pass_tran_count"),
                        rs.getLong("tran_issue_count"),
                        rs.getLong("return_code_issue_count"),
                        rs.getLong("issue_field_count"),
                        rs.getLong("field_diff_tran_count"),
                        rs.getLong("unconfigured_service_count"),
                        rs.getLong("unmapped_field_count"),
                        rs.getLong("fully_matched_count"),
                        rs.getLong("field_diff_count"),
                        rs.getLong("sample_group_count"),
                        rs.getLong("sample_detail_count"),
                        rs.getString("error_message"),
                        rs.getString("remark"),
                        rs.getObject("created_time", LocalDateTime.class),
                        rs.getObject("started_time", LocalDateTime.class),
                        rs.getObject("ended_time", LocalDateTime.class)
                ));
        Long total = jdbc.queryForObject("select count(*) from ana_sampling_command c" + query.where, query.params, Long.class);
        return PagedResult.of(rows, total == null ? 0 : total, page);
    }

    public SamplingCommandRow findByBatchId(String batchId) {
        PagedResult<SamplingCommandRow> result = search(new SamplingCommandSearchCriteria(batchId, null, null), PageRequestParams.of(1, 20));
        return result.rows().isEmpty() ? null : result.rows().get(0);
    }

    public void markRunning(String batchId, Long jobExecutionId) {
        jdbc.update("""
                update ana_sampling_command
                   set status = 'RUNNING', job_execution_id = :jobExecutionId, started_time = current_timestamp
                 where batch_id = :batchId
                """, new MapSqlParameterSource().addValue("batchId", batchId).addValue("jobExecutionId", jobExecutionId));
    }

    public void runSamplingBatch(String batchId) {
        SamplingCommandRow command = findByBatchId(batchId);
        if (command == null) {
            throw new IllegalArgumentException("采样批次不存在：" + batchId);
        }
        if (samplingBatchRunner == null) {
            throw new IllegalStateException("采样执行器未初始化");
        }
        samplingBatchRunner.run(command);
    }

    public void markCompleted(String batchId) {
        jdbc.update("""
                update ana_sampling_command c
                   set status = 'COMPLETED',
                       ended_time = current_timestamp,
                       total_tran_count = coalesce((select total_tran_count from ana_sampling_summary where batch_id = c.batch_id), 0),
                       field_diff_count = coalesce((select issue_field_count from ana_sampling_summary where batch_id = c.batch_id), 0),
                       sample_group_count = coalesce((select sample_group_count from ana_sampling_summary where batch_id = c.batch_id), 0),
                       sample_detail_count = coalesce((select sample_detail_count from ana_sampling_summary where batch_id = c.batch_id), 0)
                 where c.batch_id = :batchId
                """, new MapSqlParameterSource().addValue("batchId", batchId));
    }

    public void markFailed(String batchId, String errorMessage) {
        jdbc.update("""
                update ana_sampling_command
                   set status = 'FAILED', ended_time = current_timestamp, error_message = :errorMessage
                 where batch_id = :batchId
                """, new MapSqlParameterSource()
                .addValue("batchId", batchId)
                .addValue("errorMessage", abbreviate(errorMessage == null ? "采样执行失败" : errorMessage, 2000)));
    }

    private void validate(SamplingCommandForm form) {
        if (form == null || !StringUtils.hasText(form.origCdate())) {
            throw new IllegalArgumentException("orig_cdate不能为空");
        }
        normalizeOrigCdate(form.origCdate());
    }

    private String normalizeOrigCdate(String origCdate) {
        String value = origCdate == null ? "" : origCdate.trim();
        if (!value.matches("\\d{8}")) {
            throw new IllegalArgumentException("orig_cdate必须是8位日期，例如20260608");
        }
        return value;
    }

    private String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private QueryParts where(SamplingCommandSearchCriteria criteria) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (criteria != null) {
            like(clauses, params, "c.batch_id", "batchId", criteria.batchId());
            eq(clauses, params, "c.orig_cdate", "origCdate", criteria.origCdate());
            eq(clauses, params, "c.status", "status", criteria.status());
        }
        return new QueryParts(clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses), params);
    }

    private void like(List<String> clauses, MapSqlParameterSource params, String column, String key, String value) {
        if (StringUtils.hasText(value)) {
            clauses.add(column + " like :" + key);
            params.addValue(key, "%" + value.trim() + "%");
        }
    }

    private void eq(List<String> clauses, MapSqlParameterSource params, String column, String key, String value) {
        if (StringUtils.hasText(value)) {
            clauses.add(column + " = :" + key);
            params.addValue(key, value.trim());
        }
    }

    private Long getLongOrNull(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private String formatDuration(Long seconds) {
        if (seconds == null || seconds < 0) {
            return "-";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) {
            return hours + "时" + minutes + "分" + remainingSeconds + "秒";
        }
        if (minutes > 0) {
            return minutes + "分" + remainingSeconds + "秒";
        }
        return remainingSeconds + "秒";
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record QueryParts(String where, MapSqlParameterSource params) {}
}
