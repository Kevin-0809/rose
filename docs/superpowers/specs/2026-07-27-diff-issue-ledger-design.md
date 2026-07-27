# 差异问题台账设计

## 目标

为交易级和字段级差异建立统一的问题台账。台账由人工维护问题分析和整改信息；后续采集命中同一问题时，自动更新出现统计，并将最新维护信息快照写入当前批次明细。

历史批次明细不可被台账编辑反向修改。

## 数据模型

新增 `ana_diff_issue`，一行表示一个可持续跟踪的问题。

```sql
create table ana_diff_issue (
    issue_id bigserial primary key,
    issue_key varchar(600) not null unique,
    issue_level varchar(16) not null check (issue_level in ('TRANSACTION', 'FIELD')),
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
    issue_status varchar(16) not null default 'OPEN'
        check (issue_status in ('OPEN', 'RESOLVED', 'IGNORED')),
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
    check ((issue_level = 'TRANSACTION' and normalized_source_field_name is null)
        or (issue_level = 'FIELD' and orig_error_code is null and dest_error_code is null
            and normalized_source_field_name is not null))
);

create index idx_ana_diff_issue_status_last_seen
    on ana_diff_issue(issue_status, last_seen_date desc);
create index idx_ana_diff_issue_service_field
    on ana_diff_issue(service_code, normalized_source_field_name);

comment on table ana_diff_issue is '统一差异问题台账';
comment on column ana_diff_issue.issue_id is '问题主键';
comment on column ana_diff_issue.issue_key is '问题稳定业务键';
comment on column ana_diff_issue.issue_level is '问题级别：TRANSACTION或FIELD';
comment on column ana_diff_issue.service_code is '服务码';
comment on column ana_diff_issue.tran_code is '交易码';
comment on column ana_diff_issue.tran_name is '交易名称';
comment on column ana_diff_issue.module_name is '所属领域';
comment on column ana_diff_issue.transaction_owner is '交易负责人';
comment on column ana_diff_issue.orig_error_code is '528响应码，仅交易级使用';
comment on column ana_diff_issue.dest_error_code is 'CCBS响应码，仅交易级使用';
comment on column ana_diff_issue.normalized_source_field_name is '规范化源字段名，仅字段级使用';
comment on column ana_diff_issue.problem_type is '问题类型';
comment on column ana_diff_issue.problem_description is '问题描述';
comment on column ana_diff_issue.preliminary_analysis is '初步分析';
comment on column ana_diff_issue.final_solution is '最终解决方案';
comment on column ana_diff_issue.issue_status is '问题状态：OPEN、RESOLVED或IGNORED';
comment on column ana_diff_issue.coordination_required is '是否需要协调';
comment on column ana_diff_issue.resolver is '解决人';
comment on column ana_diff_issue.resolution_date is '解决日期';
comment on column ana_diff_issue.defect_fix_date is '缺陷修复日期';
comment on column ana_diff_issue.first_seen_date is '首次出现日期';
comment on column ana_diff_issue.last_seen_date is '最近出现日期';
comment on column ana_diff_issue.first_seen_batch_id is '首次出现批次号';
comment on column ana_diff_issue.last_seen_batch_id is '最近出现批次号';
comment on column ana_diff_issue.occurrence_batch_count is '累计出现批次数';
comment on column ana_diff_issue.created_at is '创建时间';
comment on column ana_diff_issue.updated_at is '更新时间';
```

两张既有批次明细表均新增以下字段：

```sql
issue_id bigint references ana_diff_issue(issue_id),
issue_key varchar(600),
historical_occurrence_count bigint not null default 0,
first_seen_date date,
previous_seen_date date
```

新增明细字段的 DDL 注释如下：

```sql
comment on column ana_tran_diff_tracking_export.issue_id is '关联问题台账主键';
comment on column ana_tran_diff_tracking_export.issue_key is '问题稳定业务键快照';
comment on column ana_tran_diff_tracking_export.historical_occurrence_count is '本批次前的历史出现批次数';
comment on column ana_tran_diff_tracking_export.first_seen_date is '问题首次出现日期快照';
comment on column ana_tran_diff_tracking_export.previous_seen_date is '本次前最近出现日期快照';

comment on column ana_field_diff_tracking_export.issue_id is '关联问题台账主键';
comment on column ana_field_diff_tracking_export.issue_key is '问题稳定业务键快照';
comment on column ana_field_diff_tracking_export.historical_occurrence_count is '本批次前的历史出现批次数';
comment on column ana_field_diff_tracking_export.first_seen_date is '问题首次出现日期快照';
comment on column ana_field_diff_tracking_export.previous_seen_date is '本次前最近出现日期快照';
```

`issue_id` 和 `issue_key` 记录快照归属；整改字段也作为当前批次快照写入。历史批次不随台账编辑变化。

## 问题身份

交易级键：`TRAN|服务码|528响应码|CCBS响应码`。

字段级键：`FIELD|服务码|规范化源字段名`。规范化沿用采集既有规则：字段名含点号时仅保留前两段，例如 `items.0.amount` 归一为 `items.0`。字段键不包含 `dest_field_name`。

键组成部分统一进行空值规范化和大小写归一，避免同一问题因空白或大小写形成不同台账记录。

## 采集与快照

每次写入交易级或字段级当前批次明细前：

1. 根据原始差异生成 `issue_key`。
2. 台账不存在时创建记录，首次和最近出现日期均为本次业务日期，批次出现次数为 `1`，状态为 `OPEN`。
3. 台账存在时更新服务、交易、领域和负责人等业务元数据，以及最近出现日期与批次；批次出现次数仅在该问题本批次首次出现时加一。
4. 状态为 `RESOLVED` 的问题再次出现时改为 `OPEN`；人工填写的问题类型、分析、方案、协调信息、解决人和日期保留不被采集覆盖。
5. 将台账整改字段写入当前批次明细；`historical_occurrence_count` 为更新前的批次出现次数，`first_seen_date` 为台账首次日期，`previous_seen_date` 为更新前的最近出现日期。

台账及当前批次明细必须在同一事务中更新。唯一键冲突时重新读取台账并按更新路径处理，确保并发采集不产生重复问题。

## 问题台账页面

新增路径 `/diff-issues`。

列表支持按问题级别、状态、服务码、领域、负责人、首次/最近出现日期范围和关键字筛选，并分页展示：问题级别、服务码、交易或字段标识、问题描述、状态、出现批次数、首次出现、最近出现、负责人和更新时间。

详情页或侧栏展示完整身份信息、统计信息和整改信息。身份字段与历史统计只读；可编辑字段为问题类型、初步分析、最终方案、状态、是否需要协调、解决人、解决日期和缺陷修复日期。状态为 `RESOLVED` 时必须填写解决日期。

每条记录提供跳转最近批次明细的入口。

## 接口

- `GET /api/diff-issues`：分页列表与筛选。
- `GET /api/diff-issues/{id}`：问题详情。
- `PATCH /api/diff-issues/{id}`：仅更新允许维护的整改字段。

更新使用乐观锁或 `updated_at` 条件，避免两个维护人员互相覆盖。找不到记录返回 404；不合法状态或已解决未填解决日期返回 400。

## 验收与测试

- 首次命中交易级和字段级问题时均创建正确的台账和当前批次快照。
- 同一问题在同一批次出现多次，台账批次出现次数只增加一次。
- 下一成功批次再次命中时，当前明细显示正确的历史次数、首次日期和上次出现日期。
- 台账维护后，下次采集的明细带入全部整改字段；旧批次明细不变。
- 已解决问题再次出现时状态重开，且既有维护内容保留。
- 页面筛选、分页、编辑校验、并发更新冲突和最近批次跳转均有覆盖。
