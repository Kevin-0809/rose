-- Internal streaming sampling candidate table.
\set ON_ERROR_STOP on

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

create index if not exists idx_ana_sampling_candidate_batch_group
on ana_sampling_candidate(batch_id, sample_type, tran_code, service_code, sop_field_name);

create index if not exists idx_ana_sampling_candidate_batch_seq
on ana_sampling_candidate(batch_id, mesg_seq, field_index);

create index if not exists idx_ana_sampling_candidate_group_id
on ana_sampling_candidate(batch_id, group_id, candidate_id);

create index if not exists idx_ana_sampling_candidate_group_key
on ana_sampling_candidate(batch_id, group_key);

create index if not exists idx_ana_sampling_candidate_group_hash
on ana_sampling_candidate(batch_id, group_hash);

create index if not exists idx_ana_sampling_candidate_sample_pick
on ana_sampling_candidate(batch_id, sample_type, tran_code, service_code, sop_field_name, orig_field_value, dest_field_value, mesg_seq, field_index);
