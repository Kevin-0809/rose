\set ON_ERROR_STOP on

delete from ana_sample_detail
where batch_id = 'BATCH_20260608_SEED';

delete from ana_sample_group
where batch_id = 'BATCH_20260608_SEED';

drop table if exists tmp_ana_sample_candidates;

create temporary table tmp_ana_sample_candidates as
select
    case
        when f.orig_field_name = 'returnCode' and f.comp_result = '0' then 'RETURN_CODE'
        else 'FIELD_DIFF'
    end as sample_type,
    f.dest_trcd,
    split_part(f.dest_trcd, '&', 1) as service_code,
    split_part(f.dest_trcd, '&', 2) as message_type,
    c.tran_code,
    t.comp_result,
    m.sop_field_name,
    m.soap_field_name,
    m.bizjson_field_name,
    m.field_cn_name,
    f.orig_field_value,
    f.dest_field_value,
    f.mesg_seq,
    f.conv_index,
    f.conv_cindex,
    f.field_index,
    c.owner
from tss_tran_comp t
join tss_field_comp f
  on f.mesg_seq = t.mesg_seq
 and f.conv_index = t.conv_index
 and f.conv_cindex = t.conv_cindex
join ana_tran_catalog c
  on c.service_code = split_part(f.dest_trcd, '&', 1)
join ana_field_mapping m
  on m.tran_code = c.tran_code
 and m.service_code = c.service_code
 and m.sop_field_name = f.orig_field_name
 and m.bizjson_field_name = f.dest_field_name
where (
        f.orig_field_name = 'returnCode'
        and f.comp_result = '0'
      )
   or (
        t.comp_result = '4'
        and f.comp_result = '0'
        and f.orig_field_name <> 'returnCode'
      );

create index idx_tmp_ana_sample_candidates_group
on tmp_ana_sample_candidates(sample_type, tran_code, service_code, sop_field_name, mesg_seq);

insert into ana_sample_group (
    batch_id,
    sample_type,
    group_key,
    dest_trcd,
    service_code,
    message_type,
    tran_code,
    comp_result,
    sop_field_name,
    soap_field_name,
    bizjson_field_name,
    field_cn_name,
    owner,
    affected_count,
    sample_count
)
select
    'BATCH_20260608_SEED',
    sample_type,
    'BATCH_20260608_SEED|' || sample_type || '|' || tran_code || '|' || service_code || '|' || sop_field_name,
    min(dest_trcd),
    service_code,
    min(message_type),
    tran_code,
    case when sample_type = 'FIELD_DIFF' then '4' else min(comp_result) end,
    sop_field_name,
    min(soap_field_name),
    min(bizjson_field_name),
    min(field_cn_name),
    min(owner),
    count(*),
    least(count(*), 100)
from tmp_ana_sample_candidates
group by sample_type, tran_code, service_code, sop_field_name;

insert into ana_sample_detail (
    group_id,
    batch_id,
    sample_type,
    sample_seq_no,
    dest_trcd,
    service_code,
    message_type,
    tran_code,
    comp_result,
    sop_field_name,
    soap_field_name,
    bizjson_field_name,
    field_cn_name,
    orig_field_value,
    dest_field_value,
    tran_seq_no,
    owner,
    affected_count,
    source_table,
    source_pk
)
with ranked as (
    select
        g.group_id,
        c.*,
        g.affected_count,
        row_number() over (
            partition by g.group_id
            order by c.mesg_seq, c.field_index
        ) as rn
    from tmp_ana_sample_candidates c
    join ana_sample_group g
      on g.batch_id = 'BATCH_20260608_SEED'
     and g.sample_type = c.sample_type
     and g.tran_code = c.tran_code
     and g.service_code = c.service_code
     and g.sop_field_name = c.sop_field_name
)
select
    group_id,
    'BATCH_20260608_SEED',
    sample_type,
    rn,
    dest_trcd,
    service_code,
    message_type,
    tran_code,
    comp_result,
    sop_field_name,
    soap_field_name,
    bizjson_field_name,
    field_cn_name,
    orig_field_value,
    dest_field_value,
    mesg_seq,
    owner,
    affected_count,
    'tss_field_comp',
    mesg_seq || ':' || conv_index::text || ':' || conv_cindex::text || ':' || field_index::text || ':' || sop_field_name
from ranked
where rn <= 100;

drop table if exists tmp_ana_sample_candidates;
