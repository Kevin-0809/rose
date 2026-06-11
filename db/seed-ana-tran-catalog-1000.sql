\set ON_ERROR_STOP on

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
