package com.spdb.sample;

import com.spdb.web.PageRequestParams;
import com.spdb.web.PagedResult;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class SampleQueryService {
    static final int MAX_EXPORT_ROWS = 1_000_000;

    private final NamedParameterJdbcTemplate jdbc;

    public SampleQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PagedResult<SampleGroupRow> groups(SampleSearchCriteria criteria, PageRequestParams page) {
        QueryParts query = groupWhere(criteria);
        List<SampleGroupRow> rows = groupRows(query, " order by affected_count desc, group_id desc limit :limit offset :offset", page);
        long total = count("ana_sample_group", query);
        return PagedResult.of(rows, total, page);
    }

    public List<SampleGroupRow> exportGroups(SampleSearchCriteria criteria) {
        QueryParts query = groupWhere(criteria);
        query.params.addValue("exportLimit", MAX_EXPORT_ROWS);
        return groupRows(query, " order by affected_count desc, group_id desc limit :exportLimit", null);
    }

    public void streamGroups(SampleSearchCriteria criteria, SampleGroupConsumer consumer) {
        QueryParts query = groupWhere(criteria);
        query.params.addValue("exportLimit", MAX_EXPORT_ROWS);
        RowCallbackHandler handler = rs -> consumer.accept(mapGroupRow(rs));
        jdbc.query(groupSelect() + query.where + " order by affected_count desc, group_id desc limit :exportLimit",
                query.params, handler);
    }

    public PagedResult<SampleDetailRow> details(SampleSearchCriteria criteria, PageRequestParams page) {
        QueryParts query = detailWhere(criteria);
        List<SampleDetailRow> rows = detailRows(query, " order by sample_id desc limit :limit offset :offset", page);
        long total = count("ana_sample_detail d", query);
        return PagedResult.of(rows, total, page);
    }

    public PagedResult<SampleDetailRow> transactionDiffs(SampleSearchCriteria criteria, PageRequestParams page) {
        QueryParts query = transactionResultWhere(criteria);
        query.params.addValue("limit", page.size()).addValue("offset", page.offset());
        List<SampleDetailRow> rows = jdbc.query(transactionResultSelect() + query.where + """
                 order by r.affected_tran_count desc, r.result_id desc
                 limit :limit offset :offset
                """, query.params, (rs, i) -> mapTransactionDiffRow(rs));
        long total = count("ana_tran_diff_result r", query);
        return PagedResult.of(rows, total, page);
    }

    public PagedResult<SampleFieldDiffRow> fieldDiffs(SampleSearchCriteria criteria, PageRequestParams page) {
        QueryParts query = fieldResultWhere(criteria);
        query.params.addValue("limit", page.size()).addValue("offset", page.offset());
        List<SampleFieldDiffRow> rows = jdbc.query(fieldResultSelect() + query.where + """
                 order by r.affected_tran_count desc, r.tran_code, r.service_code,
                          coalesce(r.field_cn_name, ''), r.sample_tran_seq_no
                 limit :limit offset :offset
                """, query.params, (rs, i) -> mapFieldDiffRow(rs));
        long total = count("ana_field_diff_result r", query);
        return PagedResult.of(rows, total, page);
    }

    public List<SampleDetailRow> exportDetails(SampleSearchCriteria criteria) {
        QueryParts query = detailWhere(criteria);
        query.params.addValue("exportLimit", MAX_EXPORT_ROWS);
        return detailRows(query, " order by sample_id desc limit :exportLimit", null);
    }

    public void streamDetails(SampleSearchCriteria criteria, SampleDetailConsumer consumer) {
        QueryParts query = detailWhere(criteria);
        query.params.addValue("exportLimit", MAX_EXPORT_ROWS);
        RowCallbackHandler handler = rs -> consumer.accept(mapDetailRow(rs));
        jdbc.query(detailSelect() + query.where + " order by sample_id desc limit :exportLimit",
                query.params, handler);
    }

    public void streamTransactionDiffExport(SampleSearchCriteria criteria, SampleDetailConsumer consumer) {
        QueryParts query = transactionResultWhere(criteria);
        query.params.addValue("exportLimit", MAX_EXPORT_ROWS);
        RowCallbackHandler handler = rs -> consumer.accept(mapTransactionDiffRow(rs));
        jdbc.query(transactionResultSelect() + query.where + """
                 order by r.affected_tran_count desc, r.result_id desc
                 limit :exportLimit
                """, query.params, handler);
    }

    public void streamTransactionSuccessStats(SampleSearchCriteria criteria, TransactionSuccessStatConsumer consumer) {
        SuccessQueryParts query = transactionSuccessWhere(criteria);
        RowCallbackHandler handler = rs -> consumer.accept(mapTransactionSuccessStatRow(rs));
        jdbc.query(transactionSuccessStatSelect(query.tranWhere(), query.detailWhere(), query.mappingWhere(), query.catalogWhere()),
                query.params(), handler);
    }

    public PagedResult<SampleDetailFieldRow> detailFields(Long sampleId, PageRequestParams page) {
        QueryParts query = detailFieldWhere(new SampleSearchCriteria(null, null, null, null, null, null, null, null, null, null, null), sampleId);
        query.params.addValue("limit", page.size()).addValue("offset", page.offset());
        List<SampleDetailFieldRow> rows = jdbc.query(detailFieldSelect() + query.where + " order by field_index, field_detail_id limit :limit offset :offset",
                query.params, (rs, i) -> mapDetailFieldRow(rs));
        long total = count("ana_sample_detail_field f", query);
        return PagedResult.of(rows, total, page);
    }

    public List<SampleDetailFieldRow> exportDetailFields(SampleSearchCriteria criteria) {
        QueryParts query = detailFieldWhere(criteria, null);
        query.params.addValue("exportLimit", MAX_EXPORT_ROWS);
        return detailFieldRows(query, " order by sample_id desc, field_index, field_detail_id limit :exportLimit", null);
    }

    public void streamDetailFields(SampleSearchCriteria criteria, SampleDetailFieldConsumer consumer) {
        QueryParts query = detailFieldWhere(criteria, null);
        query.params.addValue("exportLimit", MAX_EXPORT_ROWS);
        RowCallbackHandler handler = rs -> consumer.accept(mapDetailFieldRow(rs));
        jdbc.query(detailFieldSelect() + query.where + " order by sample_id desc, field_index, field_detail_id limit :exportLimit",
                query.params, handler);
    }

    public void streamFieldDiffExport(SampleSearchCriteria criteria, SampleFieldDiffExportConsumer consumer) {
        QueryParts query = fieldResultWhere(criteria);
        query.params.addValue("exportLimit", MAX_EXPORT_ROWS);
        RowCallbackHandler handler = rs -> consumer.accept(mapFieldDiffRow(rs));
        jdbc.query(fieldResultSelect() + query.where + """
                 order by r.affected_tran_count desc, r.tran_code, r.service_code,
                          coalesce(r.field_cn_name, ''), r.sample_tran_seq_no
                 limit :exportLimit
                """, query.params, handler);
    }

    public void streamServiceReport(SamplingSummarySearchCriteria criteria, SamplingServiceReportConsumer consumer) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> tranClauses = new ArrayList<>();
        List<String> detailClauses = new ArrayList<>();
        if (criteria != null) {
            String effectiveOrigCdate = criteria.origCdate();
            if (!StringUtils.hasText(effectiveOrigCdate) && StringUtils.hasText(criteria.batchId())) {
                effectiveOrigCdate = origCdateForBatch(criteria.batchId().trim());
            }
            if (StringUtils.hasText(effectiveOrigCdate)) {
                tranClauses.add("t.orig_cdate = :origCdate");
                detailClauses.add("d.orig_cdate = :origCdate");
                params.addValue("origCdate", effectiveOrigCdate.trim());
            }
            if (StringUtils.hasText(criteria.batchId())) {
                detailClauses.add("d.batch_id = :batchId");
                params.addValue("batchId", criteria.batchId().trim());
            }
        }
        String tranWhere = tranClauses.isEmpty() ? "" : " where " + String.join(" and ", tranClauses);
        String detailWhere = detailClauses.isEmpty() ? "" : " where " + String.join(" and ", detailClauses);
        RowCallbackHandler handler = rs -> consumer.accept(mapServiceReportRow(rs));
        jdbc.query(serviceReportSelect(tranWhere, detailWhere), params, handler);
    }

    private String origCdateForBatch(String batchId) {
        List<String> rows = jdbc.query("""
                select orig_cdate
                from ana_sampling_summary
                where batch_id = :batchId
                limit 1
                """, new MapSqlParameterSource().addValue("batchId", batchId), (rs, i) -> rs.getString("orig_cdate"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<SampleGroupRow> groupRows(QueryParts query, String order, PageRequestParams page) {
        if (page != null) {
            query.params.addValue("limit", page.size()).addValue("offset", page.offset());
        }
        return jdbc.query(groupSelect() + query.where + order, query.params, (rs, i) -> mapGroupRow(rs));
    }

    private List<SampleDetailRow> detailRows(QueryParts query, String order, PageRequestParams page) {
        if (page != null) {
            query.params.addValue("limit", page.size()).addValue("offset", page.offset());
        }
        return jdbc.query(detailSelect() + query.where + order, query.params, (rs, i) -> mapDetailRow(rs));
    }

    private List<SampleDetailFieldRow> detailFieldRows(QueryParts query, String order, PageRequestParams page) {
        if (page != null) {
            query.params.addValue("limit", page.size()).addValue("offset", page.offset());
        }
        return jdbc.query(detailFieldSelect() + query.where + order, query.params, (rs, i) -> mapDetailFieldRow(rs));
    }

    private String groupSelect() {
        return """
                select group_id, batch_id, orig_cdate, sample_type, config_status, mapping_status,
                       semantic_signature, semantic_signature_hash, semantic_field_names, message_types,
                       dest_trcd, service_code, message_type, tran_code, comp_result,
                       owner, affected_count, affected_tran_count, affected_field_count, sample_count, reason
                from ana_sample_group
                """;
    }

    private String detailSelect() {
        return """
                select
                       d.sample_id,
                       d.group_id,
                       d.batch_id,
                       d.orig_cdate,
                       d.sample_type,
                       d.sample_seq_no,
                       d.config_status,
                       d.dest_trcd,
                       d.service_code,
                       d.message_type,
                       d.tran_code,
                       d.comp_result,
                       d.sop_field_name,
                       d.soap_field_name,
                       d.bizjson_field_name,
                       d.field_cn_name,
                       d.tran_seq_no,
                       d.owner,
                       d.affected_count,
                       d.field_count,
                       d.orig_error_code,
                       d.orig_error_desc,
                       d.dest_error_code,
                       d.dest_error_desc,
                       d.reason,
                       d.source_table,
                       d.source_pk
                from ana_sample_detail d
                """;
    }

    private String transactionResultSelect() {
        return """
                select
                       r.result_id as sample_id,
                       cast(0 as bigint) as group_id,
                       r.batch_id,
                       r.orig_cdate,
                       'RETURN_CODE' as sample_type,
                       1 as sample_seq_no,
                       'CONFIGURED' as config_status,
                       case
                           when length(trim(coalesce(r.message_type, ''))) > 0 then r.service_code || '&' || r.message_type
                           else r.service_code
                       end as dest_trcd,
                       r.service_code,
                       r.message_type,
                       r.tran_code,
                       '8' as comp_result,
                       cast(null as varchar(200)) as sop_field_name,
                       cast(null as varchar(200)) as soap_field_name,
                       cast(null as varchar(200)) as bizjson_field_name,
                       cast(null as varchar(200)) as field_cn_name,
                       r.sample_tran_seq_no as tran_seq_no,
                       r.owner,
                       r.affected_tran_count as affected_count,
                       0 as field_count,
                       r.orig_error_code,
                       r.orig_error_desc,
                       r.dest_error_code,
                       r.dest_error_desc,
                       cast(null as varchar(1000)) as reason,
                       'tss_retcode_comp' as source_table,
                       r.sample_tran_seq_no as source_pk
                from ana_tran_diff_result r
                """;
    }

    private String fieldResultSelect() {
        return """
                select
                       r.orig_cdate,
                       r.batch_id,
                       r.tran_code,
                       r.service_code,
                       r.message_type,
                       r.sop_field_name,
                       r.soap_field_name,
                       r.bizjson_field_name,
                       r.field_cn_name,
                       r.mapping_status,
                       r.sample_tran_seq_no,
                       r.orig_field_value,
                       r.dest_field_value,
                       r.owner,
                       r.affected_tran_count
                from ana_field_diff_result r
                """;
    }

    private String detailFieldSelect() {
        return """
                select
                       f.field_detail_id,
                       f.sample_id,
                       f.group_id,
                       f.batch_id,
                       f.mesg_seq,
                       f.message_type,
                       f.raw_field_name,
                       f.std_field_name,
                       f.field_cn_name,
                       f.orig_field_value,
                       f.dest_field_value,
                       f.mapping_status,
                       f.field_index
                from ana_sample_detail_field f
                """;
    }

    private SampleGroupRow mapGroupRow(ResultSet rs) throws SQLException {
        return new SampleGroupRow(
                rs.getLong("group_id"),
                rs.getString("batch_id"),
                rs.getString("orig_cdate"),
                rs.getString("sample_type"),
                rs.getString("config_status"),
                rs.getString("mapping_status"),
                rs.getString("semantic_signature"),
                rs.getString("semantic_signature_hash"),
                rs.getString("semantic_field_names"),
                rs.getString("message_types"),
                rs.getString("dest_trcd"),
                rs.getString("service_code"),
                rs.getString("message_type"),
                rs.getString("tran_code"),
                rs.getString("comp_result"),
                rs.getString("owner"),
                rs.getLong("affected_count"),
                rs.getLong("affected_tran_count"),
                rs.getLong("affected_field_count"),
                rs.getInt("sample_count"),
                rs.getString("reason")
        );
    }

    private SampleDetailRow mapDetailRow(ResultSet rs) throws SQLException {
        return new SampleDetailRow(
                rs.getLong("sample_id"),
                rs.getLong("group_id"),
                rs.getString("batch_id"),
                rs.getString("orig_cdate"),
                rs.getString("sample_type"),
                rs.getInt("sample_seq_no"),
                rs.getString("config_status"),
                rs.getString("dest_trcd"),
                rs.getString("service_code"),
                rs.getString("message_type"),
                rs.getString("tran_code"),
                rs.getString("comp_result"),
                rs.getString("sop_field_name"),
                rs.getString("soap_field_name"),
                rs.getString("bizjson_field_name"),
                rs.getString("field_cn_name"),
                rs.getString("tran_seq_no"),
                rs.getString("owner"),
                rs.getLong("affected_count"),
                rs.getInt("field_count"),
                rs.getString("orig_error_code"),
                rs.getString("orig_error_desc"),
                rs.getString("dest_error_code"),
                rs.getString("dest_error_desc"),
                rs.getString("reason"),
                rs.getString("source_table"),
                rs.getString("source_pk")
        );
    }

    private SampleDetailRow mapTransactionDiffRow(ResultSet rs) throws SQLException {
        return mapDetailRow(rs);
    }

    private SampleDetailFieldRow mapDetailFieldRow(ResultSet rs) throws SQLException {
        return new SampleDetailFieldRow(
                rs.getLong("field_detail_id"),
                rs.getLong("sample_id"),
                rs.getLong("group_id"),
                rs.getString("batch_id"),
                rs.getString("mesg_seq"),
                rs.getString("message_type"),
                rs.getString("raw_field_name"),
                rs.getString("std_field_name"),
                rs.getString("field_cn_name"),
                rs.getString("orig_field_value"),
                rs.getString("dest_field_value"),
                rs.getString("mapping_status"),
                rs.getInt("field_index")
        );
    }

    private SampleFieldDiffRow mapFieldDiffRow(ResultSet rs) throws SQLException {
        return new SampleFieldDiffRow(
                rs.getString("orig_cdate"),
                rs.getString("batch_id"),
                rs.getString("tran_code"),
                rs.getString("service_code"),
                rs.getString("message_type"),
                rs.getString("sop_field_name"),
                rs.getString("soap_field_name"),
                rs.getString("bizjson_field_name"),
                rs.getString("field_cn_name"),
                rs.getString("mapping_status"),
                rs.getString("sample_tran_seq_no"),
                rs.getString("orig_field_value"),
                rs.getString("dest_field_value"),
                rs.getString("owner"),
                rs.getLong("affected_tran_count")
        );
    }

    private SamplingServiceReportRow mapServiceReportRow(ResultSet rs) throws SQLException {
        return new SamplingServiceReportRow(
                rs.getString("batch_id"),
                rs.getString("orig_cdate"),
                rs.getString("tran_code"),
                rs.getString("service_code"),
                rs.getString("tran_name"),
                rs.getString("owner"),
                rs.getLong("total_tran_count"),
                rs.getLong("comp_result_1_count"),
                rs.getLong("comp_result_2_count"),
                rs.getLong("comp_result_3_count"),
                rs.getLong("comp_result_4_count"),
                rs.getLong("comp_result_8_count"),
                rs.getLong("pass_tran_count"),
                rs.getLong("tran_issue_count"),
                rs.getLong("return_code_issue_count"),
                rs.getLong("field_diff_tran_count"),
                rs.getLong("fully_matched_count"),
                rs.getLong("issue_field_count")
        );
    }

    private TransactionSuccessStatRow mapTransactionSuccessStatRow(ResultSet rs) throws SQLException {
        return new TransactionSuccessStatRow(
                rs.getString("orig_cdate"),
                rs.getString("batch_id"),
                rs.getString("tran_code"),
                rs.getString("service_code"),
                rs.getString("message_type"),
                rs.getLong("success_count"),
                rs.getLong("interface_field_count"),
                rs.getLong("compared_field_count"),
                rs.getLong("diff_field_count"),
                rs.getLong("compared_field_diff_count"),
                rs.getLong("high_ratio_field_count"),
                rs.getLong("low_ratio_field_count"),
                rs.getString("module_name"),
                rs.getString("owner")
        );
    }

    public SummaryStats summary() {
        List<SummaryStats> rows = jdbc.query("""
                select batch_id, orig_cdate, total_tran_count,
                       comp_result_1_count, comp_result_2_count, comp_result_3_count,
                       comp_result_4_count, comp_result_8_count,
                       pass_tran_count, tran_issue_count, return_code_issue_count, issue_field_count,
                       field_diff_tran_count, unconfigured_service_count, unmapped_field_count,
                       fully_matched_count, sample_group_count, sample_detail_count
                from ana_sampling_summary
                order by created_at desc
                limit 1
                """, new MapSqlParameterSource(), (rs, i) -> new SummaryStats(
                rs.getString("batch_id"),
                rs.getString("orig_cdate"),
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
                rs.getLong("sample_group_count"),
                rs.getLong("sample_detail_count")
        ));
        return rows.isEmpty() ? new SummaryStats(null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) : rows.get(0);
    }

    public PagedResult<SamplingSummaryHistoryRow> summaryHistory(SamplingSummarySearchCriteria criteria, PageRequestParams page) {
        QueryParts query = summaryWhere(criteria);
        query.params.addValue("limit", page.size()).addValue("offset", page.offset());
        List<SamplingSummaryHistoryRow> rows = jdbc.query("""
                select batch_id, orig_cdate, total_tran_count,
                       comp_result_1_count, comp_result_2_count, comp_result_3_count,
                       comp_result_4_count, comp_result_8_count,
                       pass_tran_count, tran_issue_count, return_code_issue_count, issue_field_count,
                       field_diff_tran_count, unconfigured_service_count, unmapped_field_count,
                       fully_matched_count, sample_group_count, sample_detail_count,
                       created_at
                from ana_sampling_summary
                """ + query.where + " order by created_at desc limit :limit offset :offset",
                query.params, (rs, i) -> new SamplingSummaryHistoryRow(
                        rs.getString("batch_id"),
                        rs.getString("orig_cdate"),
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
                        rs.getLong("sample_group_count"),
                        rs.getLong("sample_detail_count"),
                        rs.getObject("created_at", java.time.LocalDateTime.class)
                ));
        long total = count("ana_sampling_summary", query);
        return PagedResult.of(rows, total, page);
    }

    public List<SummaryChartPoint> summaryChart(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        List<SummaryChartPoint> rows = jdbc.query("""
                select batch_id, orig_cdate, tran_issue_count, return_code_issue_count,
                       issue_field_count, field_diff_tran_count, fully_matched_count,
                       sample_group_count, sample_detail_count
                from ana_sampling_summary
                order by created_at desc
                limit :limit
                """, new MapSqlParameterSource().addValue("limit", safeLimit), (rs, i) -> new SummaryChartPoint(
                rs.getString("batch_id"),
                rs.getString("orig_cdate"),
                rs.getLong("tran_issue_count"),
                rs.getLong("return_code_issue_count"),
                rs.getLong("issue_field_count"),
                rs.getLong("field_diff_tran_count"),
                rs.getLong("fully_matched_count"),
                rs.getLong("sample_group_count"),
                rs.getLong("sample_detail_count")
        ));
        Collections.reverse(rows);
        return rows;
    }

    private String serviceReportSelect(String tranWhere, String detailWhere) {
        return """
                select
                    coalesce(ds.batch_id, '') as batch_id,
                    ts.orig_cdate,
                    coalesce(ds.tran_code, c.tran_code) as tran_code,
                    ts.service_code,
                    c.tran_name,
                    coalesce(ds.owner, c.owner) as owner,
                    ts.total_tran_count,
                    ts.comp_result_1_count,
                    ts.comp_result_2_count,
                    ts.comp_result_3_count,
                    ts.comp_result_4_count,
                    ts.comp_result_8_count,
                    ts.pass_tran_count,
                    ts.tran_issue_count,
                    coalesce(ds.return_code_issue_count, 0) as return_code_issue_count,
                    coalesce(ds.field_diff_tran_count, 0) as field_diff_tran_count,
                    ts.comp_result_4_count - coalesce(ds.field_diff_tran_count, 0) as fully_matched_count,
                    coalesce(ds.issue_field_count, 0) as issue_field_count
                from (
                    select
                        orig_cdate,
                        service_code,
                        count(*) as total_tran_count,
                        sum(case when comp_result = '1' then 1 else 0 end) as comp_result_1_count,
                        sum(case when comp_result = '2' then 1 else 0 end) as comp_result_2_count,
                        sum(case when comp_result = '3' then 1 else 0 end) as comp_result_3_count,
                        sum(case when comp_result = '4' then 1 else 0 end) as comp_result_4_count,
                        sum(case when comp_result = '8' then 1 else 0 end) as comp_result_8_count,
                        sum(case when comp_result = '4' then 1 else 0 end) as pass_tran_count,
                        sum(case when comp_result in ('1', '2') then 1 else 0 end) as tran_issue_count
                    from (
                        select
                            t.orig_cdate,
                            case
                                when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, 1, instr(t.dest_trcd, '&') - 1)
                                else t.dest_trcd
                            end as service_code,
                            t.comp_result
                        from tss_tran_comp t
                """ + tranWhere + """
                    ) tran_base
                    group by orig_cdate, service_code
                ) ts
                left join (
                    select
                        max(batch_id) as batch_id,
                        orig_cdate,
                        service_code,
                        min(tran_code) as tran_code,
                        min(owner) as owner,
                        sum(return_code_issue_count) as return_code_issue_count,
                        sum(field_diff_tran_count) as field_diff_tran_count,
                        sum(issue_field_count) as issue_field_count
                    from (
                        select
                            d.batch_id,
                            d.orig_cdate,
                            d.service_code,
                            d.tran_code,
                            d.owner,
                            coalesce(sum(d.affected_tran_count), 0) as return_code_issue_count,
                            0 as field_diff_tran_count,
                            0 as issue_field_count
                        from ana_tran_diff_result d
                """ + detailWhere + """
                        group by d.batch_id, d.orig_cdate, d.service_code, d.tran_code, d.owner
                        union all
                        select
                            d.batch_id,
                            d.orig_cdate,
                            d.service_code,
                            d.tran_code,
                            d.owner,
                            0 as return_code_issue_count,
                            count(distinct d.sample_tran_seq_no) as field_diff_tran_count,
                            count(*) as issue_field_count
                        from ana_field_diff_result d
                """ + detailWhere + """
                        group by d.batch_id, d.orig_cdate, d.service_code, d.tran_code, d.owner
                    ) result_detail
                    group by orig_cdate, service_code
                ) ds on ds.orig_cdate = ts.orig_cdate and ds.service_code = ts.service_code
                left join ana_tran_catalog c on c.service_code = ts.service_code
                order by ts.total_tran_count desc, ts.service_code
                """;
    }

    private String transactionSuccessStatSelect(String tranWhere, String detailWhere, String mappingWhere, String catalogWhere) {
        String normalized = "regexp_replace(%s, '\\\\[[0-9]+\\\\]', '', 'g')";
        String normMappingStd = String.format(normalized, "std_field_name");
        String normMappedStd = String.format(normalized, "m.std_field_name");
        String normDest = String.format(normalized, "f.dest_field_name");
        String normOrig = String.format(normalized, "f.orig_field_name");
        return """
                select
                    s.orig_cdate,
                    coalesce(ds.batch_id, '') as batch_id,
                    s.tran_code,
                    s.service_code,
                    s.message_type,
                    s.success_count,
                    coalesce(m.interface_field_count, 0) as interface_field_count,
                    s.success_count * coalesce(m.interface_field_count, 0) as compared_field_count,
                    coalesce(ds.diff_field_count, 0) as diff_field_count,
                    coalesce(ds.compared_field_diff_count, 0) as compared_field_diff_count,
                    coalesce(ds.high_ratio_field_count, 0) as high_ratio_field_count,
                    coalesce(ds.low_ratio_field_count, 0) as low_ratio_field_count,
                    coalesce(c.module_name, '') as module_name,
                    coalesce(c.owner, '') as owner
                from (
                    select
                        t.orig_cdate,
                        coalesce(c.tran_code, '') as tran_code,
                        case
                            when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, 1, instr(t.dest_trcd, '&') - 1)
                            else t.dest_trcd
                        end as service_code,
                        case
                            when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, instr(t.dest_trcd, '&') + 1)
                            else ''
                        end as message_type,
                        count(*) as success_count
                    from tss_tran_comp t
                    left join (
                        select service_code, min(tran_code) as tran_code, string_agg(distinct owner, ',' order by owner) as owner
                        from ana_tran_catalog
                        group by service_code
                    ) c on c.service_code = case
                            when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, 1, instr(t.dest_trcd, '&') - 1)
                            else t.dest_trcd
                        end
                """ + tranWhere + """
                    group by
                        t.orig_cdate,
                        coalesce(c.tran_code, ''),
                        case
                            when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, 1, instr(t.dest_trcd, '&') - 1)
                            else t.dest_trcd
                        end,
                        case
                            when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, instr(t.dest_trcd, '&') + 1)
                            else ''
                        end
                ) s
                left join (
                    select
                        tran_code,
                        service_code,
                        count(distinct norm_field_name) as interface_field_count
                    from (
                        select tran_code, service_code, """ + normMappingStd + """
                         as norm_field_name
                        from ana_field_mapping
                """ + mappingWhere + """
                    ) mapping_norm
                    group by tran_code, service_code
                ) m on m.tran_code = s.tran_code and m.service_code = s.service_code
                left join (
                    select
                        grouped.orig_cdate,
                        grouped.tran_code,
                        grouped.service_code,
                        grouped.message_type,
                        max(grouped.batch_id) as batch_id,
                        count(*) as diff_field_count,
                        sum(grouped.diff_count) as compared_field_diff_count,
                        sum(case when grouped.diff_count >= grouped.success_count * 0.01 then 1 else 0 end) as high_ratio_field_count,
                        sum(case when grouped.diff_count < grouped.success_count * 0.01 then 1 else 0 end) as low_ratio_field_count
                    from (
                        select
                            base.orig_cdate,
                            max(base.batch_id) as batch_id,
                            base.tran_code,
                            base.service_code,
                            base.message_type,
                            base.norm_field_name,
                            count(*) as diff_count,
                            max(base.success_count) as success_count
                        from (
                            select
                                t.orig_cdate,
                                sm.batch_id,
                                coalesce(c.tran_code, '') as tran_code,
                                case
                                    when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, 1, instr(t.dest_trcd, '&') - 1)
                                    else t.dest_trcd
                                end as service_code,
                                case
                                    when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, instr(t.dest_trcd, '&') + 1)
                                    else ''
                                end as message_type,
                                coalesce(
                                    """ + normMappedStd + """
                ,
                                    """ + normDest + """
                ,
                                    """ + normOrig + """
                
                                ) as norm_field_name,
                                sc.success_count
                            from tss_tran_comp t
                            join tss_field_comp f on f.orig_cdate = t.orig_cdate
                                 and f.mesg_seq = t.mesg_seq
                                 and f.comp_result = '0'
                            left join ana_sampling_summary sm on sm.orig_cdate = t.orig_cdate
                            left join (
                                select service_code, min(tran_code) as tran_code, string_agg(distinct owner, ',' order by owner) as owner
                                from ana_tran_catalog
                                group by service_code
                            ) c on c.service_code = case
                                    when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, 1, instr(t.dest_trcd, '&') - 1)
                                    else t.dest_trcd
                                end
                            left join ana_field_mapping m on m.tran_code = c.tran_code
                                 and m.service_code = c.service_code
                                 and lower(""" + normMappedStd + """
                ) = lower(coalesce(
                                     """ + normDest + """
                ,
                                     """ + normOrig + """
                
                                  ))
                            join (
                                select
                                    t2.orig_cdate,
                                    coalesce(c2.tran_code, '') as tran_code,
                                    case
                                        when instr(t2.dest_trcd, '&') > 0 then substr(t2.dest_trcd, 1, instr(t2.dest_trcd, '&') - 1)
                                        else t2.dest_trcd
                                    end as service_code,
                                    case
                                        when instr(t2.dest_trcd, '&') > 0 then substr(t2.dest_trcd, instr(t2.dest_trcd, '&') + 1)
                                        else ''
                                    end as message_type,
                                    count(*) as success_count
                                from tss_tran_comp t2
                                left join (
                                    select service_code, min(tran_code) as tran_code
                                    from ana_tran_catalog
                                    group by service_code
                                ) c2 on c2.service_code = case
                                        when instr(t2.dest_trcd, '&') > 0 then substr(t2.dest_trcd, 1, instr(t2.dest_trcd, '&') - 1)
                                        else t2.dest_trcd
                                    end
                                where t2.comp_result = '4'
                                group by
                                    t2.orig_cdate,
                                    coalesce(c2.tran_code, ''),
                                    case
                                        when instr(t2.dest_trcd, '&') > 0 then substr(t2.dest_trcd, 1, instr(t2.dest_trcd, '&') - 1)
                                        else t2.dest_trcd
                                    end,
                                    case
                                        when instr(t2.dest_trcd, '&') > 0 then substr(t2.dest_trcd, instr(t2.dest_trcd, '&') + 1)
                                        else ''
                                    end
                            ) sc on sc.orig_cdate = t.orig_cdate
                                 and sc.tran_code = coalesce(c.tran_code, '')
                                 and sc.service_code = case
                                    when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, 1, instr(t.dest_trcd, '&') - 1)
                                    else t.dest_trcd
                                end
                                 and sc.message_type = case
                                    when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, instr(t.dest_trcd, '&') + 1)
                                    else ''
                                end
                """ + detailWhere + """
                        ) base
                        where base.norm_field_name is not null and length(trim(base.norm_field_name)) > 0
                        group by base.orig_cdate, base.tran_code, base.service_code, base.message_type, base.norm_field_name
                    ) grouped
                    group by grouped.orig_cdate, grouped.tran_code, grouped.service_code, grouped.message_type
                ) ds on ds.orig_cdate = s.orig_cdate
                    and ds.tran_code = s.tran_code
                    and ds.service_code = s.service_code
                    and ds.message_type = s.message_type
                left join (
                    select
                        tran_code,
                        service_code,
                        string_agg(distinct module_name, ',' order by module_name) as module_name,
                        string_agg(distinct owner, ',' order by owner) as owner
                    from ana_tran_catalog
                """ + catalogWhere + """
                    group by tran_code, service_code
                ) c on c.tran_code = s.tran_code and c.service_code = s.service_code
                order by s.orig_cdate, s.tran_code, s.service_code, s.message_type
                """;
    }

    private long count(String table, QueryParts query) {
        Long total = jdbc.queryForObject("select count(*) from " + table + query.where, query.params, Long.class);
        return total == null ? 0 : total;
    }

    private QueryParts groupWhere(SampleSearchCriteria c) {
        return where(c, false);
    }

    private QueryParts detailWhere(SampleSearchCriteria c) {
        return where(c, true);
    }

    private QueryParts transactionResultWhere(SampleSearchCriteria c) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (c != null) {
            addEquals(clauses, params, "r.batch_id", "batchId", c.batchId());
            addEquals(clauses, params, "r.orig_cdate", "origCdate", c.origCdate());
            addLike(clauses, params, "r.tran_code", "tranCode", c.tranCode());
            addLike(clauses, params, "r.service_code", "serviceCode", c.serviceCode());
            addEquals(clauses, params, "r.message_type", "messageType", c.messageType());
            addLike(clauses, params, "r.owner", "owner", c.owner());
            addLike(clauses, params, "r.sample_tran_seq_no", "tranSeqNo", c.tranSeqNo());
        }
        return new QueryParts(clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses), params);
    }

    private SuccessQueryParts transactionSuccessWhere(SampleSearchCriteria c) {
        List<String> tranClauses = new ArrayList<>();
        List<String> detailClauses = new ArrayList<>();
        List<String> mappingClauses = new ArrayList<>();
        List<String> catalogClauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        tranClauses.add("t.comp_result = '4'");
        detailClauses.add("t.comp_result = '4'");
        if (c != null) {
            String effectiveOrigCdate = c.origCdate();
            if (!StringUtils.hasText(effectiveOrigCdate) && StringUtils.hasText(c.batchId())) {
                effectiveOrigCdate = origCdateForBatch(c.batchId().trim());
            }
            if (StringUtils.hasText(c.batchId())) {
                detailClauses.add("sm.batch_id = :batchId");
                params.addValue("batchId", c.batchId().trim());
            }
            if (StringUtils.hasText(effectiveOrigCdate)) {
                tranClauses.add("t.orig_cdate = :origCdate");
                detailClauses.add("t.orig_cdate = :origCdate");
                params.addValue("origCdate", effectiveOrigCdate.trim());
            }
            if (StringUtils.hasText(c.tranCode())) {
                tranClauses.add("c.tran_code like :tranCode");
                detailClauses.add("c.tran_code like :tranCode");
                mappingClauses.add("tran_code like :tranCode");
                catalogClauses.add("tran_code like :tranCode");
                params.addValue("tranCode", "%" + c.tranCode().trim() + "%");
            }
            if (StringUtils.hasText(c.serviceCode())) {
                tranClauses.add("""
                        case
                            when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, 1, instr(t.dest_trcd, '&') - 1)
                            else t.dest_trcd
                        end like :serviceCode
                        """);
                detailClauses.add("""
                        case
                            when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, 1, instr(t.dest_trcd, '&') - 1)
                            else t.dest_trcd
                        end like :serviceCode
                        """);
                mappingClauses.add("service_code like :serviceCode");
                catalogClauses.add("service_code like :serviceCode");
                params.addValue("serviceCode", "%" + c.serviceCode().trim() + "%");
            }
            if (StringUtils.hasText(c.messageType())) {
                tranClauses.add("""
                        case
                            when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, instr(t.dest_trcd, '&') + 1)
                            else ''
                        end = :messageType
                        """);
                detailClauses.add("""
                        case
                            when instr(t.dest_trcd, '&') > 0 then substr(t.dest_trcd, instr(t.dest_trcd, '&') + 1)
                            else ''
                        end = :messageType
                        """);
                params.addValue("messageType", c.messageType().trim());
            }
            if (StringUtils.hasText(c.owner())) {
                tranClauses.add("c.owner like :owner");
                detailClauses.add("c.owner like :owner");
                catalogClauses.add("owner like :owner");
                params.addValue("owner", "%" + c.owner().trim() + "%");
            }
            if (StringUtils.hasText(c.tranSeqNo())) {
                tranClauses.add("t.mesg_seq like :tranSeqNo");
                detailClauses.add("t.mesg_seq like :tranSeqNo");
                params.addValue("tranSeqNo", "%" + c.tranSeqNo().trim() + "%");
            }
        }
        return new SuccessQueryParts(
                " where " + String.join(" and ", tranClauses),
                " where " + String.join(" and ", detailClauses),
                mappingClauses.isEmpty() ? "" : " where " + String.join(" and ", mappingClauses),
                catalogClauses.isEmpty() ? "" : " where " + String.join(" and ", catalogClauses),
                params
        );
    }

    private QueryParts detailFieldWhere(SampleSearchCriteria c, Long sampleId) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (sampleId != null) {
            clauses.add("f.sample_id = :sampleId");
            params.addValue("sampleId", sampleId);
        }
        if (c != null) {
            addEquals(clauses, params, "f.batch_id", "batchId", c.batchId());
            addEquals(clauses, params, "f.message_type", "messageType", c.messageType());
            addEquals(clauses, params, "f.mapping_status", "mappingStatus", c.mappingStatus());
            addLike(clauses, params, "f.std_field_name", "semanticFieldName", c.semanticFieldName());
            addLike(clauses, params, "f.mesg_seq", "tranSeqNo", c.tranSeqNo());
        }
        return new QueryParts(clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses), params);
    }

    private QueryParts fieldResultWhere(SampleSearchCriteria c) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (c != null) {
            addEquals(clauses, params, "r.batch_id", "batchId", c.batchId());
            addEquals(clauses, params, "r.orig_cdate", "origCdate", c.origCdate());
            addLike(clauses, params, "r.tran_code", "tranCode", c.tranCode());
            addLike(clauses, params, "r.service_code", "serviceCode", c.serviceCode());
            addEquals(clauses, params, "r.message_type", "messageType", c.messageType());
            addEquals(clauses, params, "r.mapping_status", "mappingStatus", c.mappingStatus());
            addFieldResultNameLike(clauses, params, c.semanticFieldName());
            addLike(clauses, params, "r.owner", "owner", c.owner());
            addLike(clauses, params, "r.sample_tran_seq_no", "tranSeqNo", c.tranSeqNo());
        }
        return new QueryParts(clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses), params);
    }

    private QueryParts summaryWhere(SamplingSummarySearchCriteria c) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (c != null) {
            addLike(clauses, params, "batch_id", "batchId", c.batchId());
            addEquals(clauses, params, "orig_cdate", "origCdate", c.origCdate());
        }
        return new QueryParts(clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses), params);
    }

    private QueryParts where(SampleSearchCriteria c, boolean includeTranSeq) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        String prefix = includeTranSeq ? "d." : "";
        addEquals(clauses, params, prefix + "batch_id", "batchId", c.batchId());
        addEquals(clauses, params, prefix + "orig_cdate", "origCdate", c.origCdate());
        addEquals(clauses, params, prefix + "sample_type", "sampleType", c.sampleType());
        addLike(clauses, params, prefix + "tran_code", "tranCode", c.tranCode());
        addLike(clauses, params, prefix + "service_code", "serviceCode", c.serviceCode());
        addEquals(clauses, params, prefix + "message_type", "messageType", c.messageType());
        addEquals(clauses, params, prefix + "config_status", "configStatus", c.configStatus());
        addLike(clauses, params, prefix + "owner", "owner", c.owner());
        if (!includeTranSeq) {
            addEquals(clauses, params, prefix + "mapping_status", "mappingStatus", c.mappingStatus());
            addLike(clauses, params, prefix + "semantic_field_names", "semanticFieldName", c.semanticFieldName());
        }
        if (includeTranSeq) {
            addLike(clauses, params, "d.tran_seq_no", "tranSeqNo", c.tranSeqNo());
        }
        String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
        return new QueryParts(where, params);
    }

    private void addEquals(List<String> clauses, MapSqlParameterSource params, String column, String key, String value) {
        if (StringUtils.hasText(value)) {
            clauses.add(column + " = :" + key);
            params.addValue(key, value.trim());
        }
    }

    private void addLike(List<String> clauses, MapSqlParameterSource params, String column, String key, String value) {
        if (StringUtils.hasText(value)) {
            clauses.add(column + " like :" + key);
            params.addValue(key, "%" + value.trim() + "%");
        }
    }

    private void addFieldResultNameLike(List<String> clauses, MapSqlParameterSource params, String value) {
        if (StringUtils.hasText(value)) {
            clauses.add("""
                    (r.sop_field_name like :semanticFieldName
                     or r.soap_field_name like :semanticFieldName
                     or r.bizjson_field_name like :semanticFieldName
                     or r.field_cn_name like :semanticFieldName)
                    """);
            params.addValue("semanticFieldName", "%" + value.trim() + "%");
        }
    }

    private record QueryParts(String where, MapSqlParameterSource params) {}

    private record SuccessQueryParts(String tranWhere, String detailWhere, String mappingWhere,
                                     String catalogWhere, MapSqlParameterSource params) {}
}
