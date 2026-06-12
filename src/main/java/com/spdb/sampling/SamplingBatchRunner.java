package com.spdb.sampling;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SamplingBatchRunner {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public SamplingBatchRunner(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void run(SamplingCommandRow command) {
        if (command == null) {
            throw new IllegalArgumentException("采样批次不存在");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("batchId", command.batchId())
                .addValue("origCdate", command.origCdate());

        initializeSampling(params);
        materializeCandidates(params);
        insertSampleGroups(params);
        jdbc.getJdbcTemplate().execute("analyze ana_sampling_candidate");
        jdbc.getJdbcTemplate().execute("analyze ana_sample_group");
        materializeDetailCandidates(params);
        updateSummary(params);
        finalizeSampling(params);
    }

    private void initializeSampling(MapSqlParameterSource params) {
        jdbc.update("delete from ana_sample_detail where batch_id = :batchId", params);
        jdbc.update("delete from ana_sample_group where batch_id = :batchId", params);
        jdbc.update("delete from ana_sampling_candidate where batch_id = :batchId", params);
        jdbc.update("delete from ana_sampling_summary where batch_id = :batchId", params);
        jdbc.update("""
                insert into ana_sampling_summary (
                    batch_id, orig_cdate, total_tran_count,
                    comp_result_1_count, comp_result_2_count, comp_result_3_count,
                    comp_result_4_count, comp_result_8_count, pass_tran_count,
                    issue_field_count, fully_matched_count, sample_group_count, sample_detail_count
                ) values (
                    :batchId, :origCdate, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
                )
                """, params);
    }

    private void insertSampleGroups(MapSqlParameterSource params) {
        jdbc.update(groupedCandidateCte() + """
                insert into ana_sample_group (
                    batch_id, sample_type, group_key, group_hash, dest_trcd, service_code, message_type, tran_code,
                    comp_result, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
                    orig_field_value, dest_field_value, owner, affected_count, sample_count
                )
                select
                    :batchId, sample_type, group_key, group_hash, dest_trcd, service_code, message_type, tran_code,
                    comp_result, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
                    orig_field_value, dest_field_value, owner, affected_count,
                    least(affected_count, case when sample_type = 'RETURN_CODE' then 1 else 10 end)
                from grouped
                """, params);
    }

    private void materializeDetailCandidates(MapSqlParameterSource params) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().execute("drop table if exists tmp_sampling_detail_candidate");
            jdbc.getJdbcTemplate().execute("""
                    create temporary table tmp_sampling_detail_candidate (
                        group_id bigint not null,
                        candidate_id bigint not null,
                        sample_type varchar(32) not null,
                        dest_trcd varchar(200) not null,
                        service_code varchar(200) not null,
                        message_type varchar(32),
                        tran_code varchar(32) not null,
                        comp_result varchar(1) not null,
                        sop_field_name varchar(200) not null,
                        soap_field_name varchar(200),
                        bizjson_field_name varchar(200),
                        field_cn_name varchar(200),
                        orig_field_value varchar(2000),
                        dest_field_value varchar(2000),
                        mesg_seq varchar(64) not null,
                        conv_index integer,
                        conv_cindex integer,
                        field_index integer,
                        owner varchar(100),
                        affected_count bigint not null
                    ) on commit delete rows
                    """);
            jdbc.update("""
                    insert into tmp_sampling_detail_candidate (
                        group_id, candidate_id, sample_type, dest_trcd, service_code, message_type,
                        tran_code, comp_result, sop_field_name, soap_field_name, bizjson_field_name,
                        field_cn_name, orig_field_value, dest_field_value, mesg_seq, conv_index,
                        conv_cindex, field_index, owner, affected_count
                    )
                    select
                        g.group_id,
                        c.candidate_id,
                        c.sample_type,
                        c.dest_trcd,
                        c.service_code,
                        c.message_type,
                        c.tran_code,
                        c.comp_result,
                        c.sop_field_name,
                        c.soap_field_name,
                        c.bizjson_field_name,
                        c.field_cn_name,
                        c.orig_field_value,
                        c.dest_field_value,
                        c.mesg_seq,
                        c.conv_index,
                        c.conv_cindex,
                        c.field_index,
                        c.owner,
                        g.affected_count
                    from ana_sampling_candidate c
                    join ana_sample_group g
                      on g.batch_id = :batchId
                     and c.group_hash = g.group_hash
                     and c.group_key = g.group_key
                    where c.batch_id = :batchId
                    """, params);
            jdbc.getJdbcTemplate().execute("create index tmp_sampling_detail_candidate_small_idx on tmp_sampling_detail_candidate(affected_count, group_id, candidate_id)");
            jdbc.getJdbcTemplate().execute("create index tmp_sampling_detail_candidate_group_idx on tmp_sampling_detail_candidate(group_id, candidate_id)");
            jdbc.getJdbcTemplate().execute("analyze tmp_sampling_detail_candidate");
            insertSampleDetails(params);
            jdbc.getJdbcTemplate().execute("drop table tmp_sampling_detail_candidate");
        });
    }

    private void insertSampleDetails(MapSqlParameterSource params) {
        jdbc.update("""
                insert into ana_sample_detail (
                    group_id, batch_id, sample_type, sample_seq_no, dest_trcd, service_code, message_type,
                    tran_code, comp_result, sop_field_name, soap_field_name, bizjson_field_name,
                    field_cn_name, orig_field_value, dest_field_value, tran_seq_no, owner, affected_count,
                    source_table, source_pk
                )
                select
                    c.group_id,
                    :batchId,
                    c.sample_type,
                    c.sample_seq_no,
                    c.dest_trcd,
                    c.service_code,
                    c.message_type,
                    c.tran_code,
                    c.comp_result,
                    c.sop_field_name,
                    c.soap_field_name,
                    c.bizjson_field_name,
                    c.field_cn_name,
                    c.orig_field_value,
                    c.dest_field_value,
                    c.mesg_seq,
                    c.owner,
                    c.affected_count,
                    case when c.sample_type = 'RETURN_CODE' then 'tss_retcode_comp' else 'tss_field_comp' end,
                    case
                        when c.sample_type = 'RETURN_CODE' then c.mesg_seq
                        else c.mesg_seq || ':' || c.conv_index::text || ':' || c.conv_cindex::text || ':' ||
                             c.field_index::text || ':' || c.sop_field_name
                    end
                from (
                    select
                        c.group_id,
                        c.sample_type,
                        row_number() over (partition by c.group_id order by c.candidate_id) as sample_seq_no,
                        c.dest_trcd,
                        c.service_code,
                        c.message_type,
                        c.tran_code,
                        c.comp_result,
                        c.sop_field_name,
                        c.soap_field_name,
                        c.bizjson_field_name,
                        c.field_cn_name,
                        c.orig_field_value,
                        c.dest_field_value,
                        c.mesg_seq,
                        c.conv_index,
                        c.conv_cindex,
                        c.field_index,
                        c.owner,
                        c.affected_count
                    from tmp_sampling_detail_candidate c
                ) c
                where c.sample_seq_no <= case when c.sample_type = 'RETURN_CODE' then 1 else 10 end
                """, params);
    }

    private void materializeCandidates(MapSqlParameterSource params) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().execute("drop table if exists tmp_sampling_diff");
            jdbc.getJdbcTemplate().execute("""
                    create temporary table tmp_sampling_diff (
                        sample_type varchar(32) not null,
                        dest_trcd varchar(200) not null,
                        service_code varchar(200) not null,
                        message_type varchar(32),
                        comp_result varchar(1) not null,
                        orig_field_name varchar(200),
                        dest_field_name varchar(200),
                        orig_field_value varchar(2000),
                        dest_field_value varchar(2000),
                        mesg_seq varchar(64) not null,
                        conv_index integer,
                        conv_cindex integer,
                        field_index integer
                    ) on commit delete rows
                    """);
            jdbc.update("""
                    insert into tmp_sampling_diff (
                        sample_type, dest_trcd, service_code, message_type, comp_result, orig_field_name, dest_field_name,
                        orig_field_value, dest_field_value, mesg_seq, conv_index, conv_cindex, field_index
                    )
                    select
                        'RETURN_CODE' as sample_type,
                        r.service_code as dest_trcd,
                        split_part(r.service_code, '&', 1) as service_code,
                        split_part(r.service_code, '&', 2) as message_type,
                        t.comp_result,
                        'returnCode' as orig_field_name,
                        'returnCode' as dest_field_name,
                        r.orig_error_code as orig_field_value,
                        r.dest_error_code as dest_field_value,
                        r.mesg_seq,
                        t.conv_index,
                        t.conv_cindex,
                        1 as field_index
                    from tss_retcode_comp r
                    join tss_tran_comp t
                      on t.mesg_seq = r.mesg_seq
                    where r.orig_cdate = :origCdate
                      and t.orig_cdate = :origCdate
                      and t.comp_result <> '4'
                    union all
                    select
                        'FIELD_DIFF' as sample_type,
                        f.dest_trcd,
                        split_part(f.dest_trcd, '&', 1) as service_code,
                        split_part(f.dest_trcd, '&', 2) as message_type,
                        t.comp_result,
                        f.orig_field_name,
                        f.dest_field_name,
                        f.orig_field_value,
                        f.dest_field_value,
                        f.mesg_seq,
                        f.conv_index,
                        f.conv_cindex,
                        f.field_index
                    from tss_field_comp f
                    join tss_tran_comp t
                      on t.mesg_seq = f.mesg_seq
                    where f.orig_cdate = :origCdate
                      and f.comp_result = '0'
                      and t.orig_cdate = :origCdate
                      and t.comp_result = '4'
                    """, params);
            jdbc.getJdbcTemplate().execute("create index tmp_sampling_diff_mapping_idx on tmp_sampling_diff(service_code, orig_field_name)");
            jdbc.getJdbcTemplate().execute("create index tmp_sampling_diff_seq_idx on tmp_sampling_diff(mesg_seq, conv_index, conv_cindex, field_index)");
            jdbc.getJdbcTemplate().execute("analyze tmp_sampling_diff");

            jdbc.update("""
                insert into ana_sampling_candidate (
                    batch_id, sample_type, group_key, group_hash, dest_trcd, service_code, message_type, tran_code,
                    comp_result, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
                    orig_field_value, dest_field_value, mesg_seq, conv_index, conv_cindex, field_index, owner
                )
                select
                    :batchId,
                    d.sample_type,
                    :batchId || '|' ||
                        d.sample_type || '|' || c.tran_code || '|' || d.service_code || '|' || d.comp_result || '|' ||
                        coalesce(m.sop_field_name, d.orig_field_name) || '|' ||
                        coalesce(case when d.sample_type = 'RETURN_CODE' then d.orig_field_value else null end, '') || '|' ||
                        coalesce(case when d.sample_type = 'RETURN_CODE' then d.dest_field_value else null end, '') as group_key,
                    md5(:batchId || '|' ||
                        d.sample_type || '|' || c.tran_code || '|' || d.service_code || '|' || d.comp_result || '|' ||
                        coalesce(m.sop_field_name, d.orig_field_name) || '|' ||
                        coalesce(case when d.sample_type = 'RETURN_CODE' then d.orig_field_value else null end, '') || '|' ||
                        coalesce(case when d.sample_type = 'RETURN_CODE' then d.dest_field_value else null end, '')) as group_hash,
                    d.dest_trcd,
                    d.service_code,
                    d.message_type,
                    c.tran_code,
                    d.comp_result,
                    coalesce(m.sop_field_name, d.orig_field_name) as sop_field_name,
                    coalesce(m.soap_field_name, d.dest_field_name) as soap_field_name,
                    coalesce(m.bizjson_field_name, d.dest_field_name) as bizjson_field_name,
                    m.field_cn_name,
                    d.orig_field_value,
                    d.dest_field_value,
                    d.mesg_seq,
                    d.conv_index,
                    d.conv_cindex,
                    d.field_index,
                    c.owner
                from tmp_sampling_diff d
                join ana_tran_catalog c
                  on c.service_code = d.service_code
                left join ana_field_mapping m
                 on m.tran_code = c.tran_code
                 and m.service_code = c.service_code
                 and m.sop_field_name = d.orig_field_name
                """, params);
            jdbc.getJdbcTemplate().execute("drop table tmp_sampling_diff");
        });
    }

    private void updateSummary(MapSqlParameterSource params) {
        Long total = jdbc.queryForObject("""
                select count(*)
                from tss_tran_comp t
                join (
                    select distinct service_code
                    from ana_tran_catalog
                ) c
                  on c.service_code = split_part(t.dest_trcd, '&', 1)
                where t.orig_cdate = :origCdate
                """, params, Long.class);
        Long comp1 = countTranByResult(params, "1");
        Long comp2 = countTranByResult(params, "2");
        Long comp3 = countTranByResult(params, "3");
        Long comp4 = countTranByResult(params, "4");
        Long comp8 = countTranByResult(params, "8");
        Long issueFieldCount = jdbc.queryForObject("""
                select count(*)
                from (
                    select distinct f.mesg_seq, f.conv_index, f.conv_cindex, f.orig_field_name
                    from tss_field_comp f
                    join tss_tran_comp t
                      on t.mesg_seq = f.mesg_seq
                    where t.orig_cdate = :origCdate
                      and f.orig_cdate = :origCdate
                      and f.comp_result = '0'
                      and t.comp_result = '4'
                ) d
                """, params, Long.class);
        Long fullyMatchedCount = jdbc.queryForObject("""
                select count(*)
                from tss_tran_comp t
                join (
                    select distinct service_code
                    from ana_tran_catalog
                ) c
                  on c.service_code = split_part(t.dest_trcd, '&', 1)
                where t.orig_cdate = :origCdate
                  and t.comp_result = '4'
                  and not exists (
                      select 1
                      from tss_field_comp f
                      where f.mesg_seq = t.mesg_seq
                        and f.comp_result = '0'
                  )
                """, params, Long.class);
        params.addValue("total", total == null ? 0L : total)
                .addValue("comp1", comp1 == null ? 0L : comp1)
                .addValue("comp2", comp2 == null ? 0L : comp2)
                .addValue("comp3", comp3 == null ? 0L : comp3)
                .addValue("comp4", comp4 == null ? 0L : comp4)
                .addValue("comp8", comp8 == null ? 0L : comp8)
                .addValue("issueFields", issueFieldCount == null ? 0L : issueFieldCount)
                .addValue("fullyMatched", fullyMatchedCount == null ? 0L : fullyMatchedCount);
        jdbc.update("""
                update ana_sampling_summary
                   set total_tran_count = :total,
                       comp_result_1_count = :comp1,
                       comp_result_2_count = :comp2,
                       comp_result_3_count = :comp3,
                       comp_result_4_count = :comp4,
                       comp_result_8_count = :comp8,
                       pass_tran_count = :comp4,
                       issue_field_count = :issueFields,
                       fully_matched_count = :fullyMatched
                 where batch_id = :batchId
                """, params);
    }

    private void finalizeSampling(MapSqlParameterSource params) {
        jdbc.update("""
                update ana_sampling_summary
                   set sample_group_count = coalesce((select count(*) from ana_sample_group where batch_id = :batchId), 0),
                       sample_detail_count = coalesce((select count(*) from ana_sample_detail where batch_id = :batchId), 0)
                 where batch_id = :batchId
                """, params);
        jdbc.update("delete from ana_sampling_candidate where batch_id = :batchId", params);
    }

    private String groupedCandidateCte() {
        return """
                with grouped as (
                    select
                        sample_type,
                        :batchId || '|' || sample_type || '|' || tran_code || '|' || service_code || '|' ||
                            comp_result || '|' || sop_field_name || '|' ||
                            coalesce(min(case when sample_type = 'RETURN_CODE' then orig_field_value else null end), '') || '|' ||
                            coalesce(min(case when sample_type = 'RETURN_CODE' then dest_field_value else null end), '') as group_key,
                        md5(:batchId || '|' || sample_type || '|' || tran_code || '|' || service_code || '|' ||
                            comp_result || '|' || sop_field_name || '|' ||
                            coalesce(min(case when sample_type = 'RETURN_CODE' then orig_field_value else null end), '') || '|' ||
                            coalesce(min(case when sample_type = 'RETURN_CODE' then dest_field_value else null end), '')) as group_hash,
                        min(dest_trcd) as dest_trcd,
                        service_code,
                        min(message_type) as message_type,
                        tran_code,
                        case when sample_type = 'FIELD_DIFF' then '4' else min(comp_result) end as comp_result,
                        sop_field_name,
                        min(soap_field_name) as soap_field_name,
                        min(bizjson_field_name) as bizjson_field_name,
                        min(field_cn_name) as field_cn_name,
                        min(case when sample_type = 'RETURN_CODE' then orig_field_value else null end) as orig_field_value,
                        min(case when sample_type = 'RETURN_CODE' then dest_field_value else null end) as dest_field_value,
                        min(owner) as owner,
                        count(*) as affected_count
                    from ana_sampling_candidate
                    where batch_id = :batchId
                    group by sample_type, tran_code, service_code, comp_result, sop_field_name,
                             case when sample_type = 'RETURN_CODE' then orig_field_value else null end,
                             case when sample_type = 'RETURN_CODE' then dest_field_value else null end
                )
                """;
    }

    private Long countTranByResult(MapSqlParameterSource params, String compResult) {
        return jdbc.queryForObject("""
                select count(*)
                from tss_tran_comp t
                join (
                    select distinct service_code
                    from ana_tran_catalog
                ) c
                  on c.service_code = split_part(t.dest_trcd, '&', 1)
                where t.orig_cdate = :origCdate
                  and t.comp_result = :compResult
                """, new MapSqlParameterSource()
                .addValue("origCdate", params.getValue("origCdate"))
                .addValue("compResult", compResult), Long.class);
    }
}
