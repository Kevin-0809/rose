
create table if not exists ana_tran_code_service_mapping (
    mapping_id bigserial primary key,
    tran_code varchar(32) not null,
    "528_service_code" varchar(200) not null,
    ccbs_service_code varchar(200) not null,
    remark varchar(1000),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_tran_code_service_mapping unique (tran_code, "528_service_code", ccbs_service_code)
);

comment on table ana_tran_code_service_mapping is '交易码528与CCBS服务码映射表';
comment on column ana_tran_code_service_mapping.mapping_id is '映射ID';
comment on column ana_tran_code_service_mapping.tran_code is '四位交易码';
comment on column ana_tran_code_service_mapping."528_service_code" is '528服务码，不含报文类型';
comment on column ana_tran_code_service_mapping.ccbs_service_code is 'CCBS服务码，不含报文类型';
comment on column ana_tran_code_service_mapping.remark is '备注';
comment on column ana_tran_code_service_mapping.created_at is '创建时间';
comment on column ana_tran_code_service_mapping.updated_at is '更新时间';

create index if not exists idx_ana_tran_code_service_mapping_tran
on ana_tran_code_service_mapping(tran_code);

alter table ana_migration_shard
add column if not exists actual_lookback_days integer;
