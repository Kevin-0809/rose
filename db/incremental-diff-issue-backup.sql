-- Backup table for ana_diff_issue before report export batch materialization.
-- Safe to run repeatedly before using report export rollback by manual restore.

create table if not exists ana_diff_issue_backup (
    backup_batch_id varchar(64) not null,
    backup_time timestamp not null default current_timestamp,
    issue_id bigint not null,
    issue_key varchar(600) not null,
    issue_level varchar(16) not null,
    service_code varchar(200) not null,
    tran_code varchar(32),
    tran_name varchar(200),
    module_name varchar(100),
    transaction_owner varchar(100),
    orig_error_code varchar(64),
    dest_error_code varchar(64),
    normalized_source_field_name varchar(500),
    problem_type varchar(100),
    problem_description text,
    preliminary_analysis text,
    final_solution text,
    issue_status varchar(16) not null,
    coordination_required varchar(100),
    resolver varchar(100),
    resolution_date date,
    defect_fix_date date,
    first_seen_date date not null,
    last_seen_date date not null,
    first_seen_batch_id varchar(64) not null,
    last_seen_batch_id varchar(64) not null,
    occurrence_batch_count bigint not null,
    issue_created_at timestamp,
    issue_updated_at timestamp,
    constraint uk_ana_diff_issue_backup unique (backup_batch_id, issue_id)
);

comment on table ana_diff_issue_backup is '报表明细导出前问题台账备份表';
comment on column ana_diff_issue_backup.backup_batch_id is '触发备份的报表导出批次号';
comment on column ana_diff_issue_backup.backup_time is '备份时间';

