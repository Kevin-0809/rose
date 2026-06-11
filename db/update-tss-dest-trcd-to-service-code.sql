\set ON_ERROR_STOP on

alter table tss_tran_comp alter column dest_trcd type varchar(200);
alter table tss_field_comp alter column dest_trcd type varchar(200);

update ana_tran_catalog
set service_code = split_part(service_code, '&', 1)
where remark = 'TEST_SEED_1000_TRAN_CATALOG'
  and service_code like '%&%';

update ana_field_mapping
set service_code = split_part(service_code, '&', 1)
where remark = 'TEST_SEED_RANDOM_FIELD_MAPPING'
  and service_code like '%&%';

update tss_tran_comp t
set dest_trcd = c.service_code || '&bizjson'
from ana_tran_catalog c
where t.dest_trcd = c.tran_code
  and c.remark = 'TEST_SEED_1000_TRAN_CATALOG';

update tss_field_comp f
set dest_trcd = c.service_code || '&bizjson'
from ana_tran_catalog c
where f.dest_trcd = c.tran_code
  and c.remark = 'TEST_SEED_1000_TRAN_CATALOG';

update tss_tran_comp t
set dest_trcd = split_part(t.dest_trcd, '&', 1) || '&bizjson'
where t.dest_trcd like '%&%'
  and split_part(t.dest_trcd, '&', 2) <> 'bizjson';

update tss_field_comp f
set dest_trcd = split_part(f.dest_trcd, '&', 1) || '&bizjson'
where f.dest_trcd like '%&%'
  and split_part(f.dest_trcd, '&', 2) <> 'bizjson';
