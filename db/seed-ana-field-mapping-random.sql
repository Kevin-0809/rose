\set ON_ERROR_STOP on

delete from ana_field_mapping
where remark = 'TEST_SEED_RANDOM_FIELD_MAPPING';

insert into ana_field_mapping (
    tran_code,
    service_code,
    std_field_name,
    field_cn_name,
    sop_field_name,
    soap_field_name,
    bizjson_field_name,
    remark
)
with recursive field_seq(field_no) as (
    select 1
    union all
    select field_no + 1 from field_seq where field_no < 49
),
tran_seed as (
    select
        tran_code,
        service_code,
        ((ascii(substr(tran_code, 2, 1)) * 7
            + ascii(substr(tran_code, 3, 1)) * 11
            + ascii(substr(tran_code, 4, 1)) * 13) % 50 + 1) as field_count
    from ana_tran_catalog
    where remark = 'TEST_SEED_1000_TRAN_CATALOG'
)
select
    t.tran_code,
    t.service_code,
    'returnCode' as std_field_name,
    '返回码' as field_cn_name,
    'returnCode' as sop_field_name,
    'ReturnCode' as soap_field_name,
    'returnCode' as bizjson_field_name,
    'TEST_SEED_RANDOM_FIELD_MAPPING' as remark
from tran_seed t
union all
select
    t.tran_code,
    t.service_code,
    'f' || lpad(f.field_no::text, 3, '0') as std_field_name,
    '测试字段' || lpad(f.field_no::text, 3, '0') as field_cn_name,
    'f' || lpad(f.field_no::text, 3, '0') as sop_field_name,
    'Field' || lpad(f.field_no::text, 3, '0') as soap_field_name,
    'field' || lpad(f.field_no::text, 3, '0') as bizjson_field_name,
    'TEST_SEED_RANDOM_FIELD_MAPPING' as remark
from tran_seed t
join field_seq f on f.field_no < t.field_count;
