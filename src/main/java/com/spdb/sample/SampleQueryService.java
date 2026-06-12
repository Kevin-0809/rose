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

    private String groupSelect() {
        return """
                select group_id, batch_id, sample_type, dest_trcd, service_code, message_type, tran_code,
                       comp_result, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
                       owner, affected_count, sample_count, reason
                from ana_sample_group
                """;
    }

    private String detailSelect() {
        return """
                select
                       d.sample_id,
                       d.group_id,
                       d.batch_id,
                       d.sample_type,
                       d.sample_seq_no,
                       d.dest_trcd,
                       d.service_code,
                       d.message_type,
                       d.tran_code,
                       d.comp_result,
                       case when d.sample_type = 'RETURN_CODE' then null else coalesce(m.sop_field_name, d.sop_field_name) end as sop_field_name,
                       case when d.sample_type = 'RETURN_CODE' then null else coalesce(m.soap_field_name, d.soap_field_name) end as soap_field_name,
                       case when d.sample_type = 'RETURN_CODE' then null else coalesce(m.bizjson_field_name, d.bizjson_field_name) end as bizjson_field_name,
                       case when d.sample_type = 'RETURN_CODE' then null else coalesce(m.field_cn_name, d.field_cn_name) end as field_cn_name,
                       d.orig_field_value,
                       case when d.sample_type = 'RETURN_CODE' then r.orig_error_desc else null end as orig_field_desc,
                       d.dest_field_value,
                       case when d.sample_type = 'RETURN_CODE' then r.dest_error_desc else null end as dest_field_desc,
                       d.tran_seq_no,
                       d.owner,
                       d.affected_count,
                       d.reason
                from ana_sample_detail d
                left join ana_field_mapping m
                  on m.tran_code = d.tran_code
                 and m.service_code = d.service_code
                 and m.sop_field_name = d.sop_field_name
                 and d.sample_type = 'FIELD_DIFF'
                left join tss_retcode_comp r
                  on r.mesg_seq = d.tran_seq_no
                 and d.sample_type = 'RETURN_CODE'
                """;
    }

    private SampleGroupRow mapGroupRow(ResultSet rs) throws SQLException {
        return new SampleGroupRow(
                rs.getLong("group_id"),
                rs.getString("batch_id"),
                rs.getString("sample_type"),
                rs.getString("dest_trcd"),
                rs.getString("service_code"),
                rs.getString("message_type"),
                rs.getString("tran_code"),
                rs.getString("comp_result"),
                rs.getString("sop_field_name"),
                rs.getString("soap_field_name"),
                rs.getString("bizjson_field_name"),
                rs.getString("field_cn_name"),
                rs.getString("owner"),
                rs.getLong("affected_count"),
                rs.getInt("sample_count"),
                rs.getString("reason")
        );
    }

    private SampleDetailRow mapDetailRow(ResultSet rs) throws SQLException {
        return new SampleDetailRow(
                rs.getLong("sample_id"),
                rs.getLong("group_id"),
                rs.getString("batch_id"),
                rs.getString("sample_type"),
                rs.getInt("sample_seq_no"),
                rs.getString("dest_trcd"),
                rs.getString("service_code"),
                rs.getString("message_type"),
                rs.getString("tran_code"),
                rs.getString("comp_result"),
                rs.getString("sop_field_name"),
                rs.getString("soap_field_name"),
                rs.getString("bizjson_field_name"),
                rs.getString("field_cn_name"),
                rs.getString("orig_field_value"),
                rs.getString("orig_field_desc"),
                rs.getString("dest_field_value"),
                rs.getString("dest_field_desc"),
                rs.getString("tran_seq_no"),
                rs.getString("owner"),
                rs.getLong("affected_count"),
                rs.getString("reason")
        );
    }

    public SummaryStats summary() {
        List<SummaryStats> rows = jdbc.query("""
                select batch_id, orig_cdate, total_tran_count,
                       comp_result_1_count, comp_result_2_count, comp_result_3_count,
                       comp_result_4_count, comp_result_8_count,
                       pass_tran_count, issue_field_count,
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
                rs.getLong("issue_field_count"),
                rs.getLong("fully_matched_count"),
                rs.getLong("sample_group_count"),
                rs.getLong("sample_detail_count")
        ));
        return rows.isEmpty() ? new SummaryStats(null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) : rows.get(0);
    }

    public PagedResult<SamplingSummaryHistoryRow> summaryHistory(SamplingSummarySearchCriteria criteria, PageRequestParams page) {
        QueryParts query = summaryWhere(criteria);
        query.params.addValue("limit", page.size()).addValue("offset", page.offset());
        List<SamplingSummaryHistoryRow> rows = jdbc.query("""
                select batch_id, orig_cdate, total_tran_count,
                       comp_result_1_count, comp_result_2_count, comp_result_3_count,
                       comp_result_4_count, comp_result_8_count,
                       pass_tran_count, issue_field_count,
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
                        rs.getLong("issue_field_count"),
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
                select batch_id, orig_cdate, issue_field_count, fully_matched_count,
                       sample_group_count, sample_detail_count
                from ana_sampling_summary
                order by created_at desc
                limit :limit
                """, new MapSqlParameterSource().addValue("limit", safeLimit), (rs, i) -> new SummaryChartPoint(
                rs.getString("batch_id"),
                rs.getString("orig_cdate"),
                rs.getLong("issue_field_count"),
                rs.getLong("fully_matched_count"),
                rs.getLong("sample_group_count"),
                rs.getLong("sample_detail_count")
        ));
        Collections.reverse(rows);
        return rows;
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
        addEquals(clauses, params, prefix + "sample_type", "sampleType", c.sampleType());
        addLike(clauses, params, prefix + "tran_code", "tranCode", c.tranCode());
        addLike(clauses, params, prefix + "service_code", "serviceCode", c.serviceCode());
        addLike(clauses, params, prefix + "sop_field_name", "sopFieldName", c.sopFieldName());
        addLike(clauses, params, prefix + "field_cn_name", "fieldCnName", c.fieldCnName());
        addLike(clauses, params, prefix + "owner", "owner", c.owner());
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

    private record QueryParts(String where, MapSqlParameterSource params) {}
}
