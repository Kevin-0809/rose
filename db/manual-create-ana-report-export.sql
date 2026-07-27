-- Report detail export tables for PostgreSQL / openGauss / GaussDB.
-- Run this script once in the same schema used by the Rose application.

create table if not exists ana_report_export_command (
    command_id bigserial primary key,
    batch_id varchar(64) not null,
    report_date varchar(8) not null,
    status varchar(32) not null default 'PENDING',
    started_time timestamp,
    ended_time timestamp,
    error_message varchar(4000),
    created_time timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_report_export_command_batch unique (batch_id),
    constraint ck_ana_report_export_command_status
        check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);

create table if not exists ana_report_export_summary (
    summary_id bigserial primary key,
    batch_id varchar(64) not null,
    report_date varchar(8) not null,
    module_name varchar(100) not null,
    covered_528_interface_count bigint not null default 0,
    sent_transaction_count bigint not null default 0,
    comp_result_1_count bigint not null default 0,
    comp_result_2_count bigint not null default 0,
    comp_result_3_count bigint not null default 0,
    comp_result_4_count bigint not null default 0,
    comp_result_8_count bigint not null default 0,
    diff_528_field_count bigint not null default 0,
    success_rate numeric(12,8) not null default 0,
    created_time timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_report_export_summary unique (batch_id, module_name)
);

create index if not exists idx_ana_report_export_command_status
on ana_report_export_command(status, created_time desc);

create index if not exists idx_ana_report_export_summary_batch
on ana_report_export_summary(batch_id, module_name);

create unique index if not exists uk_ana_tran_diff_tracking_export_batch_issue
on ana_tran_diff_tracking_export(source_batch_id, service_code, orig_error_code, dest_error_code);
