-- Batch sampling summary table and orig_cdate string migration.
\set ON_ERROR_STOP on

alter table ana_sampling_command alter column orig_cdate type varchar(8)
using replace(substr(orig_cdate::text, 1, 10), '-', '');

comment on column ana_sampling_command.orig_cdate is '原始回放日期，格式yyyyMMdd';

create table if not exists ana_sampling_summary (
    summary_id bigserial primary key,
    batch_id varchar(64) not null,
    orig_cdate varchar(8) not null,
    total_tran_count bigint not null default 0,
    comp_result_1_count bigint not null default 0,
    comp_result_2_count bigint not null default 0,
    comp_result_3_count bigint not null default 0,
    comp_result_4_count bigint not null default 0,
    comp_result_8_count bigint not null default 0,
    pass_tran_count bigint not null default 0,
    issue_field_count bigint not null default 0,
    fully_matched_count bigint not null default 0,
    sample_group_count bigint not null default 0,
    sample_detail_count bigint not null default 0,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_sampling_summary_batch unique (batch_id)
);

comment on table ana_sampling_summary is '采样批次统计表';
comment on column ana_sampling_summary.summary_id is '统计ID';
comment on column ana_sampling_summary.batch_id is '采样批次号';
comment on column ana_sampling_summary.orig_cdate is '原始回放日期，格式yyyyMMdd';
comment on column ana_sampling_summary.total_tran_count is '本次发起交易总笔数';
comment on column ana_sampling_summary.comp_result_1_count is '不符：原失败，新成功笔数';
comment on column ana_sampling_summary.comp_result_2_count is '不符：原成功，新失败笔数';
comment on column ana_sampling_summary.comp_result_3_count is '相符：都失败笔数';
comment on column ana_sampling_summary.comp_result_4_count is '相符：都成功笔数';
comment on column ana_sampling_summary.comp_result_8_count is '响应码不一致笔数';
comment on column ana_sampling_summary.pass_tran_count is '通过交易笔数';
comment on column ana_sampling_summary.issue_field_count is '出现问题字段数量，按交易流水和字段去重';
comment on column ana_sampling_summary.fully_matched_count is '完全匹配交易笔数，交易流水存在且字段表无不一致字段';
comment on column ana_sampling_summary.sample_group_count is '采样分组数';
comment on column ana_sampling_summary.sample_detail_count is '采样明细数';
comment on column ana_sampling_summary.created_at is '创建时间';
comment on column ana_sampling_summary.updated_at is '更新时间';

drop trigger if exists trg_ana_sampling_summary_updated_at on ana_sampling_summary;
create trigger trg_ana_sampling_summary_updated_at
before update on ana_sampling_summary
for each row execute procedure ana_set_updated_at();

create index if not exists idx_ana_sampling_summary_date
on ana_sampling_summary(orig_cdate desc, created_at desc);
