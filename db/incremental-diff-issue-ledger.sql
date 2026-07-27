-- 增量脚本：差异问题台账与当前批次历史快照字段
-- 适用范围：
-- 1. 新增统一差异问题台账 ana_diff_issue。
-- 2. 为 ana_tran_diff_tracking_export、ana_field_diff_tracking_export 增加问题台账快照字段。
-- 3. 增加相关索引和中文注释。
-- 说明：本脚本不创建外键，tracking 表仅保存 issue_id 快照值。

create table if not exists ana_diff_issue (
    issue_id bigserial primary key,
    issue_key varchar(600) not null unique,
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
    issue_status varchar(16) not null default 'OPEN',
    coordination_required varchar(100),
    resolver varchar(100),
    resolution_date date,
    defect_fix_date date,
    first_seen_date date not null,
    last_seen_date date not null,
    first_seen_batch_id varchar(64) not null,
    last_seen_batch_id varchar(64) not null,
    occurrence_batch_count bigint not null default 1,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_ana_diff_issue_level check (issue_level in ('TRANSACTION','FIELD')),
    constraint ck_ana_diff_issue_status check (issue_status in ('OPEN','RESOLVED','IGNORED')),
    constraint ck_ana_diff_issue_level_detail check (
        (issue_level = 'FIELD' and normalized_source_field_name is not null
            and btrim(normalized_source_field_name) <> ''
            and orig_error_code is null and dest_error_code is null)
        or (issue_level = 'TRANSACTION' and normalized_source_field_name is null)
    )
);

comment on table ana_diff_issue is '统一差异问题台账表';
comment on column ana_diff_issue.issue_id is '问题台账ID';
comment on column ana_diff_issue.issue_key is '问题唯一键';
comment on column ana_diff_issue.issue_level is '问题级别，交易级或字段级';
comment on column ana_diff_issue.service_code is '服务码';
comment on column ana_diff_issue.tran_code is '交易码';
comment on column ana_diff_issue.tran_name is '交易名称';
comment on column ana_diff_issue.module_name is '所属模块';
comment on column ana_diff_issue.transaction_owner is '交易负责人';
comment on column ana_diff_issue.orig_error_code is '528响应码';
comment on column ana_diff_issue.dest_error_code is 'CCBS响应码';
comment on column ana_diff_issue.normalized_source_field_name is '标准化源字段名';
comment on column ana_diff_issue.problem_type is '问题类型';
comment on column ana_diff_issue.problem_description is '问题描述';
comment on column ana_diff_issue.preliminary_analysis is '初步分析';
comment on column ana_diff_issue.final_solution is '最终解决方案';
comment on column ana_diff_issue.issue_status is '问题状态';
comment on column ana_diff_issue.coordination_required is '是否需要协调';
comment on column ana_diff_issue.resolver is '解决人';
comment on column ana_diff_issue.resolution_date is '解决日期';
comment on column ana_diff_issue.defect_fix_date is '缺陷修复日期';
comment on column ana_diff_issue.first_seen_date is '首次发现日期';
comment on column ana_diff_issue.last_seen_date is '最后发现日期';
comment on column ana_diff_issue.first_seen_batch_id is '首次发现批次号';
comment on column ana_diff_issue.last_seen_batch_id is '最后发现批次号';
comment on column ana_diff_issue.occurrence_batch_count is '出现批次数';
comment on column ana_diff_issue.created_at is '创建时间';
comment on column ana_diff_issue.updated_at is '更新时间';

alter table ana_tran_diff_tracking_export add column if not exists issue_id bigint;
alter table ana_tran_diff_tracking_export add column if not exists issue_key varchar(600);
alter table ana_tran_diff_tracking_export add column if not exists historical_occurrence_count bigint not null default 0;
alter table ana_tran_diff_tracking_export add column if not exists first_seen_date date;
alter table ana_tran_diff_tracking_export add column if not exists previous_seen_date date;

comment on column ana_tran_diff_tracking_export.issue_id is '统一问题台账ID快照';
comment on column ana_tran_diff_tracking_export.issue_key is '稳定业务键快照';
comment on column ana_tran_diff_tracking_export.historical_occurrence_count is '本批次前历史出现批次数';
comment on column ana_tran_diff_tracking_export.first_seen_date is '问题首次出现日期快照';
comment on column ana_tran_diff_tracking_export.previous_seen_date is '本次前最近出现日期快照';

alter table ana_field_diff_tracking_export add column if not exists issue_id bigint;
alter table ana_field_diff_tracking_export add column if not exists issue_key varchar(600);
alter table ana_field_diff_tracking_export add column if not exists historical_occurrence_count bigint not null default 0;
alter table ana_field_diff_tracking_export add column if not exists first_seen_date date;
alter table ana_field_diff_tracking_export add column if not exists previous_seen_date date;

comment on column ana_field_diff_tracking_export.issue_id is '统一问题台账ID快照';
comment on column ana_field_diff_tracking_export.issue_key is '稳定业务键快照';
comment on column ana_field_diff_tracking_export.historical_occurrence_count is '本批次前历史出现批次数';
comment on column ana_field_diff_tracking_export.first_seen_date is '问题首次出现日期快照';
comment on column ana_field_diff_tracking_export.previous_seen_date is '本次前最近出现日期快照';

create index if not exists idx_ana_tran_diff_tracking_export_issue
on ana_tran_diff_tracking_export(issue_id);

create index if not exists idx_ana_field_diff_tracking_export_issue
on ana_field_diff_tracking_export(issue_id);

create index if not exists idx_ana_diff_issue_status_last_seen
on ana_diff_issue(issue_status, last_seen_date desc);

create index if not exists idx_ana_diff_issue_service_field
on ana_diff_issue(service_code, normalized_source_field_name);
