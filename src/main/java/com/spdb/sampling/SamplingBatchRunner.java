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
        transactionTemplate.executeWithoutResult(status -> runInTransaction(command));
    }

    private void runInTransaction(SamplingCommandRow command) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("batchId", command.batchId())
                .addValue("origCdate", command.origCdate());
        clearBatch(params);
        insertTransactionDiffResults(params);
        insertFieldDiffResults(params);
        writeSummary(params);
    }

    private void clearBatch(MapSqlParameterSource params) {
        jdbc.update("delete from ana_tran_diff_result where batch_id = :batchId", params);
        jdbc.update("delete from ana_field_diff_result where batch_id = :batchId", params);
        jdbc.update("delete from ana_sample_detail_field where batch_id = :batchId", params);
        jdbc.update("delete from ana_sample_detail where batch_id = :batchId", params);
        jdbc.update("delete from ana_sample_group where batch_id = :batchId", params);
        jdbc.update("delete from ana_sampling_summary where batch_id = :batchId", params);
    }

    private void insertTransactionDiffResults(MapSqlParameterSource params) {
        jdbc.update("""
                insert into ana_tran_diff_result (
                    batch_id, orig_cdate, tran_code, service_code, message_type, sample_tran_seq_no,
                    orig_error_code, orig_error_desc, dest_error_code, dest_error_desc,
                    owner, affected_tran_count
                )
                select
                    cast(:batchId as varchar(64)),
                    n.orig_cdate,
                    coalesce(c.tran_code, n.service_code) as tran_code,
                    n.service_code,
                    n.message_type,
                    n.mesg_seq as sample_tran_seq_no,
                    nullif(n.orig_error_code, '') as orig_error_code,
                    nullif(n.orig_error_desc, '') as orig_error_desc,
                    nullif(n.dest_error_code, '') as dest_error_code,
                    nullif(n.dest_error_desc, '') as dest_error_desc,
                    c.owner,
                    1 as affected_tran_count
                from (
                    select
                        r.mesg_seq,
                        r.orig_cdate,
                        case
                            when position('&' in coalesce(coalesce(t.dest_trcd, r.service_code), '')) > 0
                                then substring(coalesce(t.dest_trcd, r.service_code) from 1 for position('&' in coalesce(t.dest_trcd, r.service_code)) - 1)
                            else coalesce(coalesce(t.dest_trcd, r.service_code), '')
                        end as service_code,
                        lower(case
                            when position('&' in coalesce(coalesce(t.dest_trcd, r.service_code), '')) > 0
                                then substring(coalesce(t.dest_trcd, r.service_code) from position('&' in coalesce(t.dest_trcd, r.service_code)) + 1)
                            else ''
                        end) as message_type,
                        r.orig_error_code,
                        r.orig_error_desc,
                        r.dest_error_code,
                        r.dest_error_desc
                    from tss_retcode_comp r
                    left join tss_tran_comp t
                      on t.orig_cdate = r.orig_cdate
                     and t.mesg_seq = r.mesg_seq
                    where r.orig_cdate = :origCdate
                      and (
                            length(trim(coalesce(r.orig_error_code, ''))) > 0
                         or length(trim(coalesce(r.orig_error_desc, ''))) > 0
                         or length(trim(coalesce(r.dest_error_code, ''))) > 0
                         or length(trim(coalesce(r.dest_error_desc, ''))) > 0
                      )
                ) n
                left join ana_tran_catalog c
                  on lower(c.service_code) = lower(n.service_code)
                """, params);
    }

    private void insertFieldDiffResults(MapSqlParameterSource params) {
        jdbc.update("""
                insert into ana_field_diff_result (
                    batch_id, orig_cdate, tran_code, service_code, message_type, message_types,
                    sop_field_name, soap_field_name, bizjson_field_name, field_cn_name, mapping_status,
                    sample_tran_seq_no, orig_field_value, dest_field_value, owner, affected_tran_count
                )
                select cast(:batchId as varchar(64)), result_rows.*
                from (
                with field_base as (
                    select
                        f.mesg_seq,
                        f.orig_cdate,
                        f.field_index,
                        f.orig_field_name,
                        f.orig_field_value,
                        f.dest_field_name,
                        f.dest_field_value,
                        coalesce(t.dest_trcd, f.dest_trcd) as dest_trcd
                    from tss_field_comp f
                    join tss_tran_comp t
                      on t.orig_cdate = f.orig_cdate
                     and t.mesg_seq = f.mesg_seq
                    where f.orig_cdate = cast(:origCdate as varchar(8))
                      and f.comp_result = '0'
                      and t.comp_result = '4'
                ), normalized as (
                    select
                        mesg_seq,
                        orig_cdate,
                        field_index,
                        orig_field_name,
                        orig_field_value,
                        dest_field_name,
                        dest_field_value,
                        case
                            when position('&' in coalesce(dest_trcd, '')) > 0
                                then substring(dest_trcd from 1 for position('&' in dest_trcd) - 1)
                            else coalesce(dest_trcd, '')
                        end as service_code,
                        lower(case
                            when position('&' in coalesce(dest_trcd, '')) > 0
                                then substring(dest_trcd from position('&' in dest_trcd) + 1)
                            else ''
                        end) as message_type
                    from field_base
                ), enriched as (
                    select
                        n.*,
                        coalesce(c.tran_code, n.service_code) as tran_code,
                        c.owner
                    from normalized n
                    left join ana_tran_catalog c
                      on lower(c.service_code) = lower(n.service_code)
                ), mapped as (
                    select
                        e.*,
                        coalesce(mt.mapping_id, ma.mapping_id) as mapping_id,
                        coalesce(mt.std_field_name, ma.std_field_name, e.orig_field_name) as field_group_key,
                        coalesce(mt.field_cn_name, ma.field_cn_name) as mapped_field_cn_name,
                        coalesce(mt.sop_field_name, ma.sop_field_name) as mapped_sop_field_name,
                        coalesce(mt.soap_field_name, ma.soap_field_name) as mapped_soap_field_name,
                        coalesce(mt.bizjson_field_name, ma.bizjson_field_name) as mapped_bizjson_field_name
                    from enriched e
                    left join ana_field_mapping mt
                      on lower(mt.tran_code) = lower(e.tran_code)
                     and lower(mt.service_code) = lower(e.service_code)
                     and (
                            (e.message_type = 'sop' and lower(mt.sop_field_name) = lower(e.orig_field_name))
                         or (e.message_type = 'soap' and lower(mt.soap_field_name) = lower(e.orig_field_name))
                         or (e.message_type = 'bizjson' and lower(mt.bizjson_field_name) = lower(e.orig_field_name))
                     )
                    left join ana_field_mapping ma
                      on mt.mapping_id is null
                     and lower(ma.tran_code) = lower(e.tran_code)
                     and lower(ma.service_code) = lower(e.service_code)
                     and lower(e.orig_field_name) in (
                            lower(ma.std_field_name),
                            lower(coalesce(ma.sop_field_name, '')),
                            lower(coalesce(ma.soap_field_name, '')),
                            lower(coalesce(ma.bizjson_field_name, ''))
                     )
                ), display_rows as (
                    select
                        orig_cdate,
                        tran_code,
                        service_code,
                        message_type,
                        owner,
                        field_group_key,
                        coalesce(mapped_sop_field_name, case when message_type = 'sop' then orig_field_name end) as sop_field_name,
                        coalesce(mapped_soap_field_name, case when message_type = 'soap' then orig_field_name end) as soap_field_name,
                        coalesce(mapped_bizjson_field_name, case when message_type = 'bizjson' then orig_field_name end) as bizjson_field_name,
                        mapped_field_cn_name as field_cn_name,
                        case when mapping_id is null then 'UNMAPPED' else 'MAPPED' end as mapping_status,
                        mesg_seq,
                        field_index,
                        orig_field_value,
                        dest_field_value
                    from mapped
                ), ranked as (
                    select
                        display_rows.*,
                        row_number() over (
                            partition by orig_cdate, tran_code, service_code, field_group_key
                            order by mesg_seq, field_index
                        ) as sample_rank
                    from display_rows
                )
                select
                    orig_cdate,
                    tran_code,
                    service_code,
                    min(message_type) as message_type,
                    string_agg(distinct message_type, ',' order by message_type) as message_types,
                    max(sop_field_name) as sop_field_name,
                    max(soap_field_name) as soap_field_name,
                    max(bizjson_field_name) as bizjson_field_name,
                    max(field_cn_name) as field_cn_name,
                    case
                        when max(case when mapping_status = 'UNMAPPED' then 1 else 0 end) = 1
                         and max(case when mapping_status = 'MAPPED' then 1 else 0 end) = 1 then 'MIXED'
                        when max(case when mapping_status = 'UNMAPPED' then 1 else 0 end) = 1 then 'UNMAPPED'
                        else 'MAPPED'
                    end as mapping_status,
                    max(case when sample_rank = 1 then mesg_seq end) as sample_tran_seq_no,
                    max(case when sample_rank = 1 then orig_field_value end) as orig_field_value,
                    max(case when sample_rank = 1 then dest_field_value end) as dest_field_value,
                    owner,
                    count(distinct mesg_seq) as affected_tran_count
                from ranked
                group by orig_cdate, tran_code, service_code, field_group_key, owner
                ) result_rows
                """, params);
    }

    private void writeSummary(MapSqlParameterSource params) {
        jdbc.update("""
                insert into ana_sampling_summary (
                    batch_id, orig_cdate, total_tran_count, comp_result_1_count, comp_result_2_count,
                    comp_result_3_count, comp_result_4_count, comp_result_8_count, pass_tran_count,
                    tran_issue_count, return_code_issue_count, issue_field_count, field_diff_tran_count,
                    fully_matched_count, unconfigured_service_count, unmapped_field_count,
                    sample_group_count, sample_detail_count
                )
                select
                    :batchId,
                    :origCdate,
                    coalesce((select count(*) from tss_tran_comp t where t.orig_cdate = :origCdate), 0),
                    coalesce((select count(*) from tss_tran_comp t where t.orig_cdate = :origCdate and t.comp_result = '1'), 0),
                    coalesce((select count(*) from tss_tran_comp t where t.orig_cdate = :origCdate and t.comp_result = '2'), 0),
                    coalesce((select count(*) from tss_tran_comp t where t.orig_cdate = :origCdate and t.comp_result = '3'), 0),
                    coalesce((select count(*) from tss_tran_comp t where t.orig_cdate = :origCdate and t.comp_result = '4'), 0),
                    coalesce((select count(*) from tss_tran_comp t where t.orig_cdate = :origCdate and t.comp_result = '8'), 0),
                    coalesce((select count(*) from tss_tran_comp t where t.orig_cdate = :origCdate and t.comp_result = '4'), 0),
                    coalesce((select count(*) from tss_tran_comp t where t.orig_cdate = :origCdate and t.comp_result in ('1', '2')), 0),
                    coalesce((select sum(affected_tran_count) from ana_tran_diff_result r where r.batch_id = :batchId), 0),
                    coalesce((select sum(affected_tran_count) from ana_field_diff_result r where r.batch_id = :batchId), 0),
                    coalesce((
                        select count(distinct f.mesg_seq)
                        from tss_field_comp f
                        join tss_tran_comp t
                          on t.orig_cdate = f.orig_cdate
                         and t.mesg_seq = f.mesg_seq
                        where f.orig_cdate = :origCdate
                          and f.comp_result = '0'
                          and t.comp_result = '4'
                    ), 0),
                    coalesce((select count(*) from tss_tran_comp t where t.orig_cdate = :origCdate and t.comp_result = '4'), 0)
                    - coalesce((
                        select count(distinct f.mesg_seq)
                        from tss_field_comp f
                        join tss_tran_comp t
                          on t.orig_cdate = f.orig_cdate
                         and t.mesg_seq = f.mesg_seq
                        where f.orig_cdate = :origCdate
                          and f.comp_result = '0'
                          and t.comp_result = '4'
                    ), 0),
                    coalesce((
                        select count(*)
                        from (
                            select
                                case
                                    when position('&' in coalesce(t.dest_trcd, '')) > 0
                                        then substring(t.dest_trcd from 1 for position('&' in t.dest_trcd) - 1)
                                    else coalesce(t.dest_trcd, '')
                                end as service_code
                            from tss_tran_comp t
                            left join ana_tran_catalog c
                              on lower(c.service_code) = lower(case
                                    when position('&' in coalesce(t.dest_trcd, '')) > 0
                                        then substring(t.dest_trcd from 1 for position('&' in t.dest_trcd) - 1)
                                    else coalesce(t.dest_trcd, '')
                                end)
                            where t.orig_cdate = :origCdate
                              and c.tran_code is null
                            group by case
                                    when position('&' in coalesce(t.dest_trcd, '')) > 0
                                        then substring(t.dest_trcd from 1 for position('&' in t.dest_trcd) - 1)
                                    else coalesce(t.dest_trcd, '')
                                end
                        ) missing_service
                    ), 0),
                    coalesce((
                        select count(*)
                        from ana_field_diff_result r
                        where r.batch_id = :batchId
                          and r.mapping_status in ('UNMAPPED', 'MIXED')
                    ), 0),
                    coalesce((select count(*) from ana_tran_diff_result r where r.batch_id = :batchId), 0)
                    + coalesce((select count(*) from ana_field_diff_result r where r.batch_id = :batchId), 0),
                    coalesce((select sum(affected_tran_count) from ana_tran_diff_result r where r.batch_id = :batchId), 0)
                    + coalesce((select sum(affected_tran_count) from ana_field_diff_result r where r.batch_id = :batchId), 0)
                """, params);
    }
}
