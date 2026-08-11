alter table ana_report_export_summary
add column if not exists daily_duplicate_issue_count bigint not null default 0;

alter table ana_report_export_summary
add column if not exists weekly_duplicate_issue_count bigint not null default 0;

comment on column ana_report_export_summary.daily_duplicate_issue_count is '日报重复问题数';
comment on column ana_report_export_summary.weekly_duplicate_issue_count is '周报重复问题数';
