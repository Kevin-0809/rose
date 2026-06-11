\set ON_ERROR_STOP on

-- Re-runnable raw comparison data seed.
-- mesg_seq format: ST + 4-char tran_code + 7-digit sequence.

delete from tss_field_comp
where mesg_seq like 'ST%';

delete from tss_tran_comp
where mesg_seq like 'ST%';

drop table if exists tmp_seed_tss_tran_rows;

create temporary table tmp_seed_tss_tran_rows as
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
)
select
    'ST' || t.tran_code || lpad(s.n::text, 7, '0') as mesg_seq,
    '20260608' as orig_cdate,
    (s.n % 5) + 1 as conv_index,
    (s.n % 3) as conv_cindex,
    '20260608' as comp_date,
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
join seq s on s.n <= t.tran_count;

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
from tmp_seed_tss_tran_rows;

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
with recursive success_field_pos(pos) as (
    select 1
    union all
    select pos + 1 from success_field_pos where pos < 3
),
return_code_rows as (
    select
        r.*,
        1 as field_index,
        'returnCode' as orig_field_name,
        'returnCode' as dest_field_name,
        case
            when r.orig_tran_res = '0' then '00000000000'
            else 'E' || lpad(((r.tran_seq % 9000000000) + 1)::text, 10, '0')
        end as orig_field_value,
        case
            when r.dest_tran_res = '0' then '00000000000'
            else 'E' || lpad((((r.tran_seq + 7000000) % 9000000000) + 1)::text, 10, '0')
        end as dest_field_value,
        case when r.comp_result = '4' then '1' else '0' end as field_comp_result
    from tmp_seed_tss_tran_rows r
),
success_normal_field_rows as (
    select
        r.*,
        p.pos + 1 as field_index,
        (((r.tran_seq + p.pos * 7) % r.normal_field_count) + 1) as mapping_pos,
        case when ((r.tran_seq + p.pos) % 10) = 0 then '0' else '1' end as field_comp_result
    from tmp_seed_tss_tran_rows r
    join success_field_pos p
      on r.comp_result = '4'
     and r.normal_field_count > 0
     and p.pos <= r.success_field_row_count
),
all_field_rows as (
    select
        mesg_seq,
        orig_cdate,
        dest_trcd,
        conv_index,
        conv_cindex,
        tran_code,
        tran_seq,
        field_index,
        orig_field_name,
        orig_field_value,
        dest_field_name,
        dest_field_value,
        field_comp_result
    from return_code_rows
    union all
    select
        f.mesg_seq,
        f.orig_cdate,
        f.dest_trcd,
        f.conv_index,
        f.conv_cindex,
        f.tran_code,
        f.tran_seq,
        f.field_index,
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
    f.orig_field_name,
    f.orig_field_value,
    f.dest_field_name,
    f.dest_field_value,
    f.field_comp_result as comp_result
from all_field_rows f;

drop table if exists tmp_seed_tss_tran_rows;
