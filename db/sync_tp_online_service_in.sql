-- Repeatable patch: refresh tp_online_service_in from observed transaction comparison service codes.
-- Source:
--   tss_tran_comp.dest_trcd contains service code plus message type, for example:
--   S030030014FcyCollCrspBnkLkgQry&bzjson
-- Catalog:
--   ana_tran_catalog.service_code stores the base service code without message type.
-- Target:
--   tp_online_service_in.esf_service_code stores the ESF code with a dot after the service prefix:
--   S03003001.4FcyCollCrspBnkLkgQry

with observed_service as (
    select distinct split_part(trim(dest_trcd), '&', 1) as service_code
    from tss_tran_comp
    where position('&' in trim(dest_trcd)) > 0
      and dest_trcd is not null
      and trim(dest_trcd) <> ''
),
catalog_service as (
    select distinct
           c.tran_code,
           c.service_code,
           substring(c.service_code from 1 for 9) || '.' || substring(c.service_code from 10) as esf_service_code
    from observed_service o
    join ana_tran_catalog c
      on c.service_code = o.service_code
    where c.tran_code is not null
      and trim(c.tran_code) <> ''
      and c.service_code is not null
      and length(c.service_code) >= 9
)
delete from tp_online_service_in t
where exists (
    select 1
    from catalog_service c
    where t.tran_code = c.tran_code
      and replace(t.esf_service_code, '.', '') = c.service_code
      and t.esf_service_code <> c.esf_service_code
      and exists (
          select 1
          from tp_online_service_in existing
          where existing.tran_code = t.tran_code
            and existing.esf_service_code = c.esf_service_code
      )
  );

with observed_service as (
    select distinct split_part(trim(dest_trcd), '&', 1) as service_code
    from tss_tran_comp
    where position('&' in trim(dest_trcd)) > 0
      and dest_trcd is not null
      and trim(dest_trcd) <> ''
),
catalog_service as (
    select distinct
           c.tran_code,
           c.service_code,
           substring(c.service_code from 1 for 9) || '.' || substring(c.service_code from 10) as esf_service_code
    from observed_service o
    join ana_tran_catalog c
      on c.service_code = o.service_code
    where c.tran_code is not null
      and trim(c.tran_code) <> ''
      and c.service_code is not null
      and length(c.service_code) >= 9
)
update tp_online_service_in t
set esf_service_code = c.esf_service_code
from catalog_service c
where t.tran_code = c.tran_code
  and replace(t.esf_service_code, '.', '') = c.service_code;

with observed_service as (
    select distinct split_part(trim(dest_trcd), '&', 1) as service_code
    from tss_tran_comp
    where position('&' in trim(dest_trcd)) > 0
      and dest_trcd is not null
      and trim(dest_trcd) <> ''
),
catalog_service as (
    select distinct
           c.tran_code,
           c.service_code,
           substring(c.service_code from 1 for 9) || '.' || substring(c.service_code from 10) as esf_service_code
    from observed_service o
    join ana_tran_catalog c
      on c.service_code = o.service_code
    where c.tran_code is not null
      and trim(c.tran_code) <> ''
      and c.service_code is not null
      and length(c.service_code) >= 9
)
insert into tp_online_service_in (
    tran_code,
    esf_service_code
)
select
    c.tran_code,
    c.esf_service_code
from catalog_service c
where not exists (
    select 1
    from tp_online_service_in t
    where t.tran_code = c.tran_code
      and t.esf_service_code = c.esf_service_code
);
