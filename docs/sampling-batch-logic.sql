-- Sampling batch logic reference SQL.
--
-- Purpose:
--   This file expands the current Java sampling batch logic into SQL so the
--   batch rules can be reviewed in one place.
--
-- Parameters expected by the caller:
--   :batch_id    sampling batch id, for example SMP20260611-120000-0001
--   :orig_cdate  replay comparison date, for example 20260611
--
-- Notes:
--   1. The running application still uses Java streaming logic in
--      SamplingBatchRunner and JdbcSamplingSourceReader.
--   2. This SQL intentionally uses temporary tables for readability. It is a
--      logic reference, not the application's runtime implementation.
--   3. Grouping matches IssueGrouper: orig_cdate, sample_type, tran_code,
--      service_code, comp_result, semantic_signature_hash.
--   4. Each group keeps at most 20 sample detail rows.

begin;

delete from ana_sample_detail_field where batch_id = :batch_id;
delete from ana_sample_detail where batch_id = :batch_id;
delete from ana_sample_group where batch_id = :batch_id;
delete from ana_sampling_summary where batch_id = :batch_id;

drop table if exists tmp_sampling_tran_fact;
create temporary table tmp_sampling_tran_fact as
select
    t.mesg_seq,
    t.orig_cdate,
    t.dest_trcd,
    case
        when position('&' in coalesce(t.dest_trcd, '')) > 0
            then substring(t.dest_trcd from 1 for position('&' in t.dest_trcd) - 1)
        else coalesce(t.dest_trcd, '')
    end as service_code,
    lower(case
        when position('&' in coalesce(t.dest_trcd, '')) > 0
            then substring(t.dest_trcd from position('&' in t.dest_trcd) + 1)
        else ''
    end) as message_type,
    coalesce(c.tran_code, case
        when position('&' in coalesce(t.dest_trcd, '')) > 0
            then substring(t.dest_trcd from 1 for position('&' in t.dest_trcd) - 1)
        else coalesce(t.dest_trcd, '')
    end) as tran_code,
    c.tran_name,
    c.module_name,
    c.owner,
    t.comp_result,
    case when c.tran_code is null then 'UNCONFIGURED_SERVICE' else 'CONFIGURED' end as config_status
from tss_tran_comp t
left join ana_tran_catalog c
  on lower(c.service_code) = lower(case
        when position('&' in coalesce(t.dest_trcd, '')) > 0
            then substring(t.dest_trcd from 1 for position('&' in t.dest_trcd) - 1)
        else coalesce(t.dest_trcd, '')
    end)
where t.orig_cdate = :orig_cdate;

drop table if exists tmp_sampling_field_diff;
create temporary table tmp_sampling_field_diff as
select
    f.mesg_seq,
    f.orig_cdate,
    f.dest_trcd,
    f.field_index,
    f.orig_field_name as raw_field_name,
    f.orig_field_value,
    f.dest_field_name,
    f.dest_field_value,
    tf.service_code,
    tf.message_type,
    tf.tran_code,
    tf.tran_name,
    tf.module_name,
    tf.owner,
    tf.comp_result,
    tf.config_status,
    coalesce(mt.std_field_name, ma.std_field_name, f.orig_field_name) as std_field_name,
    coalesce(mt.field_cn_name, ma.field_cn_name) as field_cn_name,
    coalesce(mt.sop_field_name, ma.sop_field_name) as sop_field_name,
    coalesce(mt.soap_field_name, ma.soap_field_name) as soap_field_name,
    coalesce(mt.bizjson_field_name, ma.bizjson_field_name) as bizjson_field_name,
    case when coalesce(mt.mapping_id, ma.mapping_id) is null then 'UNMAPPED' else 'MAPPED' end as mapping_status
from tss_field_comp f
join tmp_sampling_tran_fact tf
  on tf.mesg_seq = f.mesg_seq
left join ana_field_mapping mt
  on lower(mt.tran_code) = lower(tf.tran_code)
 and lower(mt.service_code) = lower(tf.service_code)
 and (
        (tf.message_type = 'sop' and lower(mt.sop_field_name) = lower(f.orig_field_name))
     or (tf.message_type = 'soap' and lower(mt.soap_field_name) = lower(f.orig_field_name))
     or (tf.message_type = 'bizjson' and lower(mt.bizjson_field_name) = lower(f.orig_field_name))
 )
left join ana_field_mapping ma
  on mt.mapping_id is null
 and lower(ma.tran_code) = lower(tf.tran_code)
 and lower(ma.service_code) = lower(tf.service_code)
 and lower(f.orig_field_name) in (
        lower(ma.std_field_name),
        lower(coalesce(ma.sop_field_name, '')),
        lower(coalesce(ma.soap_field_name, '')),
        lower(coalesce(ma.bizjson_field_name, ''))
 )
where f.orig_cdate = :orig_cdate
  and f.comp_result = '0'
  and tf.comp_result = '4';

drop table if exists tmp_sampling_field_signature;
create temporary table tmp_sampling_field_signature as
select
    mesg_seq,
    orig_cdate,
    service_code,
    message_type,
    tran_code,
    tran_name,
    module_name,
    owner,
    dest_trcd,
    comp_result,
    config_status,
    case when bool_or(mapping_status = 'UNMAPPED') then 'UNMAPPED' else 'MAPPED' end as mapping_status,
    string_agg(
        std_field_name || ':' || coalesce(orig_field_value, '') || '->' || coalesce(dest_field_value, ''),
        '|'
        order by std_field_name, raw_field_name
    ) as semantic_signature,
    md5(string_agg(
        std_field_name || ':' || coalesce(orig_field_value, '') || '->' || coalesce(dest_field_value, ''),
        '|'
        order by std_field_name, raw_field_name
    )) as semantic_signature_hash,
    string_agg(distinct std_field_name, ',' order by std_field_name) as semantic_field_names,
    count(*) as affected_field_count
from tmp_sampling_field_diff
group by
    mesg_seq, orig_cdate, service_code, message_type,
    tran_code, tran_name, module_name, owner, dest_trcd, comp_result, config_status;

drop table if exists tmp_sampling_candidate;
create temporary table tmp_sampling_candidate as
select
    tf.orig_cdate,
    'RETURN_CODE' as sample_type,
    tf.mesg_seq,
    tf.dest_trcd,
    tf.service_code,
    tf.message_type,
    tf.tran_code,
    tf.tran_name,
    tf.module_name,
    tf.owner,
    tf.comp_result,
    tf.config_status,
    'MAPPED' as mapping_status,
    'TRANSACTION:' || tf.comp_result as semantic_signature,
    md5('TRANSACTION:' || tf.comp_result) as semantic_signature_hash,
    null::varchar(1000) as semantic_field_names,
    null::varchar(64) as orig_error_code,
    null::varchar(500) as orig_error_desc,
    null::varchar(64) as dest_error_code,
    null::varchar(500) as dest_error_desc,
    0::bigint as affected_field_count
from tmp_sampling_tran_fact tf
where tf.comp_result in ('1', '2', '8')

union all

select
    tf.orig_cdate,
    'RETURN_CODE' as sample_type,
    tf.mesg_seq,
    tf.dest_trcd,
    tf.service_code,
    tf.message_type,
    tf.tran_code,
    tf.tran_name,
    tf.module_name,
    tf.owner,
    tf.comp_result,
    tf.config_status,
    'MAPPED' as mapping_status,
    'returnCode:' || coalesce(r.orig_error_code, '') || '->' || coalesce(r.dest_error_code, '') as semantic_signature,
    md5('returnCode:' || coalesce(r.orig_error_code, '') || '->' || coalesce(r.dest_error_code, '')) as semantic_signature_hash,
    null::varchar(1000) as semantic_field_names,
    r.orig_error_code,
    r.orig_error_desc,
    r.dest_error_code,
    r.dest_error_desc,
    0::bigint as affected_field_count
from tss_retcode_comp r
join tmp_sampling_tran_fact tf
  on tf.mesg_seq = r.mesg_seq
where r.orig_cdate = :orig_cdate

union all

select
    fs.orig_cdate,
    'FIELD_DIFF' as sample_type,
    fs.mesg_seq,
    fs.dest_trcd,
    fs.service_code,
    fs.message_type,
    fs.tran_code,
    fs.tran_name,
    fs.module_name,
    fs.owner,
    fs.comp_result,
    fs.config_status,
    fs.mapping_status,
    fs.semantic_signature,
    fs.semantic_signature_hash,
    fs.semantic_field_names,
    null::varchar(64) as orig_error_code,
    null::varchar(500) as orig_error_desc,
    null::varchar(64) as dest_error_code,
    null::varchar(500) as dest_error_desc,
    fs.affected_field_count
from tmp_sampling_field_signature fs;

drop table if exists tmp_sampling_group;
create temporary table tmp_sampling_group as
select
    :batch_id as batch_id,
    c.orig_cdate,
    c.sample_type,
    c.orig_cdate || '|' || c.sample_type || '|' || c.tran_code || '|' ||
        c.service_code || '|' || c.comp_result || '|' || c.semantic_signature_hash as group_key,
    md5(c.orig_cdate || '|' || c.sample_type || '|' || c.tran_code || '|' ||
        c.service_code || '|' || c.comp_result || '|' || c.semantic_signature_hash) as group_hash,
    min(c.config_status) as config_status,
    case
        when bool_or(c.mapping_status = 'UNMAPPED') and bool_or(c.mapping_status = 'MAPPED') then 'MIXED'
        when bool_or(c.mapping_status = 'UNMAPPED') then 'UNMAPPED'
        else 'MAPPED'
    end as mapping_status,
    min(c.semantic_signature) as semantic_signature,
    c.semantic_signature_hash,
    string_agg(distinct c.semantic_field_names, ',' order by c.semantic_field_names)
        filter (where c.semantic_field_names is not null and c.semantic_field_names <> '') as semantic_field_names,
    string_agg(distinct c.message_type, ',' order by c.message_type)
        filter (where c.message_type is not null and c.message_type <> '') as message_types,
    min(c.dest_trcd) as dest_trcd,
    c.service_code,
    min(c.message_type) as message_type,
    c.tran_code,
    c.comp_result,
    coalesce(
        split_part(string_agg(distinct c.semantic_field_names, ',' order by c.semantic_field_names)
            filter (where c.semantic_field_names is not null and c.semantic_field_names <> ''), ',', 1),
        c.sample_type
    ) as sop_field_name,
    min(c.owner) as owner,
    count(*) as affected_count,
    count(*) as affected_tran_count,
    sum(c.affected_field_count) as affected_field_count,
    least(count(*), 20)::integer as sample_count
from tmp_sampling_candidate c
group by
    c.orig_cdate, c.sample_type, c.tran_code, c.service_code,
    c.comp_result, c.semantic_signature_hash;

insert into ana_sample_group (
    batch_id, orig_cdate, sample_type, group_key, group_hash, config_status, mapping_status,
    semantic_signature, semantic_signature_hash, semantic_field_names, message_types,
    dest_trcd, service_code, message_type, tran_code, comp_result, sop_field_name,
    owner, affected_count, affected_tran_count, affected_field_count, sample_count
)
select
    batch_id, orig_cdate, sample_type, group_key, group_hash, config_status, mapping_status,
    semantic_signature, semantic_signature_hash, semantic_field_names, message_types,
    dest_trcd, service_code, message_type, tran_code, comp_result, sop_field_name,
    owner, affected_count, affected_tran_count, affected_field_count, sample_count
from tmp_sampling_group;

drop table if exists tmp_sampling_sample_pick;
create temporary table tmp_sampling_sample_pick as
select
    g.group_id,
    c.*,
    row_number() over (
        partition by g.group_key
        order by c.mesg_seq
    ) as sample_seq_no
from tmp_sampling_candidate c
join tmp_sampling_group tg
  on tg.group_key = c.orig_cdate || '|' || c.sample_type || '|' || c.tran_code || '|' ||
        c.service_code || '|' || c.comp_result || '|' || c.semantic_signature_hash
join ana_sample_group g
  on g.batch_id = :batch_id
 and g.group_key = tg.group_key;

insert into ana_sample_detail (
    group_id, batch_id, orig_cdate, sample_type, sample_seq_no, config_status,
    dest_trcd, service_code, message_type, tran_code, comp_result,
    sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
    tran_seq_no, owner, affected_count, field_count, orig_error_code, orig_error_desc,
    dest_error_code, dest_error_desc, source_table, source_pk
)
select
    p.group_id,
    :batch_id,
    p.orig_cdate,
    p.sample_type,
    p.sample_seq_no,
    p.config_status,
    p.dest_trcd,
    p.service_code,
    p.message_type,
    p.tran_code,
    p.comp_result,
    string_agg(distinct fd.sop_field_name, ',' order by fd.sop_field_name)
        filter (where fd.sop_field_name is not null and fd.sop_field_name <> '') as sop_field_name,
    string_agg(distinct fd.soap_field_name, ',' order by fd.soap_field_name)
        filter (where fd.soap_field_name is not null and fd.soap_field_name <> '') as soap_field_name,
    string_agg(distinct fd.bizjson_field_name, ',' order by fd.bizjson_field_name)
        filter (where fd.bizjson_field_name is not null and fd.bizjson_field_name <> '') as bizjson_field_name,
    string_agg(distinct fd.field_cn_name, ',' order by fd.field_cn_name)
        filter (where fd.field_cn_name is not null and fd.field_cn_name <> '') as field_cn_name,
    p.mesg_seq,
    p.owner,
    g.affected_tran_count,
    count(fd.field_index)::integer as field_count,
    p.orig_error_code,
    p.orig_error_desc,
    p.dest_error_code,
    p.dest_error_desc,
    case when p.sample_type = 'RETURN_CODE' then 'tss_retcode_comp' else 'tss_field_comp' end as source_table,
    p.mesg_seq as source_pk
from tmp_sampling_sample_pick p
join ana_sample_group g
  on g.group_id = p.group_id
left join tmp_sampling_field_diff fd
  on fd.mesg_seq = p.mesg_seq
 and p.sample_type = 'FIELD_DIFF'
where p.sample_seq_no <= 20
group by
    p.group_id, p.orig_cdate, p.sample_type, p.sample_seq_no, p.config_status,
    p.dest_trcd, p.service_code, p.message_type, p.tran_code, p.comp_result,
    p.mesg_seq, p.owner, g.affected_tran_count, p.orig_error_code, p.orig_error_desc,
    p.dest_error_code, p.dest_error_desc;

insert into ana_sample_detail_field (
    sample_id, group_id, batch_id, mesg_seq, message_type, raw_field_name,
    std_field_name, field_cn_name, orig_field_value, dest_field_value,
    mapping_status, field_index
)
select
    d.sample_id,
    d.group_id,
    :batch_id,
    fd.mesg_seq,
    fd.message_type,
    fd.raw_field_name,
    fd.std_field_name,
    fd.field_cn_name,
    fd.orig_field_value,
    fd.dest_field_value,
    fd.mapping_status,
    fd.field_index
from ana_sample_detail d
join tmp_sampling_field_diff fd
  on fd.mesg_seq = d.tran_seq_no
 and fd.message_type = d.message_type
where d.batch_id = :batch_id
  and d.sample_type = 'FIELD_DIFF';

insert into ana_sampling_summary (
    batch_id, orig_cdate, total_tran_count, comp_result_1_count, comp_result_2_count,
    comp_result_3_count, comp_result_4_count, comp_result_8_count, pass_tran_count,
    tran_issue_count, return_code_issue_count, issue_field_count, field_diff_tran_count,
    fully_matched_count, unconfigured_service_count, unmapped_field_count,
    sample_group_count, sample_detail_count
)
select
    :batch_id,
    :orig_cdate,
    count(*) as total_tran_count,
    count(*) filter (where comp_result = '1') as comp_result_1_count,
    count(*) filter (where comp_result = '2') as comp_result_2_count,
    count(*) filter (where comp_result = '3') as comp_result_3_count,
    count(*) filter (where comp_result = '4') as comp_result_4_count,
    count(*) filter (where comp_result = '8') as comp_result_8_count,
    count(*) filter (where comp_result = '4') as pass_tran_count,
    count(*) filter (where comp_result in ('1', '2', '8')) as tran_issue_count,
    (select count(*) from tss_retcode_comp where orig_cdate = :orig_cdate) as return_code_issue_count,
    (select count(distinct mesg_seq)
       from tmp_sampling_field_diff) as issue_field_count,
    (select count(distinct mesg_seq)
       from tmp_sampling_field_diff) as field_diff_tran_count,
    count(*) filter (where comp_result = '4')
        - (select count(distinct mesg_seq)
             from tmp_sampling_field_diff) as fully_matched_count,
    count(distinct service_code) filter (where config_status = 'UNCONFIGURED_SERVICE') as unconfigured_service_count,
    (select count(distinct service_code || '|' || raw_field_name)
       from tmp_sampling_field_diff
      where mapping_status = 'UNMAPPED') as unmapped_field_count,
    (select count(*) from ana_sample_group where batch_id = :batch_id) as sample_group_count,
    (select count(*) from ana_sample_detail where batch_id = :batch_id) as sample_detail_count
from tmp_sampling_tran_fact;

commit;
