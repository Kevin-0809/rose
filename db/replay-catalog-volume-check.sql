-- DDL for the full replay transaction catalog and volume check workflow.
\set ON_ERROR_STOP on

create table if not exists ana_replay_transaction_catalog (
    tran_code varchar(200) primary key,
    tran_name varchar(200),
    business_domain varchar(200),
    batch_type varchar(200),
    new_core_tran_code varchar(200),
    new_tran_name varchar(200),
    replay_required varchar(200),
    original_service_scene_code varchar(200),
    new_service_scene_code varchar(200),
    latest_transaction_date varchar(200),
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp
);

create table if not exists ana_replay_volume_check_batch (
    check_id bigserial primary key,
    status varchar(32) not null default 'CHECKING',
    catalog_snapshot_time timestamp not null,
    sample_size integer not null default 100,
    lookback_days integer not null default 30,
    catalog_count bigint not null default 0,
    has_volume_count bigint not null default 0,
    no_volume_count bigint not null default 0,
    cleanup_service_count bigint not null default 0,
    cleanup_row_count bigint not null default 0,
    actual_cleanup_row_count bigint not null default 0,
    migration_command_id bigint,
    created_time timestamp not null default current_timestamp,
    started_time timestamp,
    ended_time timestamp,
    error_message varchar(4000),
    constraint ck_ana_replay_volume_check_batch_status
        check (status in ('CHECKING','WAITING_CONFIRM','EXECUTING','COMPLETED','FAILED')),
    constraint ck_ana_replay_volume_check_batch_sample_size
        check (sample_size > 0),
    constraint ck_ana_replay_volume_check_batch_lookback_days
        check (lookback_days > 0)
);

alter table ana_replay_volume_check_batch
    add column if not exists has_volume_count bigint not null default 0;

create table if not exists ana_replay_volume_check_detail (
    detail_id bigserial primary key,
    check_id bigint not null,
    tran_code varchar(200) not null,
    tran_name varchar(200),
    business_domain varchar(200),
    batch_type varchar(200),
    new_core_tran_code varchar(200),
    new_tran_name varchar(200),
    replay_required varchar(200),
    original_service_scene_code varchar(200),
    new_service_scene_code varchar(200),
    latest_transaction_date varchar(200),
    mapped_service_count bigint not null default 0,
    complete_volume_count bigint not null default 0,
    status varchar(32) not null default 'NO_MAPPING',
    migration_status varchar(32),
    error_message varchar(4000),
    created_time timestamp not null default current_timestamp,
    constraint uk_ana_replay_volume_check_detail_batch_tran unique (check_id, tran_code),
    constraint fk_ana_replay_volume_check_detail_batch
        foreign key (check_id) references ana_replay_volume_check_batch(check_id),
    constraint ck_ana_replay_volume_check_detail_status
        check (status in ('HAS_VOLUME','NO_VOLUME','NO_MAPPING','MIGRATION_STARTED','MIGRATION_COMPLETED','MIGRATION_FAILED')),
    constraint ck_ana_replay_volume_check_detail_migration_status
        check (migration_status is null or migration_status in ('MIGRATION_STARTED','MIGRATION_COMPLETED','MIGRATION_FAILED'))
);

alter table ana_replay_volume_check_detail add column if not exists new_core_tran_code varchar(200);
alter table ana_replay_volume_check_detail add column if not exists new_tran_name varchar(200);
alter table ana_replay_volume_check_detail add column if not exists replay_required varchar(200);
alter table ana_replay_volume_check_detail add column if not exists original_service_scene_code varchar(200);
alter table ana_replay_volume_check_detail add column if not exists new_service_scene_code varchar(200);
alter table ana_replay_volume_check_detail add column if not exists latest_transaction_date varchar(200);

create table if not exists ana_replay_volume_cleanup_detail (
    cleanup_id bigserial primary key,
    check_id bigint not null,
    service_code varchar(200) not null,
    mapped_tran_codes text not null default '',
    catalog_hit boolean not null default false,
    pending_cleanup_row_count bigint not null default 0,
    actual_cleanup_row_count bigint not null default 0,
    status varchar(32) not null default 'PENDING',
    error_message varchar(4000),
    created_time timestamp not null default current_timestamp,
    started_time timestamp,
    ended_time timestamp,
    constraint uk_ana_replay_volume_cleanup_detail_batch_service unique (check_id, service_code),
    constraint fk_ana_replay_volume_cleanup_detail_batch
        foreign key (check_id) references ana_replay_volume_check_batch(check_id),
    constraint ck_ana_replay_volume_cleanup_detail_status
        check (status in ('PENDING','EXECUTING','COMPLETED','FAILED')),
    constraint ck_ana_replay_volume_cleanup_detail_counts
        check (pending_cleanup_row_count >= 0 and actual_cleanup_row_count >= 0)
);

comment on table ana_replay_transaction_catalog is '全量回放交易清单';
comment on table ana_replay_volume_check_batch is '回放交易量检查批次';
comment on table ana_replay_volume_check_detail is '回放交易量检查交易明细';
comment on table ana_replay_volume_cleanup_detail is '回放交易量清理明细';

create index if not exists idx_ana_replay_transaction_catalog_query
on ana_replay_transaction_catalog(tran_code, tran_name, business_domain, batch_type, replay_required);

create index if not exists idx_ana_replay_volume_check_batch_status
on ana_replay_volume_check_batch(status, created_time desc);

create index if not exists idx_ana_replay_volume_check_detail_batch
on ana_replay_volume_check_detail(check_id, status, tran_code);

create index if not exists idx_ana_replay_volume_cleanup_detail_batch
on ana_replay_volume_cleanup_detail(check_id, status, service_code);
