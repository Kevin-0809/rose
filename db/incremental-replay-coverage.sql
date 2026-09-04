-- 回放交易覆盖情况：按日报批次保存全量清单与实际发送量的反向对照结果
create table if not exists ana_report_export_replay_coverage (
    coverage_id bigserial primary key,
    batch_id varchar(64) not null,
    report_date varchar(8) not null,
    tran_code varchar(200) not null,
    tran_name varchar(200),
    business_domain varchar(200),
    new_service_scene_code varchar(200),
    resolved_service_codes varchar(1000),
    service_mapping_status varchar(32) not null,
    replay_required varchar(200),
    latest_transaction_date varchar(200),
    sent_transaction_count bigint not null default 0,
    coverage_status varchar(32) not null,
    unsent_reason varchar(200),
    owner varchar(100),
    internal_owner varchar(100),
    created_time timestamp not null default current_timestamp,
    constraint uk_ana_report_export_replay_coverage unique (batch_id, tran_code)
);

create index if not exists idx_ana_report_export_replay_coverage_batch
on ana_report_export_replay_coverage(batch_id, business_domain, tran_code);
