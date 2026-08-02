-- 在 GaussDB 执行本脚本后，交易级差异问题跟踪导出功能即可写入导出流水。
-- 可重复执行；不会删除或修改已有导出流水。

create table if not exists ana_tran_diff_tracking_export (
    export_id bigserial primary key,
    export_timestamp timestamp not null,
    source_batch_id varchar(64) not null,
    business_date varchar(8) not null,
    row_no bigint not null,
    service_code varchar(200) not null,
    orig_error_code varchar(64),
    dest_error_code varchar(64),
    tran_code varchar(32),
    tran_name varchar(200),
    module_name varchar(100),
    orig_error_desc varchar(500),
    dest_error_desc varchar(500),
    transaction_owner varchar(100),
    tran_seq_no varchar(64),
    problem_level varchar(100),
    registration_date varchar(8),
    field_name varchar(500),
    problem_description text,
    problem_type varchar(100),
    preliminary_analysis text,
    final_solution text,
    resolution_date varchar(8),
    coordination_required varchar(100),
    resolver varchar(100),
    defect_fix_date varchar(8),
    issue_id bigint,
    issue_key varchar(600),
    affected_tran_count bigint not null default 0,
    historical_occurrence_count bigint not null default 0,
    first_seen_date date,
    previous_seen_date date,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

alter table ana_tran_diff_tracking_export add column if not exists issue_id bigint;
alter table ana_tran_diff_tracking_export add column if not exists issue_key varchar(600);
alter table ana_tran_diff_tracking_export add column if not exists affected_tran_count bigint not null default 0;
alter table ana_tran_diff_tracking_export add column if not exists historical_occurrence_count bigint not null default 0;
alter table ana_tran_diff_tracking_export add column if not exists first_seen_date date;
alter table ana_tran_diff_tracking_export add column if not exists previous_seen_date date;

comment on table ana_tran_diff_tracking_export is '交易级差异问题跟踪导出表';
comment on column ana_tran_diff_tracking_export.export_id is '导出记录ID';
comment on column ana_tran_diff_tracking_export.export_timestamp is '导出时间';
comment on column ana_tran_diff_tracking_export.source_batch_id is '来源批次号';
comment on column ana_tran_diff_tracking_export.business_date is '业务日期';
comment on column ana_tran_diff_tracking_export.row_no is '导出行号';
comment on column ana_tran_diff_tracking_export.service_code is '服务码';
comment on column ana_tran_diff_tracking_export.orig_error_code is '528响应码';
comment on column ana_tran_diff_tracking_export.dest_error_code is 'CCBS响应码';
comment on column ana_tran_diff_tracking_export.tran_code is '交易码';
comment on column ana_tran_diff_tracking_export.tran_name is '交易名称';
comment on column ana_tran_diff_tracking_export.module_name is '组别';
comment on column ana_tran_diff_tracking_export.orig_error_desc is '528响应描述';
comment on column ana_tran_diff_tracking_export.dest_error_desc is 'CCBS响应描述';
comment on column ana_tran_diff_tracking_export.transaction_owner is '交易负责人';
comment on column ana_tran_diff_tracking_export.tran_seq_no is '流水号';
comment on column ana_tran_diff_tracking_export.problem_level is '问题级别';
comment on column ana_tran_diff_tracking_export.registration_date is '登记日期';
comment on column ana_tran_diff_tracking_export.field_name is '字段名';
comment on column ana_tran_diff_tracking_export.problem_description is '问题描述';
comment on column ana_tran_diff_tracking_export.problem_type is '问题类型';
comment on column ana_tran_diff_tracking_export.preliminary_analysis is '初步问题分析';
comment on column ana_tran_diff_tracking_export.final_solution is '最终处理方案';
comment on column ana_tran_diff_tracking_export.resolution_date is '解决日期';
comment on column ana_tran_diff_tracking_export.coordination_required is '需协调';
comment on column ana_tran_diff_tracking_export.resolver is '解决人员';
comment on column ana_tran_diff_tracking_export.defect_fix_date is '缺陷修复日期';
comment on column ana_tran_diff_tracking_export.issue_id is '统一问题台账ID快照';
comment on column ana_tran_diff_tracking_export.issue_key is '稳定业务键快照';
comment on column ana_tran_diff_tracking_export.affected_tran_count is '该问题出现在的交易笔数';
comment on column ana_tran_diff_tracking_export.historical_occurrence_count is '本批次前历史出现批次数';
comment on column ana_tran_diff_tracking_export.first_seen_date is '问题首次出现日期快照';
comment on column ana_tran_diff_tracking_export.previous_seen_date is '本次前最近出现日期快照';
comment on column ana_tran_diff_tracking_export.created_at is '创建时间';
comment on column ana_tran_diff_tracking_export.updated_at is '更新时间';

create unique index if not exists uk_ana_tran_diff_tracking_export_batch_issue
on ana_tran_diff_tracking_export(source_batch_id, service_code, orig_error_code, dest_error_code);

create index if not exists idx_ana_tran_diff_tracking_export_time
on ana_tran_diff_tracking_export(export_timestamp desc);
