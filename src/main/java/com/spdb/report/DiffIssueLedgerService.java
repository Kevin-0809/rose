package com.spdb.report;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.spdb.web.PageRequestParams;
import com.spdb.web.PagedResult;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class DiffIssueLedgerService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public DiffIssueLedgerService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
    }

    public void materializeBatch(String batchId, LocalDate businessDate) {
        if (batchId == null || batchId.isBlank() || businessDate == null) {
            throw new IllegalArgumentException("batch id and business date are required");
        }
        transactionTemplate.executeWithoutResult(status -> {
            for (BatchIssue issue : batchIssues(batchId)) {
                materialize(issue, batchId, businessDate);
            }
        });
    }

    public DiffIssueRow findById(long issueId) {
        List<DiffIssueRow> rows = jdbc.query("select * from ana_diff_issue where issue_id = :issueId",
                params().addValue("issueId", issueId), (rs, ignored) -> row(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<DiffIssueRow> search(DiffIssueSearch search) {
        return searchPaged(search, PageRequestParams.of(1, 200)).rows();
    }

    public PagedResult<DiffIssueRow> searchPaged(DiffIssueSearch search, PageRequestParams page) {
        DiffIssueSearch criteria = search == null ? new DiffIssueSearch(null, null, null, null, null, null, null, null, null, null) : search;
        PageRequestParams pageParams = page == null ? PageRequestParams.of(null, null) : page;
        SearchSql searchSql = searchSql(criteria);
        MapSqlParameterSource values = searchSql.values()
                .addValue("limit", pageParams.size())
                .addValue("offset", pageParams.offset());
        List<DiffIssueRow> rows = jdbc.query("""
                select * from ana_diff_issue
                """ + searchSql.where() + """
                 order by last_seen_date desc, issue_id desc
                 limit :limit offset :offset
                """, values, (rs, ignored) -> row(rs));
        Long total = jdbc.queryForObject("select count(*) from ana_diff_issue " + searchSql.where(), values, Long.class);
        return PagedResult.of(rows, total == null ? 0 : total, pageParams);
    }

    public void update(long issueId, DiffIssueUpdate update, LocalDateTime expectedUpdatedAt) {
        if (update == null || expectedUpdatedAt == null) {
            throw new IllegalArgumentException("update and expected updated time are required");
        }
        if ("RESOLVED".equals(update.issueStatus()) && update.resolutionDate() == null) {
            throw new IllegalArgumentException("resolution date is required when resolved");
        }
        int affected = jdbc.update("""
                update ana_diff_issue set problem_type = :problemType, preliminary_analysis = :preliminaryAnalysis,
                    final_solution = :finalSolution, issue_status = :issueStatus,
                    coordination_required = :coordinationRequired, resolver = :resolver,
                    resolution_date = :resolutionDate, defect_fix_date = :defectFixDate,
                    updated_at = current_timestamp
                 where issue_id = :issueId and updated_at = :expectedUpdatedAt
                """, params().addValue("issueId", issueId).addValue("problemType", update.problemType())
                .addValue("preliminaryAnalysis", update.preliminaryAnalysis()).addValue("finalSolution", update.finalSolution())
                .addValue("issueStatus", update.issueStatus()).addValue("coordinationRequired", update.coordinationRequired())
                .addValue("resolver", update.resolver()).addValue("resolutionDate", update.resolutionDate())
                .addValue("defectFixDate", update.defectFixDate()).addValue("expectedUpdatedAt", Timestamp.valueOf(expectedUpdatedAt)));
        if (affected == 0) {
            if (findById(issueId) == null) {
                throw new IllegalArgumentException("issue not found: " + issueId);
            }
            throw new OptimisticLockingFailureException("issue was modified by another user");
        }
    }

    private void materialize(BatchIssue candidate, String batchId, LocalDate businessDate) {
        DiffIssueRow existing = findByKey(candidate.issueKey());
        if (existing == null) {
            jdbc.update("""
                    insert into ana_diff_issue(issue_key, issue_level, service_code, tran_code, tran_name, module_name,
                        transaction_owner, orig_error_code, dest_error_code, normalized_source_field_name,
                        problem_description, issue_status, first_seen_date, last_seen_date, first_seen_batch_id,
                        last_seen_batch_id, occurrence_batch_count)
                    values (:issueKey, :issueLevel, :serviceCode, :tranCode, :tranName, :moduleName,
                        :transactionOwner, :origErrorCode, :destErrorCode, :normalizedSourceFieldName,
                        :problemDescription, 'OPEN', :businessDate, :businessDate, :batchId, :batchId, 1)
                    """, candidate.params().addValue("businessDate", businessDate).addValue("batchId", batchId));
            existing = findByKey(candidate.issueKey());
            writeSnapshots(candidate.issueKey(), batchId, existing, 0, null);
            return;
        }
        boolean newBatch = !batchId.equals(existing.lastSeenBatchId());
        long historicalCount = newBatch ? existing.occurrenceBatchCount() : Math.max(0, existing.occurrenceBatchCount() - 1);
        LocalDate previousSeenDate = newBatch ? existing.lastSeenDate() : null;
        jdbc.update("""
                update ana_diff_issue set service_code = :serviceCode, tran_code = :tranCode, tran_name = :tranName,
                    module_name = :moduleName, transaction_owner = :transactionOwner, orig_error_code = :origErrorCode,
                    dest_error_code = :destErrorCode, normalized_source_field_name = :normalizedSourceFieldName,
                    problem_description = :problemDescription, issue_status = case when issue_status = 'RESOLVED' then 'OPEN' else issue_status end,
                    last_seen_date = :businessDate, last_seen_batch_id = :batchId,
                    occurrence_batch_count = occurrence_batch_count + :increment, updated_at = current_timestamp
                 where issue_id = :issueId
                """, candidate.params().addValue("businessDate", businessDate).addValue("batchId", batchId)
                .addValue("increment", newBatch ? 1 : 0).addValue("issueId", existing.issueId()));
        DiffIssueRow updated = findById(existing.issueId());
        writeSnapshots(candidate.issueKey(), batchId, updated, historicalCount, previousSeenDate);
    }

    private void writeSnapshots(String issueKey, String batchId, DiffIssueRow issue, long historicalCount, LocalDate previousSeenDate) {
        MapSqlParameterSource values = params().addValue("issueId", issue.issueId()).addValue("issueKey", issueKey)
                .addValue("batchId", batchId).addValue("historicalCount", historicalCount).addValue("firstSeenDate", issue.firstSeenDate())
                .addValue("previousSeenDate", previousSeenDate).addValue("problemType", issue.problemType())
                .addValue("preliminaryAnalysis", issue.preliminaryAnalysis()).addValue("finalSolution", issue.finalSolution())
                .addValue("coordinationRequired", issue.coordinationRequired()).addValue("resolver", issue.resolver())
                .addValue("resolutionDate", compactDate(issue.resolutionDate())).addValue("defectFixDate", compactDate(issue.defectFixDate()));
        String set = "issue_id=:issueId, historical_occurrence_count=:historicalCount, first_seen_date=:firstSeenDate, previous_seen_date=:previousSeenDate, problem_type=:problemType, preliminary_analysis=:preliminaryAnalysis, final_solution=:finalSolution, coordination_required=:coordinationRequired, resolver=:resolver, resolution_date=:resolutionDate, defect_fix_date=:defectFixDate";
        jdbc.update("update ana_tran_diff_tracking_export set " + set + " where source_batch_id=:batchId and issue_key=:issueKey", values);
        jdbc.update("update ana_field_diff_tracking_export set " + set + " where source_batch_id=:batchId and issue_key=:issueKey", values);
    }

    private List<BatchIssue> batchIssues(String batchId) {
        return jdbc.query("""
                select issue_key, 'TRANSACTION' issue_level, service_code, tran_code, tran_name, module_name, transaction_owner,
                       orig_error_code, dest_error_code, cast(null as varchar) normalized_source_field_name, problem_description
                  from ana_tran_diff_tracking_export where source_batch_id = :batchId and issue_key is not null
                 union
                select issue_key, 'FIELD' issue_level, service_code, tran_code, tran_name, module_name, transaction_owner,
                       cast(null as varchar) orig_error_code, cast(null as varchar) dest_error_code, field_name normalized_source_field_name, problem_description
                  from ana_field_diff_tracking_export where source_batch_id = :batchId and issue_key is not null
                """, params().addValue("batchId", batchId), (rs, ignored) -> new BatchIssue(rs.getString("issue_key"), rs.getString("issue_level"),
                rs.getString("service_code"), rs.getString("tran_code"), rs.getString("tran_name"), rs.getString("module_name"),
                rs.getString("transaction_owner"), rs.getString("orig_error_code"), rs.getString("dest_error_code"),
                normalizedSourceFieldName(rs.getString("issue_level"), rs.getString("issue_key"), rs.getString("normalized_source_field_name")),
                rs.getString("problem_description")));
    }

    private DiffIssueRow findByKey(String issueKey) {
        List<DiffIssueRow> rows = jdbc.query("select * from ana_diff_issue where issue_key = :issueKey", params().addValue("issueKey", issueKey),
                (rs, ignored) -> row(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private DiffIssueRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DiffIssueRow(rs.getLong("issue_id"), rs.getString("issue_key"), rs.getString("issue_level"), rs.getString("service_code"),
                rs.getString("tran_code"), rs.getString("tran_name"), rs.getString("module_name"), rs.getString("transaction_owner"),
                rs.getString("orig_error_code"), rs.getString("dest_error_code"), rs.getString("normalized_source_field_name"),
                rs.getString("problem_type"), rs.getString("problem_description"), rs.getString("preliminary_analysis"), rs.getString("final_solution"),
                rs.getString("issue_status"), rs.getString("coordination_required"), rs.getString("resolver"), rs.getObject("resolution_date", LocalDate.class),
                rs.getObject("defect_fix_date", LocalDate.class), rs.getObject("first_seen_date", LocalDate.class), rs.getObject("last_seen_date", LocalDate.class),
                rs.getString("first_seen_batch_id"), rs.getString("last_seen_batch_id"), rs.getLong("occurrence_batch_count"),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private static MapSqlParameterSource params() { return new MapSqlParameterSource(); }
    private static SearchSql searchSql(DiffIssueSearch criteria) {
        List<String> conditions = new ArrayList<>();
        MapSqlParameterSource values = params();
        addEquals(conditions, values, "issue_level", "issueLevel", clean(criteria.issueLevel()));
        addEquals(conditions, values, "issue_status", "issueStatus", clean(criteria.issueStatus()));
        addEquals(conditions, values, "service_code", "serviceCode", clean(criteria.serviceCode()));
        addEquals(conditions, values, "module_name", "moduleName", clean(criteria.moduleName()));
        addEquals(conditions, values, "transaction_owner", "transactionOwner", clean(criteria.transactionOwner()));
        addDateFrom(conditions, values, "first_seen_date", "firstSeenFrom", criteria.firstSeenFrom());
        addDateTo(conditions, values, "first_seen_date", "firstSeenTo", criteria.firstSeenTo());
        addDateFrom(conditions, values, "last_seen_date", "lastSeenFrom", criteria.lastSeenFrom());
        addDateTo(conditions, values, "last_seen_date", "lastSeenTo", criteria.lastSeenTo());
        String keyword = clean(criteria.keyword());
        if (keyword != null) {
            conditions.add("(lower(issue_key) like lower(:keyword) or lower(problem_description) like lower(:keyword))");
            values.addValue("keyword", "%" + keyword + "%");
        }
        String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions) + "\n";
        return new SearchSql(where, values);
    }
    private static void addEquals(List<String> conditions, MapSqlParameterSource values, String column, String param, String value) {
        if (value != null) {
            conditions.add(column + " = :" + param);
            values.addValue(param, value);
        }
    }
    private static void addDateFrom(List<String> conditions, MapSqlParameterSource values, String column, String param, LocalDate value) {
        if (value != null) {
            conditions.add(column + " >= :" + param);
            values.addValue(param, value);
        }
    }
    private static void addDateTo(List<String> conditions, MapSqlParameterSource values, String column, String param, LocalDate value) {
        if (value != null) {
            conditions.add(column + " <= :" + param);
            values.addValue(param, value);
        }
    }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String compactDate(LocalDate date) { return date == null ? null : DateTimeFormatter.BASIC_ISO_DATE.format(date); }
    private static String normalizedSourceFieldName(String issueLevel, String issueKey, String fallback) {
        if (!"FIELD".equals(issueLevel) || issueKey == null) {
            return fallback;
        }
        int lastSeparator = issueKey.lastIndexOf('|');
        return lastSeparator < 0 ? fallback : issueKey.substring(lastSeparator + 1);
    }

    private record BatchIssue(String issueKey, String issueLevel, String serviceCode, String tranCode, String tranName,
                              String moduleName, String transactionOwner, String origErrorCode, String destErrorCode,
                              String normalizedSourceFieldName, String problemDescription) {
        MapSqlParameterSource params() {
            return DiffIssueLedgerService.params().addValue("issueKey", issueKey).addValue("issueLevel", issueLevel)
                    .addValue("serviceCode", serviceCode).addValue("tranCode", tranCode).addValue("tranName", tranName)
                    .addValue("moduleName", moduleName).addValue("transactionOwner", transactionOwner)
                    .addValue("origErrorCode", origErrorCode).addValue("destErrorCode", destErrorCode)
                    .addValue("normalizedSourceFieldName", normalizedSourceFieldName).addValue("problemDescription", problemDescription);
        }
    }

    private record SearchSql(String where, MapSqlParameterSource values) {
    }
}
