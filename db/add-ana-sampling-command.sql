-- Sampling command batch table for asynchronous execution.
\set ON_ERROR_STOP on

create table if not exists ana_sampling_command (
    command_id bigserial primary key,
    batch_id varchar(64) not null,
    orig_cdate varchar(8) not null,
    sample_type varchar(32),
    tran_code varchar(32),
    service_code varchar(200),
    status varchar(32) not null default 'CREATED',
    job_execution_id bigint,
    total_tran_count bigint not null default 0,
    field_diff_count bigint not null default 0,
    sample_group_count bigint not null default 0,
    sample_detail_count bigint not null default 0,
    error_message varchar(2000),
    remark varchar(1000),
    created_by varchar(100),
    created_time timestamp not null default current_timestamp,
    started_time timestamp,
    ended_time timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_sampling_command_batch unique (batch_id),
    constraint ck_ana_sampling_command_status check (status in ('CREATED','RUNNING','COMPLETED','FAILED','STOPPING','STOPPED'))
);

alter table ana_sampling_command drop column if exists partition_count;

comment on table ana_sampling_command is '采样指令批次表';
comment on column ana_sampling_command.command_id is '采样指令ID';
comment on column ana_sampling_command.batch_id is '采样批次号';
comment on column ana_sampling_command.orig_cdate is '原始回放日期，格式yyyyMMdd';
comment on column ana_sampling_command.sample_type is '采样类型，RETURN_CODE或FIELD_DIFF，空表示全部';
comment on column ana_sampling_command.tran_code is '四位交易码过滤条件';
comment on column ana_sampling_command.service_code is '服务码过滤条件';
comment on column ana_sampling_command.status is '批次状态';
comment on column ana_sampling_command.job_execution_id is '后台执行ID';
comment on column ana_sampling_command.total_tran_count is '交易总数';
comment on column ana_sampling_command.field_diff_count is '字段差异总数';
comment on column ana_sampling_command.sample_group_count is '采样分组数';
comment on column ana_sampling_command.sample_detail_count is '采样明细数';
comment on column ana_sampling_command.error_message is '失败原因';
comment on column ana_sampling_command.remark is '备注';
comment on column ana_sampling_command.created_by is '创建人';
comment on column ana_sampling_command.created_time is '创建时间';
comment on column ana_sampling_command.started_time is '开始时间';
comment on column ana_sampling_command.ended_time is '结束时间';
comment on column ana_sampling_command.updated_at is '更新时间';

drop trigger if exists trg_ana_sampling_command_updated_at on ana_sampling_command;
create trigger trg_ana_sampling_command_updated_at
before update on ana_sampling_command
for each row execute function ana_set_updated_at();

create index if not exists idx_ana_sampling_command_history
on ana_sampling_command(orig_cdate desc, created_time desc);

create index if not exists idx_ana_sampling_command_status
on ana_sampling_command(status, created_time desc);
