alter table ana_report_export_interface_summary
    add column if not exists average_528_take_time numeric(18,0);

alter table ana_report_export_interface_summary
    add column if not exists average_ccbs_take_time numeric(18,0);

comment on column ana_report_export_interface_summary.average_528_take_time is '528平均耗时，单位毫秒';
comment on column ana_report_export_interface_summary.average_ccbs_take_time is 'CCBS平均耗时，单位毫秒';
