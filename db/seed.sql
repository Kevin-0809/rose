-- Rose replay management seed/test DML.
-- Run after db/ddl.sql. This file is re-runnable for TEST_SEED_* and BATCH_20260608_SEED data.
\set ON_ERROR_STOP on

-- 001_seed_ana_tran_catalog.sql
delete from ana_tran_catalog
where remark = 'TEST_SEED_1000_TRAN_CATALOG';

insert into ana_tran_catalog (
    tran_code,
    service_code,
    tran_name,
    module_name,
    owner,
    importance_level,
    is_key_tran,
    remark
)
with recursive seq(n) as (
    select 1
    union all
    select n + 1 from seq where n < 1000
)
select
    'A' || lpad((n - 1)::text, 3, '0') as tran_code,
    'S' || lpad(n::text, 9, '0') || 'TestTran' || lpad(n::text, 4, '0') as service_code,
    '测试交易' || lpad(n::text, 4, '0') as tran_name,
    case (n - 1) % 4
        when 0 then 'loan'
        when 1 then 'sett'
        when 2 then 'dept'
        else 'comm'
    end as module_name,
    case (n - 1) % 20
        when 0 then '张伟'
        when 1 then '王芳'
        when 2 then '李娜'
        when 3 then '刘洋'
        when 4 then '陈敏'
        when 5 then '杨磊'
        when 6 then '赵静'
        when 7 then '黄强'
        when 8 then '周杰'
        when 9 then '吴婷'
        when 10 then '徐明'
        when 11 then '孙丽'
        when 12 then '胡斌'
        when 13 then '朱燕'
        when 14 then '高峰'
        when 15 then '林雪'
        when 16 then '何涛'
        when 17 then '郭梅'
        when 18 then '马超'
        else '罗欣'
    end as owner,
    case
        when n % 10 = 0 then 'P0'
        when n % 5 = 0 then 'P1'
        when n % 2 = 0 then 'P2'
        else 'P3'
    end as importance_level,
    case when n % 10 = 0 then 'true' else 'false' end as is_key_tran,
    'TEST_SEED_1000_TRAN_CATALOG' as remark
from seq;

delete from ana_tran_catalog
where remark = 'TEST_SEED_A825_SEMANTIC_MAPPING';

insert into ana_tran_catalog (
    tran_code,
    service_code,
    tran_name,
    module_name,
    owner,
    importance_level,
    is_key_tran,
    remark
) values (
    'A825',
    'S030030014FcyCollCrspBnkLkgQry',
    '外币托收代理行联动查询',
    'loan',
    '张伟',
    'P1',
    'true',
    'TEST_SEED_A825_SEMANTIC_MAPPING'
);

-- 002_seed_ana_field_mapping.sql
delete from ana_field_mapping
where remark = 'TEST_SEED_RANDOM_FIELD_MAPPING';

delete from ana_field_mapping
where remark = 'TEST_SEED_A825_SEMANTIC_MAPPING';

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

insert into ana_field_mapping (
    tran_code,
    service_code,
    std_field_name,
    field_cn_name,
    sop_field_name,
    soap_field_name,
    bizjson_field_name,
    remark
) values
(
    'A825',
    'S030030014FcyCollCrspBnkLkgQry',
    'currency_id',
    '币种',
    'HUOBDH',
    'CurrencyId',
    'CurrencyId',
    'TEST_SEED_A825_SEMANTIC_MAPPING'
),
(
    'A825',
    'S030030014FcyCollCrspBnkLkgQry',
    'link_info',
    '联动信息',
    'FAB251',
    'FcyCollCrspBnkLkg',
    'FcyCollCrspBnkLkg',
    'TEST_SEED_A825_SEMANTIC_MAPPING'
);

-- 003_seed_tss_comp_test_data.sql
-- Re-runnable raw comparison data seed.
-- mesg_seq format: ST + 4-char tran_code + 7-digit sequence.

delete from tss_field_comp
where mesg_seq like 'ST%';

delete from tss_retcode_comp
where remark = 'TEST_SEED_RETCODE_COMP';

delete from tss_tran_comp
where mesg_seq like 'ST%';

insert into tss_tran_comp (
    mesg_seq,
    orig_cdate,
    conv_index,
    conv_cindex,
    comp_date,
    dest_trcd,
    orig_tran_res,
    dest_tran_res,
    comp_result
)
with recursive seq(n) as (
    select 1
    union all
    select n + 1 from seq where n < 10000
),
tran_seed as (
    select
        c.tran_code,
        c.service_code,
        cast(substr(c.tran_code, 2, 3) as integer) as tran_no,
        count(m.std_field_name) as mapping_field_count
    from ana_tran_catalog c
    join ana_field_mapping m
      on m.tran_code = c.tran_code
     and m.service_code = c.service_code
     and m.remark = 'TEST_SEED_RANDOM_FIELD_MAPPING'
    where c.remark = 'TEST_SEED_1000_TRAN_CATALOG'
    group by c.tran_code, c.service_code
),
tran_count_seed as (
    select
        tran_code,
        service_code,
        tran_no,
        mapping_field_count,
        case
            when tran_no = 0 then 50
            when tran_no = 999 then 10000
            when tran_no % 20 = 0 then 5000 + ((tran_no * 37 + 17) % 5001)
            when tran_no % 5 = 0 then 1000 + ((tran_no * 53 + 29) % 2001)
            else 50 + ((tran_no * 97 + 31) % 451)
        end as tran_count
    from tran_seed
),
seed_rows as (
    select
        'ST' || t.tran_code || lpad(s.n::text, 7, '0') as mesg_seq,
        '20260608' as orig_cdate,
        (s.n % 5) + 1 as conv_index,
        (s.n % 3) as conv_cindex,
        '20260608' as comp_date,
        t.service_code || '&bizjson' as dest_trcd,
        case s.n % 5
            when 0 then '1'
            when 1 then '0'
            when 2 then '1'
            when 3 then '0'
            else '0'
        end as orig_tran_res,
        case s.n % 5
            when 0 then '0'
            when 1 then '1'
            when 2 then '1'
            when 3 then '0'
            else '0'
        end as dest_tran_res,
        case s.n % 5
            when 0 then '1'
            when 1 then '2'
            when 2 then '3'
            when 3 then '4'
            else '8'
        end as comp_result
    from tran_count_seed t
    join seq s on s.n <= t.tran_count
)
select
    mesg_seq,
    orig_cdate,
    conv_index,
    conv_cindex,
    comp_date,
    dest_trcd,
    orig_tran_res,
    dest_tran_res,
    comp_result
from seed_rows;

insert into tss_field_comp (
    mesg_seq,
    orig_cdate,
    dest_trcd,
    conv_index,
    conv_cindex,
    redo_index,
    field_index,
    field_file_flag,
    orig_field_name,
    orig_field_value,
    dest_field_name,
    dest_field_value,
    comp_result
)
with recursive seq(n) as (
    select 1
    union all
    select n + 1 from seq where n < 10000
),
success_field_pos(pos) as (
    select 1
    union all
    select pos + 1 from success_field_pos where pos < 3
),
tran_seed as (
    select
        c.tran_code,
        c.service_code,
        cast(substr(c.tran_code, 2, 3) as integer) as tran_no,
        count(m.std_field_name) as mapping_field_count
    from ana_tran_catalog c
    join ana_field_mapping m
      on m.tran_code = c.tran_code
     and m.service_code = c.service_code
     and m.remark = 'TEST_SEED_RANDOM_FIELD_MAPPING'
    where c.remark = 'TEST_SEED_1000_TRAN_CATALOG'
    group by c.tran_code, c.service_code
),
tran_count_seed as (
    select
        tran_code,
        service_code,
        tran_no,
        mapping_field_count,
        case
            when tran_no = 0 then 50
            when tran_no = 999 then 10000
            when tran_no % 20 = 0 then 5000 + ((tran_no * 37 + 17) % 5001)
            when tran_no % 5 = 0 then 1000 + ((tran_no * 53 + 29) % 2001)
            else 50 + ((tran_no * 97 + 31) % 451)
        end as tran_count
    from tran_seed
),
seed_rows as (
    select
        'ST' || t.tran_code || lpad(s.n::text, 7, '0') as mesg_seq,
        '20260608' as orig_cdate,
        (s.n % 5) + 1 as conv_index,
        (s.n % 3) as conv_cindex,
        t.service_code || '&bizjson' as dest_trcd,
        t.tran_code,
        case s.n % 5
            when 0 then '1'
            when 1 then '0'
            when 2 then '1'
            when 3 then '0'
            else '0'
        end as orig_tran_res,
        case s.n % 5
            when 0 then '0'
            when 1 then '1'
            when 2 then '1'
            when 3 then '0'
            else '0'
        end as dest_tran_res,
        case s.n % 5
            when 0 then '1'
            when 1 then '2'
            when 2 then '3'
            when 3 then '4'
            else '8'
        end as comp_result,
        greatest(t.mapping_field_count - 1, 0) as normal_field_count,
        case when (s.n % 10) = 0 then 3 else 2 end as success_field_row_count,
        s.n as tran_seq
    from tran_count_seed t
    join seq s on s.n <= t.tran_count
),
success_normal_field_rows as (
    select
        r.mesg_seq,
        r.orig_cdate,
        r.dest_trcd,
        r.conv_index,
        r.conv_cindex,
        r.tran_code,
        r.tran_seq,
        p.pos + 1 as field_index,
        (((r.tran_seq + p.pos * 7) % r.normal_field_count) + 1) as mapping_pos,
        case when ((r.tran_seq + p.pos) % 10) = 0 then '0' else '1' end as field_comp_result
    from seed_rows r
    join success_field_pos p
      on r.comp_result = '4'
     and r.normal_field_count > 0
     and p.pos <= r.success_field_row_count
)
    select
        f.mesg_seq,
        f.orig_cdate,
        f.dest_trcd,
        f.conv_index,
        f.conv_cindex,
        0 as redo_index,
        f.field_index,
        'BODY' as field_file_flag,
        m.sop_field_name as orig_field_name,
        case
            when f.field_comp_result = '1'
                then 'VAL-' || f.tran_code || '-' || m.sop_field_name || '-' || (f.tran_seq % 100)::text
            else 'ORIG-' || f.tran_code || '-' || m.sop_field_name || '-' || (f.tran_seq % 100)::text
        end as orig_field_value,
        m.bizjson_field_name as dest_field_name,
        case
            when f.field_comp_result = '1'
                then 'VAL-' || f.tran_code || '-' || m.sop_field_name || '-' || (f.tran_seq % 100)::text
            else 'DEST-' || f.tran_code || '-' || m.bizjson_field_name || '-' || ((f.tran_seq + f.field_index) % 100)::text
        end as dest_field_value,
        f.field_comp_result
    from success_normal_field_rows f
    join ana_field_mapping m
      on m.tran_code = f.tran_code
     and m.service_code = split_part(f.dest_trcd, '&', 1)
     and m.remark = 'TEST_SEED_RANDOM_FIELD_MAPPING'
     and m.std_field_name = 'f' || lpad(f.mapping_pos::text, 3, '0')
    where f.field_comp_result = '0';

-- 004_seed_tss_retcode_comp.sql
delete from tss_retcode_comp
where remark = 'TEST_SEED_RETCODE_COMP';

insert into tss_retcode_comp (
    mesg_seq,
    service_code,
    orig_cdate,
    orig_error_code,
    orig_error_desc,
    dest_error_code,
    dest_error_desc,
    remark
)
select
    t.mesg_seq,
    t.dest_trcd as service_code,
    t.orig_cdate,
    'E528' || lpad(((row_number() over (order by t.mesg_seq) % 1000000) + 1)::text, 6, '0') as orig_error_code,
    case
        when (row_number() over (order by t.mesg_seq) % 4) = 0 then '528账户状态异常'
        when (row_number() over (order by t.mesg_seq) % 4) = 1 then '528客户信息不存在'
        when (row_number() over (order by t.mesg_seq) % 4) = 2 then '528交易金额超限'
        else '528业务规则校验失败'
    end as orig_error_desc,
    'ECCBS' || lpad((((row_number() over (order by t.mesg_seq) + 7000) % 1000000) + 1)::text, 5, '0') as dest_error_code,
    case
        when (row_number() over (order by t.mesg_seq) % 4) = 0 then 'CCBS账户状态异常'
        when (row_number() over (order by t.mesg_seq) % 4) = 1 then 'CCBS客户资料缺失'
        when (row_number() over (order by t.mesg_seq) % 4) = 2 then 'CCBS额度校验失败'
        else 'CCBS核心交易拒绝'
    end as dest_error_desc,
    'TEST_SEED_RETCODE_COMP' as remark
from tss_tran_comp t
where t.mesg_seq like 'ST%'
  and t.comp_result <> '4';

-- 005_seed_ana_samples_from_tss.sql
delete from ana_sample_detail
where batch_id = 'BATCH_20260608_SEED';

delete from ana_sample_group
where batch_id = 'BATCH_20260608_SEED';

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
with candidates as (
    select
        'RETURN_CODE' as sample_type,
        r.service_code as dest_trcd,
        split_part(r.service_code, '&', 1) as service_code,
        split_part(r.service_code, '&', 2) as message_type,
        c.tran_code,
        t.comp_result,
        'returnCode' as sop_field_name,
        'returnCode' as soap_field_name,
        'returnCode' as bizjson_field_name,
        '响应码' as field_cn_name,
        c.owner
    from tss_retcode_comp r
    join tss_tran_comp t
      on t.mesg_seq = r.mesg_seq
    join ana_tran_catalog c
      on c.service_code = split_part(r.service_code, '&', 1)
    where r.remark = 'TEST_SEED_RETCODE_COMP'
      and t.comp_result <> '4'
    union all
    select
        'FIELD_DIFF' as sample_type,
        f.dest_trcd,
        split_part(f.dest_trcd, '&', 1) as service_code,
        split_part(f.dest_trcd, '&', 2) as message_type,
        c.tran_code,
        t.comp_result,
        coalesce(m.sop_field_name, f.orig_field_name),
        coalesce(m.soap_field_name, f.dest_field_name),
        coalesce(m.bizjson_field_name, f.dest_field_name),
        m.field_cn_name,
        c.owner
    from tss_tran_comp t
    join tss_field_comp f
      on f.mesg_seq = t.mesg_seq
    join ana_tran_catalog c
      on c.service_code = split_part(f.dest_trcd, '&', 1)
    left join ana_field_mapping m
      on m.tran_code = c.tran_code
     and m.service_code = c.service_code
     and m.sop_field_name = f.orig_field_name
    where t.comp_result = '4'
      and f.comp_result = '0'
)
select
    'BATCH_20260608_SEED',
    sample_type,
    'BATCH_20260608_SEED|' || sample_type || '|' || tran_code || '|' || service_code || '|' ||
        comp_result || '|' || sop_field_name,
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
    least(count(*), case when sample_type = 'RETURN_CODE' then 1 else 10 end)
from candidates
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
with candidates as (
    select
        'RETURN_CODE' as sample_type,
        r.service_code as dest_trcd,
        split_part(r.service_code, '&', 1) as service_code,
        split_part(r.service_code, '&', 2) as message_type,
        c.tran_code,
        t.comp_result,
        'returnCode' as sop_field_name,
        'returnCode' as soap_field_name,
        'returnCode' as bizjson_field_name,
        '响应码' as field_cn_name,
        r.orig_error_code as orig_field_value,
        r.dest_error_code as dest_field_value,
        r.mesg_seq,
        t.conv_index,
        t.conv_cindex,
        1 as field_index,
        c.owner
    from tss_retcode_comp r
    join tss_tran_comp t
      on t.mesg_seq = r.mesg_seq
    join ana_tran_catalog c
      on c.service_code = split_part(r.service_code, '&', 1)
    where r.remark = 'TEST_SEED_RETCODE_COMP'
      and t.comp_result <> '4'
    union all
    select
        'FIELD_DIFF' as sample_type,
        f.dest_trcd,
        split_part(f.dest_trcd, '&', 1) as service_code,
        split_part(f.dest_trcd, '&', 2) as message_type,
        c.tran_code,
        t.comp_result,
        coalesce(m.sop_field_name, f.orig_field_name),
        coalesce(m.soap_field_name, f.dest_field_name),
        coalesce(m.bizjson_field_name, f.dest_field_name),
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
    join ana_tran_catalog c
      on c.service_code = split_part(f.dest_trcd, '&', 1)
    left join ana_field_mapping m
      on m.tran_code = c.tran_code
     and m.service_code = c.service_code
     and m.sop_field_name = f.orig_field_name
    where t.comp_result = '4'
      and f.comp_result = '0'
),
ranked as (
    select
        g.group_id,
        c.*,
        g.affected_count,
        row_number() over (
            partition by g.group_id
            order by c.mesg_seq, c.field_index
        ) as rn
    from candidates c
    join ana_sample_group g
      on g.batch_id = 'BATCH_20260608_SEED'
     and g.sample_type = c.sample_type
     and g.tran_code = c.tran_code
     and g.service_code = c.service_code
     and g.comp_result = c.comp_result
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
    case when sample_type = 'RETURN_CODE' then 'tss_retcode_comp' else 'tss_field_comp' end,
    case
        when sample_type = 'RETURN_CODE' then mesg_seq
        else mesg_seq || ':' || conv_index::text || ':' || conv_cindex::text || ':' || field_index::text || ':' || sop_field_name
    end
from ranked
where rn <= case when sample_type = 'RETURN_CODE' then 1 else 10 end;
