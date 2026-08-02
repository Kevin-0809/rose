comment on column ana_field_diff_tracking_export.affected_tran_count is '该问题出现在的交易笔数';
alter table ana_field_diff_tracking_export add column if not exists affected_tran_count bigint not null default 0;
comment on column ana_tran_diff_tracking_export.affected_tran_count is '该问题出现在的交易笔数';
alter table ana_tran_diff_tracking_export add column if not exists affected_tran_count bigint not null default 0;