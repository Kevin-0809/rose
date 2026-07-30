-- Rose replay management database DDL.
-- Contains current application schema only. Seed/test data lives in db/seed.sql.
\set ON_ERROR_STOP on

-- Analysis platform tables for replay comparison sampling.
create table if not exists ana_tran_catalog (
    catalog_id bigserial primary key,
    tran_code varchar(32) not null,
    service_code varchar(200) not null,
    tran_name varchar(200),
    module_name varchar(100),
    owner varchar(100),
    importance_level varchar(32),
    is_key_tran varchar(5) not null default 'false',
    remark varchar(1000),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_tran_catalog unique (tran_code, service_code)
);

comment on table ana_tran_catalog is '交易说明表';
comment on column ana_tran_catalog.catalog_id is '交易说明ID';
comment on column ana_tran_catalog.tran_code is '四位交易码';
comment on column ana_tran_catalog.service_code is 'ESF或ESB服务码，不含报文类型';
comment on column ana_tran_catalog.tran_name is '交易名称';
comment on column ana_tran_catalog.module_name is '所属模块';
comment on column ana_tran_catalog.owner is '负责人';
comment on column ana_tran_catalog.importance_level is '重要级别';
comment on column ana_tran_catalog.is_key_tran is '是否关键交易，字符串true或false';
comment on column ana_tran_catalog.remark is '备注';
comment on column ana_tran_catalog.created_at is '创建时间';
comment on column ana_tran_catalog.updated_at is '更新时间';

alter table ana_tran_catalog drop constraint if exists ck_ana_tran_catalog_is_key_tran;
alter table ana_tran_catalog add constraint ck_ana_tran_catalog_is_key_tran
check (is_key_tran in ('true', 'false'));

create table if not exists ana_tran_code_service_mapping (
    mapping_id bigserial primary key,
    tran_code varchar(32) not null,
    "528_service_code" varchar(200) not null,
    ccbs_service_code varchar(200) not null,
    remark varchar(1000),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_tran_code_service_mapping unique (tran_code, "528_service_code", ccbs_service_code)
);

comment on table ana_tran_code_service_mapping is '交易码528与CCBS服务码映射表';
comment on column ana_tran_code_service_mapping.mapping_id is '映射ID';
comment on column ana_tran_code_service_mapping.tran_code is '四位交易码';
comment on column ana_tran_code_service_mapping."528_service_code" is '528服务码，不含报文类型';
comment on column ana_tran_code_service_mapping.ccbs_service_code is 'CCBS服务码，不含报文类型';
comment on column ana_tran_code_service_mapping.remark is '备注';
comment on column ana_tran_code_service_mapping.created_at is '创建时间';
comment on column ana_tran_code_service_mapping.updated_at is '更新时间';

create table if not exists ana_module_owner_config (
    config_id bigserial primary key,
    module_name varchar(100) not null,
    primary_owner varchar(100),
    backup_owner varchar(100),
    remark varchar(1000),
    status varchar(20) not null default '启用',
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_module_owner_config unique (module_name),
    constraint ck_ana_module_owner_config_status check (status in ('启用', '停用'))
);

comment on table ana_module_owner_config is '领域负责人配置表';
comment on column ana_module_owner_config.config_id is '配置ID';
comment on column ana_module_owner_config.module_name is '领域名称';
comment on column ana_module_owner_config.primary_owner is '主负责人';
comment on column ana_module_owner_config.backup_owner is '备份负责人';
comment on column ana_module_owner_config.remark is '说明';
comment on column ana_module_owner_config.status is '状态，启用或停用';
comment on column ana_module_owner_config.created_at is '创建时间';
comment on column ana_module_owner_config.updated_at is '更新时间';

create table if not exists ana_field_mapping (
    mapping_id bigserial primary key,
    tran_code varchar(32) not null,
    service_code varchar(200) not null,
    std_field_name varchar(200) not null,
    field_cn_name varchar(200),
    sop_field_name varchar(200),
    soap_field_name varchar(200),
    bizjson_field_name varchar(200),
    remark varchar(1000),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_field_mapping unique (tran_code, service_code, std_field_name)
);

comment on table ana_field_mapping is '字段语义映射表';
comment on column ana_field_mapping.mapping_id is '字段映射ID';
comment on column ana_field_mapping.tran_code is '四位交易码';
comment on column ana_field_mapping.service_code is 'ESF或ESB服务码，不含报文类型';
comment on column ana_field_mapping.std_field_name is '标准字段名，优先使用SOP字段名';
comment on column ana_field_mapping.field_cn_name is '字段中文名';
comment on column ana_field_mapping.sop_field_name is 'SOP报文字段名';
comment on column ana_field_mapping.soap_field_name is 'SOAP报文字段名';
comment on column ana_field_mapping.bizjson_field_name is 'BizJSON报文字段名';
comment on column ana_field_mapping.remark is '备注';
comment on column ana_field_mapping.created_at is '创建时间';
comment on column ana_field_mapping.updated_at is '更新时间';

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

create table if not exists ana_tran_diff_result (
    result_id bigserial primary key,
    batch_id varchar(64) not null,
    orig_cdate varchar(8) not null,
    tran_code varchar(32) not null,
    service_code varchar(200) not null,
    message_type varchar(32),
    sample_tran_seq_no varchar(64),
    orig_error_code varchar(64),
    orig_error_desc varchar(500),
    dest_error_code varchar(64),
    dest_error_desc varchar(500),
    owner varchar(100),
    affected_tran_count bigint not null default 0,
    created_at timestamp not null default current_timestamp
);

comment on table ana_tran_diff_result is '交易级差异页面结果表';
comment on column ana_tran_diff_result.result_id is '结果ID';
comment on column ana_tran_diff_result.batch_id is '采样批次号';
comment on column ana_tran_diff_result.orig_cdate is '业务日期';
comment on column ana_tran_diff_result.tran_code is '交易码';
comment on column ana_tran_diff_result.service_code is '服务码';
comment on column ana_tran_diff_result.message_type is '报文类型';
comment on column ana_tran_diff_result.sample_tran_seq_no is '样例流水号';
comment on column ana_tran_diff_result.orig_error_code is '528响应码';
comment on column ana_tran_diff_result.orig_error_desc is '528响应描述';
comment on column ana_tran_diff_result.dest_error_code is 'CCBS响应码';
comment on column ana_tran_diff_result.dest_error_desc is 'CCBS响应描述';
comment on column ana_tran_diff_result.owner is '责任人';
comment on column ana_tran_diff_result.affected_tran_count is '影响交易笔数';
comment on column ana_tran_diff_result.created_at is '创建时间';

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
    historical_occurrence_count bigint not null default 0,
    first_seen_date date,
    previous_seen_date date,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

alter table ana_tran_diff_tracking_export add column if not exists issue_id bigint;
alter table ana_tran_diff_tracking_export add column if not exists issue_key varchar(600);
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
comment on column ana_tran_diff_tracking_export.orig_error_code is '原系统错误码';
comment on column ana_tran_diff_tracking_export.dest_error_code is '目标系统错误码';
comment on column ana_tran_diff_tracking_export.tran_code is '交易码';
comment on column ana_tran_diff_tracking_export.tran_name is '交易名称';
comment on column ana_tran_diff_tracking_export.module_name is '所属模块';
comment on column ana_tran_diff_tracking_export.orig_error_desc is '原系统错误描述';
comment on column ana_tran_diff_tracking_export.dest_error_desc is '目标系统错误描述';
comment on column ana_tran_diff_tracking_export.transaction_owner is '交易负责人';
comment on column ana_tran_diff_tracking_export.tran_seq_no is '交易流水号';
comment on column ana_tran_diff_tracking_export.problem_level is '问题级别';
comment on column ana_tran_diff_tracking_export.registration_date is '登记日期';
comment on column ana_tran_diff_tracking_export.field_name is '问题字段名';
comment on column ana_tran_diff_tracking_export.problem_description is '问题描述';
comment on column ana_tran_diff_tracking_export.problem_type is '问题类型';
comment on column ana_tran_diff_tracking_export.preliminary_analysis is '初步分析';
comment on column ana_tran_diff_tracking_export.final_solution is '最终解决方案';
comment on column ana_tran_diff_tracking_export.resolution_date is '解决日期';
comment on column ana_tran_diff_tracking_export.coordination_required is '是否需要协调';
comment on column ana_tran_diff_tracking_export.resolver is '解决人';
comment on column ana_tran_diff_tracking_export.defect_fix_date is '缺陷修复日期';
comment on column ana_tran_diff_tracking_export.issue_id is '统一问题台账ID快照';
comment on column ana_tran_diff_tracking_export.issue_key is '稳定业务键快照';
comment on column ana_tran_diff_tracking_export.historical_occurrence_count is '本批次前历史出现批次数';
comment on column ana_tran_diff_tracking_export.first_seen_date is '问题首次出现日期快照';
comment on column ana_tran_diff_tracking_export.previous_seen_date is '本次前最近出现日期快照';
comment on column ana_tran_diff_tracking_export.created_at is '创建时间';
comment on column ana_tran_diff_tracking_export.updated_at is '更新时间';

create table if not exists ana_field_diff_tracking_export (
    export_id bigserial primary key,
    export_timestamp timestamp not null,
    source_batch_id varchar(64) not null,
    business_date varchar(8) not null,
    row_no bigint not null,
    service_code varchar(200) not null,
    tran_code varchar(32),
    tran_name varchar(200),
    module_name varchar(100),
    sop_field_name varchar(200),
    soap_field_name varchar(200),
    bizjson_field_name varchar(200),
    field_cn_name varchar(200),
    mapping_status varchar(32),
    orig_field_value varchar(2000),
    dest_field_value varchar(2000),
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
    historical_occurrence_count bigint not null default 0,
    first_seen_date date,
    previous_seen_date date,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

alter table ana_field_diff_tracking_export add column if not exists issue_id bigint;
alter table ana_field_diff_tracking_export add column if not exists issue_key varchar(600);
alter table ana_field_diff_tracking_export add column if not exists historical_occurrence_count bigint not null default 0;
alter table ana_field_diff_tracking_export add column if not exists first_seen_date date;
alter table ana_field_diff_tracking_export add column if not exists previous_seen_date date;

comment on table ana_field_diff_tracking_export is '字段级差异问题跟踪导出表';
comment on column ana_field_diff_tracking_export.export_id is '导出记录ID';
comment on column ana_field_diff_tracking_export.export_timestamp is '导出时间';
comment on column ana_field_diff_tracking_export.source_batch_id is '来源批次号';
comment on column ana_field_diff_tracking_export.business_date is '业务日期';
comment on column ana_field_diff_tracking_export.row_no is '导出行号';
comment on column ana_field_diff_tracking_export.service_code is '服务码';
comment on column ana_field_diff_tracking_export.tran_code is '交易码';
comment on column ana_field_diff_tracking_export.tran_name is '交易名称';
comment on column ana_field_diff_tracking_export.module_name is '所属模块';
comment on column ana_field_diff_tracking_export.sop_field_name is 'SOP字段名';
comment on column ana_field_diff_tracking_export.soap_field_name is 'SOAP字段名';
comment on column ana_field_diff_tracking_export.bizjson_field_name is 'BizJSON字段名';
comment on column ana_field_diff_tracking_export.field_cn_name is '字段中文名';
comment on column ana_field_diff_tracking_export.mapping_status is '映射状态';
comment on column ana_field_diff_tracking_export.orig_field_value is '原字段值';
comment on column ana_field_diff_tracking_export.dest_field_value is '目标字段值';
comment on column ana_field_diff_tracking_export.transaction_owner is '交易负责人';
comment on column ana_field_diff_tracking_export.tran_seq_no is '交易流水号';
comment on column ana_field_diff_tracking_export.problem_level is '问题级别';
comment on column ana_field_diff_tracking_export.registration_date is '登记日期';
comment on column ana_field_diff_tracking_export.field_name is '问题字段名';
comment on column ana_field_diff_tracking_export.problem_description is '问题描述';
comment on column ana_field_diff_tracking_export.problem_type is '问题类型';
comment on column ana_field_diff_tracking_export.preliminary_analysis is '初步分析';
comment on column ana_field_diff_tracking_export.final_solution is '最终解决方案';
comment on column ana_field_diff_tracking_export.resolution_date is '解决日期';
comment on column ana_field_diff_tracking_export.coordination_required is '是否需要协调';
comment on column ana_field_diff_tracking_export.resolver is '解决人';
comment on column ana_field_diff_tracking_export.defect_fix_date is '缺陷修复日期';
comment on column ana_field_diff_tracking_export.issue_id is '统一问题台账ID快照';
comment on column ana_field_diff_tracking_export.issue_key is '稳定业务键快照';
comment on column ana_field_diff_tracking_export.historical_occurrence_count is '本批次前历史出现批次数';
comment on column ana_field_diff_tracking_export.first_seen_date is '问题首次出现日期快照';
comment on column ana_field_diff_tracking_export.previous_seen_date is '本次前最近出现日期快照';
comment on column ana_field_diff_tracking_export.created_at is '创建时间';
comment on column ana_field_diff_tracking_export.updated_at is '更新时间';

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
comment on column ana_diff_issue.orig_error_code is '原系统错误码';
comment on column ana_diff_issue.dest_error_code is '目标系统错误码';
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

create table if not exists ana_field_diff_result (
    result_id bigserial primary key,
    batch_id varchar(64) not null,
    orig_cdate varchar(8) not null,
    tran_code varchar(32) not null,
    service_code varchar(200) not null,
    message_type varchar(32),
    message_types varchar(200),
    sop_field_name varchar(200),
    soap_field_name varchar(200),
    bizjson_field_name varchar(200),
    field_cn_name varchar(200),
    mapping_status varchar(32) not null default 'MAPPED',
    sample_tran_seq_no varchar(64),
    orig_field_value varchar(2000),
    dest_field_value varchar(2000),
    owner varchar(100),
    affected_tran_count bigint not null default 0,
    created_at timestamp not null default current_timestamp
);

comment on table ana_field_diff_result is '字段级差异页面结果表';
comment on column ana_field_diff_result.result_id is '结果ID';
comment on column ana_field_diff_result.batch_id is '采样批次号';
comment on column ana_field_diff_result.orig_cdate is '业务日期';
comment on column ana_field_diff_result.tran_code is '交易码';
comment on column ana_field_diff_result.service_code is '服务码';
comment on column ana_field_diff_result.message_type is '主报文类型';
comment on column ana_field_diff_result.message_types is '涉及报文类型集合';
comment on column ana_field_diff_result.sop_field_name is 'SOP字段名';
comment on column ana_field_diff_result.soap_field_name is 'SOAP字段名';
comment on column ana_field_diff_result.bizjson_field_name is 'BizJSON字段名';
comment on column ana_field_diff_result.field_cn_name is '字段中文名';
comment on column ana_field_diff_result.mapping_status is '映射状态，MAPPED、UNMAPPED或MIXED';
comment on column ana_field_diff_result.sample_tran_seq_no is '样例流水号';
comment on column ana_field_diff_result.orig_field_value is '528字段值';
comment on column ana_field_diff_result.dest_field_value is 'CCBS字段值';
comment on column ana_field_diff_result.owner is '责任人';
comment on column ana_field_diff_result.affected_tran_count is '影响交易笔数';
comment on column ana_field_diff_result.created_at is '创建时间';

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
    tran_issue_count bigint not null default 0,
    return_code_issue_count bigint not null default 0,
    issue_field_count bigint not null default 0,
    field_diff_tran_count bigint not null default 0,
    fully_matched_count bigint not null default 0,
    unconfigured_service_count bigint not null default 0,
    unmapped_field_count bigint not null default 0,
    sample_group_count bigint not null default 0,
    sample_detail_count bigint not null default 0,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_sampling_summary_batch unique (batch_id)
);

alter table ana_sampling_summary add column if not exists tran_issue_count bigint;
alter table ana_sampling_summary alter column tran_issue_count set default 0;
update ana_sampling_summary set tran_issue_count = 0 where tran_issue_count is null;
alter table ana_sampling_summary add column if not exists return_code_issue_count bigint;
alter table ana_sampling_summary alter column return_code_issue_count set default 0;
update ana_sampling_summary set return_code_issue_count = 0 where return_code_issue_count is null;
alter table ana_sampling_summary add column if not exists field_diff_tran_count bigint;
alter table ana_sampling_summary alter column field_diff_tran_count set default 0;
update ana_sampling_summary set field_diff_tran_count = 0 where field_diff_tran_count is null;
alter table ana_sampling_summary add column if not exists unconfigured_service_count bigint;
alter table ana_sampling_summary alter column unconfigured_service_count set default 0;
update ana_sampling_summary set unconfigured_service_count = 0 where unconfigured_service_count is null;
alter table ana_sampling_summary add column if not exists unmapped_field_count bigint;
alter table ana_sampling_summary alter column unmapped_field_count set default 0;
update ana_sampling_summary set unmapped_field_count = 0 where unmapped_field_count is null;

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
comment on column ana_sampling_summary.tran_issue_count is '交易结果问题数量';
comment on column ana_sampling_summary.return_code_issue_count is '响应码问题数量';
comment on column ana_sampling_summary.issue_field_count is '出现问题字段数量，按交易流水和字段去重';
comment on column ana_sampling_summary.field_diff_tran_count is '字段差异影响交易流水数量';
comment on column ana_sampling_summary.fully_matched_count is '完全匹配交易笔数，交易流水存在且字段表无不一致字段';
comment on column ana_sampling_summary.unconfigured_service_count is '未配置服务数量';
comment on column ana_sampling_summary.unmapped_field_count is '未映射字段数量';
comment on column ana_sampling_summary.sample_group_count is '采样分组数';
comment on column ana_sampling_summary.sample_detail_count is '采样明细数';
comment on column ana_sampling_summary.created_at is '创建时间';
comment on column ana_sampling_summary.updated_at is '更新时间';

create table if not exists tss_retcode_comp (
    mesg_seq varchar(64) primary key,
    service_code varchar(200) not null,
    orig_cdate varchar(8),
    orig_error_code varchar(64),
    orig_error_desc varchar(500),
    dest_error_code varchar(64),
    dest_error_desc varchar(500),
    remark varchar(1000),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

comment on table tss_retcode_comp is '响应码差异登记表';
comment on column tss_retcode_comp.mesg_seq is '流水号';
comment on column tss_retcode_comp.service_code is '服务码，带报文类型';
comment on column tss_retcode_comp.orig_cdate is '业务日期';
comment on column tss_retcode_comp.orig_error_code is '528错误码';
comment on column tss_retcode_comp.orig_error_desc is '528错误描述';
comment on column tss_retcode_comp.dest_error_code is 'CCBS错误码';
comment on column tss_retcode_comp.dest_error_desc is 'CCBS错误描述';
comment on column tss_retcode_comp.remark is '备注';
comment on column tss_retcode_comp.created_at is '创建时间';
comment on column tss_retcode_comp.updated_at is '更新时间';

create table if not exists ana_migration_command (
    command_id bigserial primary key,
    source_data_source varchar(64) not null default 'bxds',
    target_data_source varchar(64) not null default 'primary',
    command_type varchar(32) not null default 'TIME_RANGE',
    time_from bigint not null,
    time_to bigint not null,
    window_seconds bigint not null,
    parallelism integer not null default 2,
    status varchar(32) not null default 'CREATED',
    total_shard_count bigint not null default 0,
    completed_shard_count bigint not null default 0,
    failed_shard_count bigint not null default 0,
    migrated_rows bigint not null default 0,
    skipped_rows bigint not null default 0,
    dropped_rows bigint not null default 0,
    request_sql text,
    response_sql text,
    tran_codes text,
    sample_size integer,
    lookback_days integer,
    error_message varchar(2000),
    remark varchar(1000),
    created_by varchar(100),
    created_time timestamp not null default current_timestamp,
    started_time timestamp,
    ended_time timestamp,
    updated_at timestamp not null default current_timestamp
);

alter table ana_migration_command
add column if not exists source_data_source varchar(64) not null default 'bxds';
alter table ana_migration_command
add column if not exists target_data_source varchar(64) not null default 'primary';
alter table ana_migration_command
add column if not exists command_type varchar(32) not null default 'TIME_RANGE';
alter table ana_migration_command
add column if not exists request_sql text;
alter table ana_migration_command
add column if not exists response_sql text;
alter table ana_migration_command
add column if not exists tran_codes text;
alter table ana_migration_command
add column if not exists sample_size integer;
alter table ana_migration_command
add column if not exists lookback_days integer;
update ana_migration_command
set lookback_days = 5
where command_type = 'TRAN_CODE'
  and lookback_days is null;
alter table ana_migration_command drop constraint if exists ck_ana_migration_command_status;
alter table ana_migration_command add constraint ck_ana_migration_command_status
check (status in ('CREATED','RUNNING','COMPLETED','FAILED','CANCEL_REQUESTED','CANCELLED'));
alter table ana_migration_command drop constraint if exists ck_ana_migration_command_type;
alter table ana_migration_command add constraint ck_ana_migration_command_type
check (command_type in ('TIME_RANGE','SQL','TRAN_CODE'));
alter table ana_migration_command drop constraint if exists ck_ana_migration_command_tran_code_parameters;
alter table ana_migration_command add constraint ck_ana_migration_command_tran_code_parameters
check (command_type <> 'TRAN_CODE' or (tran_codes is not null and btrim(tran_codes) <> '' and sample_size is not null and sample_size > 0 and lookback_days is not null and lookback_days > 0));

create table if not exists ana_migration_shard (
    shard_id bigserial primary key,
    command_id bigint not null,
    shard_seq integer not null,
    tran_code varchar(32),
    time_from bigint not null,
    time_to bigint not null,
    status varchar(32) not null default 'PENDING',
    migrated_rows bigint not null default 0,
    skipped_rows bigint not null default 0,
    dropped_rows bigint not null default 0,
    error_message varchar(2000),
    attempts integer not null default 0,
    created_time timestamp not null default current_timestamp,
    started_time timestamp,
    ended_time timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_migration_shard_seq unique (command_id, shard_seq)
);

alter table ana_migration_shard
add column if not exists tran_code varchar(32);

alter table ana_migration_shard drop constraint if exists ck_ana_migration_shard_status;
alter table ana_migration_shard add constraint ck_ana_migration_shard_status
check (status in ('PENDING','RUNNING','COMPLETED','FAILED','SKIPPED'));

comment on table ana_migration_command is '报文日志迁移指令表';
comment on column ana_migration_command.source_data_source is '源数据源标识，固定bxds';
comment on column ana_migration_command.target_data_source is '目标数据源标识，固定主数据源';
comment on column ana_migration_command.command_type is '迁移指令类型，TIME_RANGE或SQL';
comment on column ana_migration_command.request_sql is 'SQL迁移模式历史兼容字段，当前请求报文按source_ip和trans_id自动回查';
comment on column ana_migration_command.response_sql is 'SQL迁移模式的响应报文查询SQL';
comment on column ana_migration_command.tran_codes is '交易码迁移模式的逗号分隔交易码';
comment on column ana_migration_command.sample_size is '交易码迁移模式下每类报文的迁移笔数';
comment on column ana_migration_command.lookback_days is '交易码迁移模式下从当天起向前回溯的自然日数';
comment on table ana_migration_shard is '报文日志迁移分片表';

create index if not exists idx_ana_migration_command_status
on ana_migration_command(status, created_time desc);

create index if not exists idx_ana_migration_command_type_created
on ana_migration_command(command_type, created_time desc, command_id desc);

create index if not exists idx_ana_migration_shard_command_status
on ana_migration_shard(command_id, status, shard_seq);

create table if not exists ana_transaction_list_import_task (
    task_id bigint generated by default as identity primary key,
    status varchar(32) not null default 'CREATED',
    original_filename varchar(255),
    list_file_path varchar(1000) not null,
    total_count integer not null default 0,
    request_batch_count integer not null default 0,
    completed_batch_count integer not null default 0,
    failed_batch_count integer not null default 0,
    imported_count integer not null default 0,
    tran_inserted integer not null default 0,
    tran_updated integer not null default 0,
    field_inserted integer not null default 0,
    field_updated integer not null default 0,
    field_skipped integer not null default 0,
    imported_tran_codes text,
    failure_message varchar(4000),
    created_time timestamp not null default current_timestamp,
    started_time timestamp,
    ended_time timestamp,
    updated_at timestamp not null default current_timestamp
);

alter table ana_transaction_list_import_task
    add column if not exists imported_tran_codes text;

alter table ana_transaction_list_import_task drop constraint if exists ck_ana_transaction_list_import_task_status;
alter table ana_transaction_list_import_task add constraint ck_ana_transaction_list_import_task_status
check (status in ('CREATED','RUNNING','COMPLETED','FAILED'));

comment on table ana_transaction_list_import_task is '金融业务交易信息登记表导入任务表';
comment on column ana_transaction_list_import_task.status is '导入任务状态';
comment on column ana_transaction_list_import_task.list_file_path is '上传清单临时文件路径';
comment on column ana_transaction_list_import_task.imported_tran_codes is '已成功导入交易码，按行分隔，用于失败续跑跳过已完成交易';
comment on column ana_transaction_list_import_task.failure_message is '失败批次或缺失交易码摘要';

create index if not exists idx_ana_transaction_list_import_task_status
on ana_transaction_list_import_task(status, created_time desc);

-- Batch domain report materialization tables.
create table if not exists ana_batch_domain_report_command (
    command_id bigserial primary key,
    batch_id varchar(64) not null,
    status varchar(32) not null default 'PENDING',
    started_time timestamp,
    ended_time timestamp,
    error_message varchar(4000),
    created_time timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_batch_domain_report_command_batch unique (batch_id),
    constraint ck_ana_batch_domain_report_command_status
        check (status in ('PENDING','RUNNING','SUCCEEDED','FAILED'))
);

create table if not exists ana_batch_domain_transaction_stat (
    stat_id bigserial primary key,
    batch_id varchar(64) not null,
    module_name varchar(100) not null,
    covered_service_count bigint not null default 0,
    sent_transaction_count bigint not null default 0,
    comp_result_1_count bigint not null default 0,
    comp_result_2_count bigint not null default 0,
    comp_result_3_count bigint not null default 0,
    comp_result_4_count bigint not null default 0,
    comp_result_8_count bigint not null default 0,
    created_time timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_batch_domain_transaction_stat unique (batch_id, module_name)
);

create table if not exists ana_batch_domain_field_stat (
    stat_id bigserial primary key,
    batch_id varchar(64) not null,
    module_name varchar(100) not null,
    total_field_count bigint not null default 0,
    diff_field_count bigint not null default 0,
    no_diff_field_count bigint not null default 0,
    created_time timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_batch_domain_field_stat unique (batch_id, module_name)
);

create table if not exists ana_batch_report_gap (
    gap_id bigserial primary key,
    batch_id varchar(64) not null,
    gap_type varchar(32) not null,
    service_code varchar(200),
    message_type varchar(32),
    field_key varchar(500),
    affected_count bigint not null default 0,
    created_time timestamp not null default current_timestamp,
    constraint ck_ana_batch_report_gap_type
        check (gap_type in ('UNCONFIGURED_SERVICE','UNMAPPED_FIELD'))
);

create index if not exists idx_ana_batch_report_gap_batch_type
on ana_batch_report_gap(batch_id, gap_type);

-- Report detail export persistence tables.
create table if not exists ana_report_export_command (
    command_id bigserial primary key,
    batch_id varchar(64) not null,
    report_date varchar(8) not null,
    status varchar(32) not null default 'PENDING',
    current_stage varchar(32),
    started_time timestamp,
    ended_time timestamp,
    error_message varchar(4000),
    created_time timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_report_export_command_batch unique (batch_id),
    constraint ck_ana_report_export_command_status
        check (status in ('PENDING','RUNNING','SUCCEEDED','FAILED'))
);

comment on table ana_report_export_command is '报表明细导出指令表';
comment on column ana_report_export_command.command_id is '导出指令主键';
comment on column ana_report_export_command.batch_id is '导出批次号';
comment on column ana_report_export_command.report_date is '报表日期，格式yyyymmdd';
comment on column ana_report_export_command.status is '导出执行状态';
comment on column ana_report_export_command.current_stage is '当前执行阶段';
comment on column ana_report_export_command.started_time is '导出开始时间';
comment on column ana_report_export_command.ended_time is '导出结束时间';
comment on column ana_report_export_command.error_message is '导出失败错误信息';
comment on column ana_report_export_command.created_time is '创建时间';
comment on column ana_report_export_command.updated_at is '更新时间';

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
    field_pass_transaction_count bigint not null default 0,
    comparison_pass_rate numeric(12,8) not null default 0,
    transaction_issue_count bigint not null default 0,
    field_issue_count bigint not null default 0,
    issue_total_count bigint not null default 0,
    duplicate_issue_count bigint not null default 0,
    created_time timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_report_export_summary unique (batch_id, module_name)
);

alter table ana_report_export_summary
add column if not exists field_pass_transaction_count bigint not null default 0;
alter table ana_report_export_summary
add column if not exists comparison_pass_rate numeric(12,8) not null default 0;
alter table ana_report_export_summary
add column if not exists transaction_issue_count bigint not null default 0;
alter table ana_report_export_summary
add column if not exists field_issue_count bigint not null default 0;
alter table ana_report_export_summary
add column if not exists issue_total_count bigint not null default 0;
alter table ana_report_export_summary
add column if not exists duplicate_issue_count bigint not null default 0;

comment on table ana_report_export_summary is '报表明细导出汇总表';
comment on column ana_report_export_summary.summary_id is '导出汇总主键';
comment on column ana_report_export_summary.batch_id is '导出批次号';
comment on column ana_report_export_summary.report_date is '报表日期，格式yyyymmdd';
comment on column ana_report_export_summary.module_name is '所属模块名称';
comment on column ana_report_export_summary.covered_528_interface_count is '覆盖的528接口数量';
comment on column ana_report_export_summary.sent_transaction_count is '已发送交易数量';
comment on column ana_report_export_summary.comp_result_1_count is '比对结果1数量';
comment on column ana_report_export_summary.comp_result_2_count is '比对结果2数量';
comment on column ana_report_export_summary.comp_result_3_count is '二者均失败且响应码一致数量';
comment on column ana_report_export_summary.comp_result_4_count is '二者均成功数量';
comment on column ana_report_export_summary.comp_result_8_count is '二者均失败且响应码不一致数量';
comment on column ana_report_export_summary.diff_528_field_count is '差异528字段数量';
comment on column ana_report_export_summary.success_rate is '成功率';
comment on column ana_report_export_summary.field_pass_transaction_count is '二者均成功且无字段差异交易数';
comment on column ana_report_export_summary.comparison_pass_rate is '比对通过率';
comment on column ana_report_export_summary.transaction_issue_count is '交易级差异总数';
comment on column ana_report_export_summary.field_issue_count is '字段级差异总数';
comment on column ana_report_export_summary.issue_total_count is '问题总数';
comment on column ana_report_export_summary.duplicate_issue_count is '重复问题数';
comment on column ana_report_export_summary.created_time is '创建时间';
comment on column ana_report_export_summary.updated_at is '更新时间';

create index if not exists idx_ana_report_export_command_status
on ana_report_export_command(status, created_time desc);

create index if not exists idx_ana_report_export_summary_batch
on ana_report_export_summary(batch_id, module_name);

create index if not exists idx_ana_field_mapping_lookup
on ana_field_mapping(tran_code, service_code, sop_field_name, soap_field_name, bizjson_field_name);

create index if not exists idx_ana_sampling_command_history
on ana_sampling_command(orig_cdate desc, created_time desc);

create index if not exists idx_ana_sampling_command_status
on ana_sampling_command(status, created_time desc);

create index if not exists idx_ana_sampling_summary_date
on ana_sampling_summary(orig_cdate desc, created_at desc);

create index if not exists idx_ana_tran_diff_result_query
on ana_tran_diff_result(batch_id, orig_cdate, tran_code, service_code, message_type, owner);

create index if not exists idx_ana_tran_diff_result_sample
on ana_tran_diff_result(batch_id, sample_tran_seq_no);

create unique index if not exists uk_ana_tran_diff_tracking_export_batch_issue
on ana_tran_diff_tracking_export(source_batch_id, service_code, orig_error_code, dest_error_code);

create index if not exists idx_ana_tran_diff_tracking_export_time
on ana_tran_diff_tracking_export(export_timestamp desc);

create index if not exists idx_ana_tran_diff_tracking_export_issue
on ana_tran_diff_tracking_export(issue_id);

create index if not exists idx_ana_field_diff_tracking_export_source
on ana_field_diff_tracking_export(source_batch_id, service_code, soap_field_name);

create unique index if not exists uk_ana_field_diff_tracking_export_batch_issue
on ana_field_diff_tracking_export(source_batch_id, service_code, issue_key);

create index if not exists idx_ana_field_diff_tracking_export_time
on ana_field_diff_tracking_export(export_timestamp desc);

create index if not exists idx_ana_field_diff_tracking_export_issue
on ana_field_diff_tracking_export(issue_id);

create index if not exists idx_ana_diff_issue_status_last_seen
on ana_diff_issue(issue_status, last_seen_date desc);

create index if not exists idx_ana_diff_issue_service_field
on ana_diff_issue(service_code, normalized_source_field_name);

create index if not exists idx_ana_field_diff_result_query
on ana_field_diff_result(batch_id, orig_cdate, tran_code, service_code, message_type, mapping_status, owner);

create index if not exists idx_ana_field_diff_result_sample
on ana_field_diff_result(batch_id, sample_tran_seq_no);

create index if not exists idx_tss_retcode_comp_date_service
on tss_retcode_comp(orig_cdate, service_code);

create index if not exists idx_tss_retcode_comp_code
on tss_retcode_comp(orig_error_code, dest_error_code);

-- Recording configuration tables.
CREATE SEQUENCE IF NOT EXISTS seq_recording_config_id;

CREATE TABLE IF NOT EXISTS system_config (
    config_key character varying(100) PRIMARY KEY,
    config_value character varying(200) NOT NULL,
    description character varying(500),
    updated_time timestamp(6) without time zone DEFAULT pg_systimestamp() NOT NULL
)
WITH (
    orientation = row,
    compression = no,
    storage_type = USTORE,
    segment = off
);

COMMENT ON TABLE system_config IS '系统配置表';
COMMENT ON COLUMN system_config.config_key IS '配置键';
COMMENT ON COLUMN system_config.config_value IS '配置值';
COMMENT ON COLUMN system_config.description IS '描述';
COMMENT ON COLUMN system_config.updated_time IS '更新时间';



CREATE TABLE IF NOT EXISTS recording_config (
    id bigint DEFAULT nextval('seq_recording_config_id'::regclass) PRIMARY KEY,
    txn_code character varying(100) NOT NULL,
    txn_switch tinyint DEFAULT 1 NOT NULL,
    record_ratio integer DEFAULT 100 NOT NULL,
    description character varying(500),
    created_time timestamp(6) without time zone DEFAULT pg_systimestamp() NOT NULL,
    updated_time timestamp(6) without time zone DEFAULT pg_systimestamp() NOT NULL
)
WITH (
    orientation = row,
    compression = no,
    storage_type = USTORE,
    segment = off
);

COMMENT ON TABLE recording_config IS '录制配置表';
COMMENT ON COLUMN recording_config.id IS '主键ID';
COMMENT ON COLUMN recording_config.txn_code IS '交易代码，如 S010020110LtSzTnyLtrApl111';
COMMENT ON COLUMN recording_config.txn_switch IS '交易级开关： 0=关闭， 1=启用';
COMMENT ON COLUMN recording_config.record_ratio IS '录制比例： 0-100， 100表示100%录制';
COMMENT ON COLUMN recording_config.description IS '描述';
COMMENT ON COLUMN recording_config.created_time IS '创建时间';
COMMENT ON COLUMN recording_config.updated_time IS '更新时间';

CREATE UNIQUE INDEX IF NOT EXISTS uk_txn_code
    ON recording_config (txn_code);

-- Indexes for set-based sampling execution.
create index if not exists idx_tss_field_comp_sampling_diff
on tss_field_comp(orig_cdate, comp_result, orig_field_name, mesg_seq, conv_index, conv_cindex, dest_field_name);

create index if not exists idx_tss_field_comp_sampling_stream
on tss_field_comp(orig_cdate, comp_result, mesg_seq, field_index);

create index if not exists idx_tss_tran_comp_sampling_join
on tss_tran_comp(orig_cdate, mesg_seq, conv_index, conv_cindex, comp_result, dest_trcd);

create index if not exists idx_tss_retcode_comp_sampling_join
on tss_retcode_comp(orig_cdate, mesg_seq);

create index if not exists idx_ana_tran_catalog_service
on ana_tran_catalog(service_code);

create index if not exists idx_ana_tran_code_service_mapping_tran
on ana_tran_code_service_mapping(tran_code);

create index if not exists idx_ana_module_owner_config_status
on ana_module_owner_config(status, module_name);

create index if not exists idx_ana_field_mapping_sampling
on ana_field_mapping(service_code, sop_field_name, bizjson_field_name, tran_code);

create index if not exists idx_msg_flow_log_response_report_time_trans
on msg_flow_log_response(response_time, trans_id);
