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

create table if not exists ana_sample_group (
    group_id bigserial primary key,
    batch_id varchar(64) not null,
    orig_cdate varchar(8),
    sample_type varchar(32) not null,
    group_key varchar(500) not null,
    group_hash varchar(32),
    config_status varchar(32) not null default 'CONFIGURED',
    mapping_status varchar(32) not null default 'MAPPED',
    semantic_signature varchar(2000),
    semantic_signature_hash varchar(32),
    semantic_field_names varchar(1000),
    message_types varchar(200),
    dest_trcd varchar(200) not null,
    service_code varchar(200) not null,
    message_type varchar(32),
    tran_code varchar(32) not null,
    comp_result varchar(1) not null,
    sop_field_name varchar(200) not null,
    soap_field_name varchar(200),
    bizjson_field_name varchar(200),
    field_cn_name varchar(200),
    orig_field_value varchar(2000),
    dest_field_value varchar(2000),
    owner varchar(100),
    affected_count bigint not null default 0,
    affected_tran_count bigint not null default 0,
    affected_field_count bigint not null default 0,
    sample_count integer not null default 0,
    reason varchar(1000),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_ana_sample_group_type check (sample_type in ('TRAN_RESULT', 'RETURN_CODE', 'FIELD_DIFF')),
    constraint uk_ana_sample_group_key unique (batch_id, group_key)
);

alter table ana_sample_group add column if not exists orig_cdate varchar(8);
alter table ana_sample_group add column if not exists config_status varchar(32);
alter table ana_sample_group alter column config_status set default 'CONFIGURED';
update ana_sample_group set config_status = 'CONFIGURED' where config_status is null;
alter table ana_sample_group add column if not exists mapping_status varchar(32);
alter table ana_sample_group alter column mapping_status set default 'MAPPED';
update ana_sample_group set mapping_status = 'MAPPED' where mapping_status is null;
alter table ana_sample_group add column if not exists semantic_signature varchar(2000);
alter table ana_sample_group add column if not exists semantic_signature_hash varchar(32);
alter table ana_sample_group add column if not exists semantic_field_names varchar(1000);
alter table ana_sample_group add column if not exists message_types varchar(200);
alter table ana_sample_group add column if not exists affected_tran_count bigint;
alter table ana_sample_group alter column affected_tran_count set default 0;
update ana_sample_group set affected_tran_count = affected_count where affected_tran_count is null;
alter table ana_sample_group add column if not exists affected_field_count bigint;
alter table ana_sample_group alter column affected_field_count set default 0;
update ana_sample_group set affected_field_count = 0 where affected_field_count is null;
alter table ana_sample_group drop constraint if exists ck_ana_sample_group_type;
alter table ana_sample_group add constraint ck_ana_sample_group_type check (sample_type in ('TRAN_RESULT', 'RETURN_CODE', 'FIELD_DIFF'));

comment on table ana_sample_group is '差异采样分组表';
comment on column ana_sample_group.group_id is '采样分组ID';
comment on column ana_sample_group.batch_id is '批次ID';
comment on column ana_sample_group.orig_cdate is '原始回放日期，格式yyyyMMdd';
comment on column ana_sample_group.sample_type is '采样类型，TRAN_RESULT为交易结果差异，RETURN_CODE为响应码差异，FIELD_DIFF为字段差异';
comment on column ana_sample_group.group_key is '稳定分组键';
comment on column ana_sample_group.group_hash is '分组键MD5，用于批量关联优化';
comment on column ana_sample_group.config_status is '交易配置状态，CONFIGURED或UNCONFIGURED_SERVICE';
comment on column ana_sample_group.mapping_status is '字段映射状态，MAPPED、UNMAPPED或MIXED';
comment on column ana_sample_group.semantic_signature is '语义字段差异组合签名';
comment on column ana_sample_group.semantic_signature_hash is '语义字段差异组合签名MD5';
comment on column ana_sample_group.semantic_field_names is '语义字段名集合';
comment on column ana_sample_group.message_types is '该问题组涉及的报文类型集合';
comment on column ana_sample_group.dest_trcd is '原始表目标交易标识，格式为服务码&报文类型';
comment on column ana_sample_group.service_code is '服务码，不含报文类型';
comment on column ana_sample_group.message_type is '报文类型';
comment on column ana_sample_group.tran_code is '四位交易码';
comment on column ana_sample_group.comp_result is '交易级比对结果';
comment on column ana_sample_group.sop_field_name is 'SOP字段名';
comment on column ana_sample_group.soap_field_name is 'SOAP字段名';
comment on column ana_sample_group.bizjson_field_name is 'BizJSON字段名';
comment on column ana_sample_group.field_cn_name is '字段中文名';
comment on column ana_sample_group.orig_field_value is '528的值，用于响应码分组';
comment on column ana_sample_group.dest_field_value is 'CCBS的值，用于响应码分组';
comment on column ana_sample_group.owner is '责任人';
comment on column ana_sample_group.affected_count is '该分组影响数量';
comment on column ana_sample_group.affected_tran_count is '该分组影响交易流水数量';
comment on column ana_sample_group.affected_field_count is '该分组影响字段差异数量';
comment on column ana_sample_group.sample_count is '该分组样本数量';
comment on column ana_sample_group.reason is '原因';
comment on column ana_sample_group.created_at is '创建时间';
comment on column ana_sample_group.updated_at is '更新时间';

create table if not exists ana_sample_detail (
    sample_id bigserial primary key,
    group_id bigint not null,
    batch_id varchar(64) not null,
    orig_cdate varchar(8),
    sample_type varchar(32) not null,
    sample_seq_no integer not null,
    config_status varchar(32) not null default 'CONFIGURED',
    dest_trcd varchar(200) not null,
    service_code varchar(200) not null,
    message_type varchar(32),
    tran_code varchar(32) not null,
    comp_result varchar(1) not null,
    sop_field_name varchar(200) not null,
    soap_field_name varchar(200),
    bizjson_field_name varchar(200),
    field_cn_name varchar(200),
    orig_field_value varchar(2000),
    dest_field_value varchar(2000),
    tran_seq_no varchar(32) not null,
    owner varchar(100),
    affected_count bigint not null default 0,
    field_count integer not null default 0,
    orig_error_code varchar(64),
    orig_error_desc varchar(500),
    dest_error_code varchar(64),
    dest_error_desc varchar(500),
    reason varchar(1000),
    source_table varchar(64) not null default 'tss_field_comp',
    source_pk varchar(300),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_ana_sample_detail_type check (sample_type in ('TRAN_RESULT', 'RETURN_CODE', 'FIELD_DIFF')),
    constraint uk_ana_sample_detail_seq unique (group_id, sample_seq_no)
);

create table if not exists ana_sample_detail_field (
    field_detail_id bigserial primary key,
    sample_id bigint not null,
    group_id bigint not null,
    batch_id varchar(64) not null,
    mesg_seq varchar(64) not null,
    message_type varchar(32),
    raw_field_name varchar(200),
    std_field_name varchar(200),
    field_cn_name varchar(200),
    orig_field_value varchar(2000),
    dest_field_value varchar(2000),
    mapping_status varchar(32) not null default 'MAPPED',
    field_index integer,
    created_at timestamp not null default current_timestamp
);

alter table ana_sample_detail add column if not exists orig_cdate varchar(8);
alter table ana_sample_detail add column if not exists config_status varchar(32);
alter table ana_sample_detail alter column config_status set default 'CONFIGURED';
update ana_sample_detail set config_status = 'CONFIGURED' where config_status is null;
alter table ana_sample_detail add column if not exists field_count integer;
alter table ana_sample_detail alter column field_count set default 0;
update ana_sample_detail set field_count = 0 where field_count is null;
alter table ana_sample_detail add column if not exists orig_error_code varchar(64);
alter table ana_sample_detail add column if not exists orig_error_desc varchar(500);
alter table ana_sample_detail add column if not exists dest_error_code varchar(64);
alter table ana_sample_detail add column if not exists dest_error_desc varchar(500);
alter table ana_sample_detail drop constraint if exists ck_ana_sample_detail_type;
alter table ana_sample_detail add constraint ck_ana_sample_detail_type check (sample_type in ('TRAN_RESULT', 'RETURN_CODE', 'FIELD_DIFF'));

comment on table ana_sample_detail is '差异采样明细表';
comment on column ana_sample_detail.sample_id is '样本ID';
comment on column ana_sample_detail.group_id is '采样分组ID，逻辑关联ana_sample_group';
comment on column ana_sample_detail.batch_id is '批次ID';
comment on column ana_sample_detail.orig_cdate is '原始回放日期，格式yyyyMMdd';
comment on column ana_sample_detail.sample_type is '采样类型，TRAN_RESULT为交易结果差异，RETURN_CODE为响应码差异，FIELD_DIFF为字段差异';
comment on column ana_sample_detail.sample_seq_no is '分组内样本序号';
comment on column ana_sample_detail.config_status is '交易配置状态，CONFIGURED或UNCONFIGURED_SERVICE';
comment on column ana_sample_detail.dest_trcd is '原始表目标交易标识，格式为服务码&报文类型';
comment on column ana_sample_detail.service_code is '服务码，不含报文类型';
comment on column ana_sample_detail.message_type is '报文类型';
comment on column ana_sample_detail.tran_code is '四位交易码';
comment on column ana_sample_detail.comp_result is '交易级比对结果';
comment on column ana_sample_detail.sop_field_name is 'SOP字段名';
comment on column ana_sample_detail.soap_field_name is 'SOAP字段名';
comment on column ana_sample_detail.bizjson_field_name is 'BizJSON字段名';
comment on column ana_sample_detail.field_cn_name is '字段中文名';
comment on column ana_sample_detail.orig_field_value is '528的值';
comment on column ana_sample_detail.dest_field_value is 'CCBS的值';
comment on column ana_sample_detail.tran_seq_no is '流水号';
comment on column ana_sample_detail.owner is '责任人';
comment on column ana_sample_detail.affected_count is '该分组影响数量';
comment on column ana_sample_detail.field_count is '样本流水包含的字段差异数量';
comment on column ana_sample_detail.orig_error_code is '528错误码';
comment on column ana_sample_detail.orig_error_desc is '528错误描述';
comment on column ana_sample_detail.dest_error_code is 'CCBS错误码';
comment on column ana_sample_detail.dest_error_desc is 'CCBS错误描述';
comment on column ana_sample_detail.reason is '原因';
comment on column ana_sample_detail.source_table is '来源表';
comment on column ana_sample_detail.source_pk is '来源记录定位键';
comment on column ana_sample_detail.created_at is '创建时间';
comment on column ana_sample_detail.updated_at is '更新时间';

comment on table ana_sample_detail_field is '采样样本字段明细表';
comment on column ana_sample_detail_field.field_detail_id is '样本字段明细ID';
comment on column ana_sample_detail_field.sample_id is '样本ID';
comment on column ana_sample_detail_field.group_id is '采样分组ID';
comment on column ana_sample_detail_field.batch_id is '批次ID';
comment on column ana_sample_detail_field.mesg_seq is '流水号';
comment on column ana_sample_detail_field.message_type is '报文类型';
comment on column ana_sample_detail_field.raw_field_name is '原始报文字段名';
comment on column ana_sample_detail_field.std_field_name is '标准语义字段名';
comment on column ana_sample_detail_field.field_cn_name is '字段中文名';
comment on column ana_sample_detail_field.orig_field_value is '528的值';
comment on column ana_sample_detail_field.dest_field_value is 'CCBS的值';
comment on column ana_sample_detail_field.mapping_status is '字段映射状态，MAPPED或UNMAPPED';
comment on column ana_sample_detail_field.field_index is '字段序号';
comment on column ana_sample_detail_field.created_at is '创建时间';

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

create table if not exists ana_sampling_candidate (
    candidate_id bigserial primary key,
    batch_id varchar(64) not null,
    sample_type varchar(32) not null,
    group_key varchar(500),
    group_hash varchar(32),
    group_id bigint,
    dest_trcd varchar(200) not null,
    service_code varchar(200) not null,
    message_type varchar(32),
    tran_code varchar(32) not null,
    comp_result varchar(1) not null,
    sop_field_name varchar(200) not null,
    soap_field_name varchar(200),
    bizjson_field_name varchar(200),
    field_cn_name varchar(200),
    orig_field_value varchar(2000),
    dest_field_value varchar(2000),
    mesg_seq varchar(64) not null,
    conv_index integer,
    conv_cindex integer,
    field_index integer,
    owner varchar(100),
    created_at timestamp not null default current_timestamp
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

comment on table ana_sampling_candidate is '采样流式处理中间候选表';
comment on column ana_sampling_candidate.candidate_id is '候选记录ID';
comment on column ana_sampling_candidate.batch_id is '采样批次号';
comment on column ana_sampling_candidate.sample_type is '采样类型，RETURN_CODE为响应码差异，FIELD_DIFF为字段差异';
comment on column ana_sampling_candidate.group_key is '稳定分组键';
comment on column ana_sampling_candidate.group_hash is '分组键MD5，用于批量关联优化';
comment on column ana_sampling_candidate.group_id is '采样分组ID，逻辑关联ana_sample_group';
comment on column ana_sampling_candidate.dest_trcd is '原始表目标交易标识，格式为服务码&报文类型';
comment on column ana_sampling_candidate.service_code is '服务码，不含报文类型';
comment on column ana_sampling_candidate.message_type is '报文类型';
comment on column ana_sampling_candidate.tran_code is '四位交易码';
comment on column ana_sampling_candidate.comp_result is '交易级比对结果';
comment on column ana_sampling_candidate.sop_field_name is 'SOP字段名';
comment on column ana_sampling_candidate.soap_field_name is 'SOAP字段名';
comment on column ana_sampling_candidate.bizjson_field_name is 'BizJSON字段名';
comment on column ana_sampling_candidate.field_cn_name is '字段中文名';
comment on column ana_sampling_candidate.orig_field_value is '528的值';
comment on column ana_sampling_candidate.dest_field_value is 'CCBS的值';
comment on column ana_sampling_candidate.mesg_seq is '流水号';
comment on column ana_sampling_candidate.conv_index is '会话索引';
comment on column ana_sampling_candidate.conv_cindex is '会话子索引';
comment on column ana_sampling_candidate.field_index is '字段序号';
comment on column ana_sampling_candidate.owner is '责任人';
comment on column ana_sampling_candidate.created_at is '创建时间';

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

create index if not exists idx_ana_sample_group_batch_type
on ana_sample_group(batch_id, sample_type, tran_code, service_code);

create index if not exists idx_ana_sample_group_owner
on ana_sample_group(batch_id, owner);

create index if not exists idx_ana_sample_group_field
on ana_sample_group(batch_id, tran_code, service_code, sop_field_name);

create index if not exists idx_ana_sample_group_semantic
on ana_sample_group(batch_id, sample_type, tran_code, service_code, semantic_signature_hash);

create index if not exists idx_ana_sample_group_status
on ana_sample_group(batch_id, config_status, mapping_status);

create index if not exists idx_ana_sample_detail_group
on ana_sample_detail(group_id, sample_seq_no);

create index if not exists idx_ana_sample_detail_lookup
on ana_sample_detail(batch_id, tran_code, service_code, tran_seq_no);

create index if not exists idx_ana_sample_detail_field_sample
on ana_sample_detail_field(sample_id, field_index);

create index if not exists idx_ana_sample_detail_field_lookup
on ana_sample_detail_field(batch_id, group_id, mesg_seq);

create index if not exists idx_ana_field_mapping_lookup
on ana_field_mapping(tran_code, service_code, sop_field_name, soap_field_name, bizjson_field_name);

create index if not exists idx_ana_sampling_command_history
on ana_sampling_command(orig_cdate desc, created_time desc);

create index if not exists idx_ana_sampling_command_status
on ana_sampling_command(status, created_time desc);

create index if not exists idx_ana_sampling_summary_date
on ana_sampling_summary(orig_cdate desc, created_at desc);

create index if not exists idx_ana_sampling_candidate_batch_group
on ana_sampling_candidate(batch_id, sample_type, tran_code, service_code, sop_field_name);

create index if not exists idx_ana_sampling_candidate_group_id
on ana_sampling_candidate(batch_id, group_id, candidate_id);

create index if not exists idx_ana_sampling_candidate_group_key
on ana_sampling_candidate(batch_id, group_key);

create index if not exists idx_ana_sampling_candidate_group_hash
on ana_sampling_candidate(batch_id, group_hash);

create index if not exists idx_ana_sampling_candidate_batch_seq
on ana_sampling_candidate(batch_id, mesg_seq, field_index);

create index if not exists idx_ana_sampling_candidate_sample_pick
on ana_sampling_candidate(batch_id, sample_type, tran_code, service_code, sop_field_name, orig_field_value, dest_field_value, mesg_seq, field_index);

create index if not exists idx_ana_sample_group_batch_groupkey_aff
on ana_sample_group(batch_id, group_key, affected_count);

create index if not exists idx_ana_sample_group_batch_grouphash
on ana_sample_group(batch_id, group_hash, group_key);

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

create index if not exists idx_tss_tran_comp_sampling_join
on tss_tran_comp(orig_cdate, mesg_seq, conv_index, conv_cindex, comp_result, dest_trcd);

create index if not exists idx_ana_tran_catalog_service
on ana_tran_catalog(service_code);

create index if not exists idx_ana_field_mapping_sampling
on ana_field_mapping(service_code, sop_field_name, bizjson_field_name, tran_code);
