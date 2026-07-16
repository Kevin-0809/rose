package com.spdb.report;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class BatchDomainReportService {
    private static final DateTimeFormatter BUSINESS_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_ERROR_LENGTH = 4000;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<BatchDomainReportTaskLauncher> launcherProvider;

    public BatchDomainReportService(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, null);
    }

    public BatchDomainReportService(NamedParameterJdbcTemplate jdbc,
                                    PlatformTransactionManager transactionManager,
                                    ObjectProvider<BatchDomainReportTaskLauncher> launcherProvider) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.launcherProvider = launcherProvider;
    }

    public void generate(String batchId) {
        BatchContext context = batchContext(batchId);
        if (!"COMPLETED".equals(context.status())) {
            throw new IllegalStateException("采样批次必须处于 COMPLETED 状态：" + batchId);
        }
        BatchDomainReportCommandRow command = findCommand(batchId);
        if (command != null && "SUCCEEDED".equals(command.status())) {
            throw new IllegalStateException("历史成功报表不可重新生成：" + batchId);
        }
        if (!hasRetainedResponseDetails(context)) {
            throw new IllegalStateException("源明细已清理，不能重新生成批次领域报表：" + batchId);
        }
        transactionTemplate.executeWithoutResult(status -> materialize(context));
    }

    public void createAndStartCommand(String batchId) {
        if (batchContextOrNull(batchId) == null) {
            throw new IllegalArgumentException("采样批次不存在：" + batchId);
        }
        BatchDomainReportCommandRow existing = findCommand(batchId);
        if (existing != null && "SUCCEEDED".equals(existing.status())) {
            throw new IllegalStateException("历史成功报表不可重新启动：" + batchId);
        }
        if (existing != null && ("PENDING".equals(existing.status()) || "RUNNING".equals(existing.status()))) {
            throw new IllegalStateException("报表任务正在执行：" + batchId);
        }
        if (existing != null && "FAILED".equals(existing.status())) {
            int retried = jdbc.update("""
                    update ana_batch_domain_report_command
                       set status = 'PENDING', started_time = null, ended_time = null,
                           error_message = null, updated_at = current_timestamp
                     where batch_id = :batchId and status = 'FAILED'
                    """, params(batchId));
            if (retried != 1) {
                throw new IllegalStateException("报表任务状态已变化，拒绝重复提交：" + batchId);
            }
        } else {
            try {
                transactionTemplate.executeWithoutResult(status -> jdbc.update("""
                        insert into ana_batch_domain_report_command(batch_id, status)
                        values (:batchId, 'PENDING')
                        """, params(batchId)));
            } catch (DuplicateKeyException exception) {
                throw new IllegalStateException("报表任务已提交，拒绝重复入队：" + batchId, exception);
            }
        }
        BatchDomainReportTaskLauncher launcher = launcherProvider == null ? null : launcherProvider.getIfAvailable();
        if (launcher != null) {
            launcher.launch(batchId);
        }
    }

    public BatchDomainReportCommandRow findCommand(String batchId) {
        List<BatchDomainReportCommandRow> rows = jdbc.query("""
                select command_id, batch_id, status, started_time, ended_time, error_message, created_time
                from ana_batch_domain_report_command where batch_id = :batchId
                """, params(batchId), (rs, rowNum) -> new BatchDomainReportCommandRow(
                rs.getLong("command_id"), rs.getString("batch_id"), rs.getString("status"),
                localDateTime(rs.getTimestamp("started_time")), localDateTime(rs.getTimestamp("ended_time")),
                rs.getString("error_message"), localDateTime(rs.getTimestamp("created_time"))));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<BatchDomainReportRow> findTransactionStats(String batchId) {
        return jdbc.query("""
                select batch_id, module_name, covered_service_count, sent_transaction_count,
                       comp_result_1_count, comp_result_2_count, comp_result_3_count,
                       comp_result_4_count, comp_result_8_count
                from ana_batch_domain_transaction_stat where batch_id = :batchId order by module_name
                """, params(batchId), (rs, rowNum) -> new BatchDomainReportRow(
                rs.getString("batch_id"), rs.getString("module_name"), rs.getLong("covered_service_count"),
                rs.getLong("sent_transaction_count"), rs.getLong("comp_result_1_count"),
                rs.getLong("comp_result_2_count"), rs.getLong("comp_result_3_count"),
                rs.getLong("comp_result_4_count"), rs.getLong("comp_result_8_count")));
    }

    public List<BatchDomainFieldStatRow> findFieldStats(String batchId) {
        return jdbc.query("""
                select batch_id, module_name, total_field_count, diff_field_count, no_diff_field_count
                from ana_batch_domain_field_stat where batch_id = :batchId order by module_name
                """, params(batchId), (rs, rowNum) -> new BatchDomainFieldStatRow(
                rs.getString("batch_id"), rs.getString("module_name"), rs.getLong("total_field_count"),
                rs.getLong("diff_field_count"), rs.getLong("no_diff_field_count")));
    }

    public List<BatchReportGapRow> findGaps(String batchId) {
        return jdbc.query("""
                select batch_id, gap_type, service_code, message_type, field_key, affected_count
                from ana_batch_report_gap where batch_id = :batchId order by gap_type, service_code, field_key
                """, params(batchId), (rs, rowNum) -> new BatchReportGapRow(
                rs.getString("batch_id"), rs.getString("gap_type"), rs.getString("service_code"),
                rs.getString("message_type"), rs.getString("field_key"), rs.getLong("affected_count")));
    }

    boolean markRunning(String batchId) {
        return jdbc.update("""
                update ana_batch_domain_report_command
                   set status = 'RUNNING', started_time = current_timestamp, ended_time = null,
                       error_message = null, updated_at = current_timestamp
                 where batch_id = :batchId and status = 'PENDING'
                """, params(batchId)) == 1;
    }

    void markSucceeded(String batchId) {
        jdbc.update("""
                update ana_batch_domain_report_command
                   set status = 'SUCCEEDED', ended_time = current_timestamp,
                       error_message = null, updated_at = current_timestamp
                 where batch_id = :batchId and status = 'RUNNING'
                """, params(batchId));
    }

    void markFailed(String batchId, String errorMessage) {
        jdbc.update("""
                update ana_batch_domain_report_command
                   set status = 'FAILED', ended_time = current_timestamp,
                       error_message = :errorMessage, updated_at = current_timestamp
                 where batch_id = :batchId and status = 'RUNNING'
                """, params(batchId).addValue("errorMessage", abbreviate(errorMessage)));
    }

    void markLaunchRejected(String batchId, String errorMessage) {
        jdbc.update("""
                update ana_batch_domain_report_command
                   set status = 'FAILED', ended_time = current_timestamp,
                       error_message = :errorMessage, updated_at = current_timestamp
                 where batch_id = :batchId and status = 'PENDING'
                """, params(batchId).addValue("errorMessage", abbreviate(errorMessage)));
    }

    private void materialize(BatchContext context) {
        MapSqlParameterSource parameters = sourceParams(context);
        jdbc.update("delete from ana_batch_domain_transaction_stat where batch_id = :batchId", parameters);
        jdbc.update("delete from ana_batch_domain_field_stat where batch_id = :batchId", parameters);
        jdbc.update("delete from ana_batch_report_gap where batch_id = :batchId", parameters);
        insertTransactionStats(parameters);
        insertFieldStats(parameters);
        insertUnconfiguredServiceGaps(parameters);
        insertUnmappedFieldGaps(parameters);
    }

    private void insertTransactionStats(MapSqlParameterSource p) {
        jdbc.update("""
                insert into ana_batch_domain_transaction_stat(
                    batch_id, module_name, covered_service_count, sent_transaction_count,
                    comp_result_1_count, comp_result_2_count, comp_result_3_count, comp_result_4_count, comp_result_8_count)
                with response_services as (
                    select split_part(r.txn_code, '&', 1) as service_code, count(*) as sent_count
                    from msg_flow_log_response r
                    where r.response_time >= :startTime and r.response_time < :endTime
                    group by split_part(r.txn_code, '&', 1)
                ), comparison_counts as (
                    select split_part(r.txn_code, '&', 1) as service_code,
                           sum(case when t.comp_result = '1' then 1 else 0 end) as comp1,
                           sum(case when t.comp_result = '2' then 1 else 0 end) as comp2,
                           sum(case when t.comp_result = '3' then 1 else 0 end) as comp3,
                           sum(case when t.comp_result = '4' then 1 else 0 end) as comp4,
                           sum(case when t.comp_result = '8' then 1 else 0 end) as comp8
                    from tss_tran_comp t
                    join msg_flow_log_response r on r.trans_id = t.mesg_seq
                    where t.orig_cdate = :origCdate
                      and r.response_time >= :startTime and r.response_time < :endTime
                    group by split_part(r.txn_code, '&', 1)
                ), catalog_services as (
                    select lower(service_code) as service_code, min(module_name) as module_name
                    from ana_tran_catalog
                    where module_name is not null
                    group by lower(service_code)
                )
                select :batchId, c.module_name, count(distinct rs.service_code), sum(rs.sent_count),
                       coalesce(sum(cc.comp1), 0), coalesce(sum(cc.comp2), 0), coalesce(sum(cc.comp3), 0),
                       coalesce(sum(cc.comp4), 0), coalesce(sum(cc.comp8), 0)
                from response_services rs
                join catalog_services c on c.service_code = lower(rs.service_code)
                left join comparison_counts cc on lower(cc.service_code) = lower(rs.service_code)
                where c.module_name is not null
                group by c.module_name
                """, p);
    }

    private void insertFieldStats(MapSqlParameterSource p) {
        jdbc.update("""
                insert into ana_batch_domain_field_stat(batch_id, module_name, total_field_count, diff_field_count, no_diff_field_count)
                with catalog_services as (
                    select lower(service_code) as service_code, min(module_name) as module_name
                    from ana_tran_catalog where module_name is not null group by lower(service_code)
                ), mapped_fields as (
                    select distinct c.module_name, m.tran_code, m.service_code, m.std_field_name,
                           m.sop_field_name, m.soap_field_name, m.bizjson_field_name
                    from ana_field_mapping m
                    join catalog_services c on c.service_code = lower(m.service_code)
                    where c.module_name is not null
                      and exists (
                          select 1 from msg_flow_log_response r
                          where r.response_time >= :startTime and r.response_time < :endTime
                            and lower(split_part(r.txn_code, '&', 1)) = lower(m.service_code)
                      )
                )
                select :batchId, m.module_name, count(*),
                       sum(case when exists (
                           select 1 from ana_field_diff_result d
                           where d.batch_id = :batchId and d.mapping_status = 'MAPPED'
                             and lower(d.tran_code) = lower(m.tran_code)
                             and lower(d.service_code) = lower(m.service_code)
                             and ((lower(d.message_type) = 'sop' and lower(d.sop_field_name) = lower(m.sop_field_name))
                               or (lower(d.message_type) = 'soap' and lower(d.soap_field_name) = lower(m.soap_field_name))
                               or (lower(d.message_type) = 'bizjson' and lower(d.bizjson_field_name) = lower(m.bizjson_field_name)))
                       ) then 1 else 0 end),
                       count(*) - sum(case when exists (
                           select 1 from ana_field_diff_result d
                           where d.batch_id = :batchId and d.mapping_status = 'MAPPED'
                             and lower(d.tran_code) = lower(m.tran_code)
                             and lower(d.service_code) = lower(m.service_code)
                             and ((lower(d.message_type) = 'sop' and lower(d.sop_field_name) = lower(m.sop_field_name))
                               or (lower(d.message_type) = 'soap' and lower(d.soap_field_name) = lower(m.soap_field_name))
                               or (lower(d.message_type) = 'bizjson' and lower(d.bizjson_field_name) = lower(m.bizjson_field_name)))
                       ) then 1 else 0 end)
                from mapped_fields m
                group by m.module_name
                """, p);
    }

    private void insertUnconfiguredServiceGaps(MapSqlParameterSource p) {
        jdbc.update("""
                insert into ana_batch_report_gap(batch_id, gap_type, service_code, message_type, field_key, affected_count)
                select :batchId, 'UNCONFIGURED_SERVICE', split_part(r.txn_code, '&', 1),
                       nullif(split_part(r.txn_code, '&', 2), ''), null, count(*)
                from msg_flow_log_response r
                left join ana_tran_catalog c on lower(c.service_code) = lower(split_part(r.txn_code, '&', 1))
                where r.response_time >= :startTime and r.response_time < :endTime and c.service_code is null
                group by split_part(r.txn_code, '&', 1), nullif(split_part(r.txn_code, '&', 2), '')
                """, p);
    }

    private void insertUnmappedFieldGaps(MapSqlParameterSource p) {
        jdbc.update("""
                insert into ana_batch_report_gap(batch_id, gap_type, service_code, message_type, field_key, affected_count)
                select :batchId, 'UNMAPPED_FIELD', service_code, message_type,
                       case lower(message_type)
                           when 'soap' then soap_field_name
                           when 'bizjson' then bizjson_field_name
                           else sop_field_name
                       end,
                       sum(affected_tran_count)
                from ana_field_diff_result
                where batch_id = :batchId and mapping_status = 'UNMAPPED'
                group by service_code, message_type,
                         case lower(message_type)
                             when 'soap' then soap_field_name
                             when 'bizjson' then bizjson_field_name
                             else sop_field_name
                         end
                """, p);
    }

    private BatchContext batchContext(String batchId) {
        BatchContext context = batchContextOrNull(batchId);
        if (context == null) {
            throw new IllegalArgumentException("采样批次不存在：" + batchId);
        }
        return context;
    }

    private BatchContext batchContextOrNull(String batchId) {
        List<BatchContext> rows = jdbc.query("select batch_id, orig_cdate, status from ana_sampling_command where batch_id = :batchId",
                params(batchId), (rs, rowNum) -> new BatchContext(rs.getString("batch_id"), rs.getString("orig_cdate"), rs.getString("status")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean hasRetainedResponseDetails(BatchContext context) {
        Long count = jdbc.queryForObject("""
                select count(*) from msg_flow_log_response
                where response_time >= :startTime and response_time < :endTime
                """, sourceParams(context), Long.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource sourceParams(BatchContext context) {
        LocalDate date = LocalDate.parse(context.origCdate(), BUSINESS_DATE);
        return params(context.batchId()).addValue("origCdate", context.origCdate())
                .addValue("startTime", Timestamp.valueOf(date.atStartOfDay()))
                .addValue("endTime", Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
    }

    private MapSqlParameterSource params(String batchId) {
        return new MapSqlParameterSource("batchId", batchId);
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private record BatchContext(String batchId, String origCdate, String status) {
    }
}
