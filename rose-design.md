# 交易回放差异采样分析平台设计

## 1. 背景

528 是现生产银行核心系统，CCBS 是新建的对公分布式核心系统。交易回放工具用于 CCBS 投产前最后验证：将 528 数据迁移到 CCBS，录制生产交易请求和返回报文，再通过 ESF 回放到 CCBS，对录制返回和回放返回进行交易级、响应码级、字段级对比。

外围系统调用核心必须经过 ESF 或 ESB，调用 CCBS 经过 ESB，调用 528 经过 ESB。报文类型包括 SOP、SOAP、BizJSON。字段映射通过 ESB 或 ESF 转换，其中 528 接收 SOP，CCBS 接收 BizJSON。字段名在不同报文中可能不同，但业务语义一致，例如 SOAP（大驼峰） `TranSeqNo`、SOP（拼音首字母） `jylsh`、BizJSON （小驼峰）`tranSeqNo`。

当前数据库在 WSL Docker 容器 `opengauss5` 中，对外连接信息：

- URL：`jdbc:postgresql://localhost:15432/postgres`
- 用户名：`tss`
- 密码：`Tss@123456`

原始结果表：

- `tss_tran_comp`：交易级差异结果。
- `tss_field_comp`：字段级差异输出。

平台目标是对回放差异结果进行采样分析，第一版先打通“差异聚合、抽样、人工定因、状态流转、汇总展示”的闭环，同时保留后续领导看板和复测趋势分析能力。

## 2. 设计原则

- 原始差异表只读，不修改 `tss_tran_comp`、`tss_field_comp`。
- 不新建 schema，所有平台新增表直接使用 `ana_` 前缀。
- 千万级交易不在页面展示全量明细，页面展示聚合后的问题组和代表样本。
- 第一版抽样规则保持简单、稳定、可解释。
- 交易级核对结论和字段级核对结论分开统计。
- `tss_tran_comp.comp_result=3/4` 只代表交易结论或响应层面相符，不代表所有返回字段均一致。
- 字段语义以交易级配置为准，统一字段名优先使用 SOP 报文字段名。

## 3. 交易级结果枚举

`tss_tran_comp.comp_result` 的业务含义：

| 值 | 含义 |
| --- | --- |
| 0 | 未对比 |
| 1 | 不符：原失败，新成功 |
| 2 | 不符：原成功，新失败 |
| 3 | 相符：都失败 |
| 4 | 相符：都成功 |
| 5 | 忽略比对 |
| 6 | 比对中 |
| 7 | 对比异常 |
| 8 | 响应码不一致 |

字段级表 `tss_field_comp` 中，第一版重点分析 `comp_result=0` 的字段不一致记录。

## 4. 总体架构

第一版采用“原始结果只读 + 批处理聚合抽样 + 人工分析闭环 + 汇总看板”的架构。

### 4.1 只读事实源

- `tss_tran_comp`：交易级结果、响应码、交易码、交易流水等。
- `tss_field_comp`：字段级差异、字段名、原值、新值、交易码等。

### 4.2 分析任务

分析任务按批次手工触发，后续可扩展为定时任务。任务从原始表读取结果，生成交易级问题组、字段级问题组、样本和汇总快照。

### 4.3 平台分析表

平台表使用 `ana_` 前缀保存：

- 回放批次
- 交易说明
- 字段映射
- 问题组
- 样本
- 人工操作日志
- 汇总快照
- 原因字典

### 4.4 用户视图

- 开发/测试：问题组列表、问题组详情、样本查看、字段对比、原因标注、状态流转。
- 项目/领导：批次总览、通过率、阻断交易、模块责任、待分析数量、收敛趋势。
- 页面布局：页面布局合理，考虑查询条件，分页等信息。

## 5. 页面与用户流程

### 5.1 批次总览

展示每个回放批次的整体情况：

- 交易总量
- 交易级相符率
- 响应码不一致数量
- 字段不一致数量
- 交易级问题组数量
- 字段级问题组数量
- 待分析问题组数量
- 阻断问题数量
- 最近分析时间

批次总览是进入某个批次分析的入口。

### 5.2 问题组列表

问题组列表默认展示聚合后的问题组，不展示全量流水。

列表字段：

- 问题组类型：交易级、字段级
- 交易码
- 服务码，如原始表具备
- 交易名称
- 模块
- 负责人
- `comp_result`
- 差异字段
- 影响笔数
- 样本数
- 风险等级
- 状态
- 原因分类
- 最近更新时间

过滤条件：

- 批次
- 问题组类型
- 交易码
- 服务码
- `comp_result`
- 字段名
- 模块
- 负责人
- 状态
- 风险等级

### 5.3 问题组详情

问题组详情是开发/测试的主工作台。

展示内容：

- 问题组摘要
- 聚合口径
- 影响笔数
- 样本列表
- 交易级返回差异
- 字段级原值/新值
- 交易说明和字段语义说明
- 原因分类
- 责任人
- 状态
- 分析结论
- 操作历史

### 5.4 样本查看

交易级样本从 `tss_tran_comp` 读取详情。

字段级样本从 `tss_field_comp` 读取详情，并展示：

- 原始字段名
- 标准字段名
- 原值
- 新值
- 字段差异类型，如原始表具备
- 关联交易流水
- 同一交易流水下的全部字段差异

平台表默认不复制完整大报文，只保存原始表引用。详情页按需读取原始表内容。

### 5.5 管理汇总页

管理汇总页消费平台分析结果，不直接扫描全量原始明细。

展示维度：

- 批次
- 模块
- 负责人
- 交易重要级别
- 交易码
- 问题状态
- 风险等级

展示指标：

- 交易总数
- 问题组数量
- 待分析数量
- 已定因数量
- 阻断数量
- 已关闭数量
- 交易级相符率
- 字段级差异收敛情况

## 6. 状态流转

问题组状态建议如下：

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 待分析 |
| `ANALYZING` | 分析中 |
| `ROOT_CAUSED` | 已定因 |
| `WAIT_FIX` | 待修复 |
| `WAIT_CONFIG` | 待配置调整 |
| `IGNORED` | 可忽略 |
| `WAIT_RETEST` | 待复测 |
| `CLOSED` | 已关闭 |

状态变更规则：

- 从 `PENDING` 进入 `ANALYZING` 时记录分析人。
- 标记为 `ROOT_CAUSED`、`WAIT_FIX`、`WAIT_CONFIG`、`IGNORED` 时必须填写原因分类。
- 标记为 `IGNORED` 时必须填写无影响说明。
- 所有人工操作写入 `ana_issue_action_log`。

## 7. 平台表设计

当前实现废弃原先的 `ana_replay_batch`、`ana_issue_group`、`ana_issue_sample`、`ana_issue_action_log`、`ana_summary_snapshot`、`ana_reason_dict`。

保留配置表：

- `ana_tran_catalog`：交易说明。
- `ana_field_mapping`：字段语义映射。

新增采样结果表：

- `ana_sample_group`：差异采样分组，保存交易码、服务码、字段、责任人、影响数量和样本数量。
- `ana_sample_detail`：差异采样明细，保存具体流水、528 的值、CCBS 的值和字段映射信息。

### 7.0 新采样表口径

`ana_sample_group` 主要字段：

| 字段 | 含义 |
| --- | --- |
| `batch_id` | 批次 ID |
| `sample_type` | `RETURN_CODE` 或 `FIELD_DIFF` |
| `dest_trcd` | `tss_field_comp.dest_trcd`，格式为 `服务码&报文类型` |
| `service_code` | 服务码，不含报文类型 |
| `message_type` | 报文类型 |
| `tran_code` | 四位交易码 |
| `comp_result` | 交易级比对结果 |
| `sop_field_name` | SOP 字段名 |
| `soap_field_name` | SOAP 字段名 |
| `bizjson_field_name` | BizJSON 字段名 |
| `field_cn_name` | 中文名 |
| `owner` | 责任人 |
| `affected_count` | 数量 |
| `sample_count` | 样本数量，最多 100 |
| `reason` | 原因 |

`ana_sample_detail` 主要字段：

| 字段 | 含义 |
| --- | --- |
| `group_id` | 采样分组 ID，逻辑关联 `ana_sample_group` |
| `sample_type` | `RETURN_CODE` 或 `FIELD_DIFF` |
| `sample_seq_no` | 分组内样本序号 |
| `dest_trcd` | `tss_field_comp.dest_trcd` |
| `tran_code` | 四位交易码 |
| `sop_field_name` | SOP 字段名 |
| `soap_field_name` | SOAP 字段名 |
| `bizjson_field_name` | BizJSON 字段名 |
| `field_cn_name` | 中文名 |
| `orig_field_value` | 528 的值 |
| `dest_field_value` | CCBS 的值 |
| `tran_seq_no` | 流水号 |
| `owner` | 责任人 |
| `affected_count` | 所属分组数量 |
| `reason` | 原因 |

采样规则：

1. 响应码不一致或交易报错：`tss_field_comp` 只保留 `returnCode` 差异，写入 `sample_type='RETURN_CODE'`。
2. 交易响应成功：只对普通字段差异采样，排除 `returnCode`，写入 `sample_type='FIELD_DIFF'`。
3. 每个分组超过 100 笔取 100 笔，不足 100 笔全取。

### 7.1 `ana_replay_batch`

回放批次表。

建议字段：

| 字段 | 含义 |
| --- | --- |
| `batch_id` | 批次 ID |
| `batch_name` | 批次名称 |
| `data_start_time` | 数据开始时间 |
| `data_end_time` | 数据结束时间 |
| `replay_start_time` | 回放开始时间 |
| `replay_end_time` | 回放结束时间 |
| `tran_total_count` | 交易总数 |
| `field_diff_count` | 字段差异数 |
| `analysis_status` | 分析任务状态 |
| `last_analyzed_at` | 最近分析时间 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

### 7.2 `ana_tran_catalog`

交易说明表。

建议字段：

| 字段 | 含义                                              |
| --- |-------------------------------------------------|
| `tran_code` | 交易码    如：1001                                   |
| `service_code` | ESF/ESB 服务码 如：S080030035CorpAcctInfoQry&bizjson |
| `tran_name` | 交易名称        如：对公账户信息查询                          |
| `module_name` | 模块                                              |
| `owner` | 负责人                                             |
| `importance_level` | 重要级别                                            |
| `is_key_tran` | 是否关键交易                                          |
| `remark` | 备注                                              |
| `created_at` | 创建时间                                            |
| `updated_at` | 更新时间                                            |

### 7.3 `ana_field_mapping`

字段语义映射表。

同一个交易和服务码下维护 SOP、SOAP、BizJSON 三类字段映射。统一语义字段优先使用 SOP 字段名。

建议字段：

| 字段 | 含义 |
| --- | --- |
| `tran_code` | 交易码 |
| `service_code` | 服务码 |
| `std_field_name` | 标准字段名，优先使用 SOP 字段名 |
| `field_cn_name` | 字段中文名 |
| `sop_field_name` | SOP 字段名 |
| `soap_field_name` | SOAP 字段名 |
| `bizjson_field_name` | BizJSON 字段名 |
| `remark` | 备注 |

示例：

| 交易码 | 服务码 | 标准字段 | 中文名 | SOAP 字段 | BizJSON 字段 |
| --- | --- | --- | --- | --- | --- |
| 1001 | S080030035CorpAcctInfoQry&bizjson | jylsh | 交易流水号 | TranSeqNo | tranSeqNo |

### 7.4 `ana_issue_group`

问题组主表。

建议字段：

| 字段 | 含义 |
| --- | --- |
| `group_id` | 问题组 ID |
| `batch_id` | 批次 ID |
| `group_type` | `TRAN` 或 `FIELD` |
| `group_key` | 稳定聚合 key |
| `tran_code` | 交易码 |
| `service_code` | 服务码，如具备 |
| `comp_result` | 交易级或字段级结果 |
| `field_name` | 原始差异字段名 |
| `std_field_name` | 标准字段名 |
| `affected_count` | 影响笔数 |
| `sample_count` | 样本数 |
| `risk_level` | 风险等级 |
| `status` | 状态 |
| `owner` | 负责人 |
| `reason_code` | 原因分类 |
| `conclusion` | 分析结论 |
| `created_by_task_id` | 创建任务 ID |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

交易级 `group_key`：

```text
batch_id + tran_code + service_code + comp_result
```

如果原始表没有服务码，则使用：

```text
batch_id + tran_code + comp_result
```

字段级 `group_key`：

```text
batch_id + tran_code + service_code + std_field_name
```

如果原始表没有服务码，则使用：

```text
batch_id + tran_code + std_field_name
```

### 7.5 `ana_issue_sample`

问题组样本表。

建议字段：

| 字段 | 含义 |
| --- | --- |
| `sample_id` | 样本 ID |
| `group_id` | 问题组 ID |
| `batch_id` | 批次 ID |
| `group_type` | `TRAN` 或 `FIELD` |
| `tran_code` | 交易码 |
| `comp_result` | 结果码 |
| `field_name` | 原始字段名 |
| `std_field_name` | 标准字段名 |
| `sample_seq_no` | 样本序号 |
| `source_table` | 原始来源表 |
| `source_pk` | 原始表主键或稳定唯一键 |
| `tran_seq_no` | 交易流水号，如具备 |
| `covered_field_names` | 字段级样本覆盖的字段集合，可用 JSON 或分隔字符串保存 |
| `covered_field_count` | 字段级样本覆盖的字段数量 |
| `record_time` | 记录时间，如具备 |
| `sample_source` | `AUTO` 或 `MANUAL` |
| `created_at` | 创建时间 |

### 7.6 `ana_issue_action_log`

人工分析流水表。

建议字段：

| 字段 | 含义 |
| --- | --- |
| `log_id` | 日志 ID |
| `group_id` | 问题组 ID |
| `action_type` | 操作类型 |
| `old_value` | 旧值 |
| `new_value` | 新值 |
| `operator` | 操作人 |
| `comment` | 备注 |
| `created_at` | 创建时间 |

### 7.7 `ana_summary_snapshot`

汇总快照表。

建议字段：

| 字段 | 含义 |
| --- | --- |
| `snapshot_id` | 快照 ID |
| `batch_id` | 批次 ID |
| `stat_dimension` | 统计维度 |
| `stat_key` | 统计 key |
| `tran_total_count` | 交易总数 |
| `issue_group_count` | 问题组数 |
| `pending_count` | 待分析数 |
| `root_caused_count` | 已定因数 |
| `blocker_count` | 阻断数 |
| `closed_count` | 已关闭数 |
| `created_at` | 创建时间 |

### 7.8 `ana_reason_dict`

原因字典表。

建议原因分类：

| 原因码 | 含义 |
| --- | --- |
| `MAPPING_ERROR` | 字段映射错误 |
| `FORMAT_DIFF` | 格式差异 |
| `DEFAULT_VALUE_DIFF` | 默认值差异 |
| `TIME_OR_SERIAL_DIFF` | 时间或流水类差异 |
| `ESB_ESF_TRANSFORM_DIFF` | ESB/ESF 转换差异 |
| `CCBS_BUG` | CCBS 处理逻辑问题 |
| `DATA_MIGRATION_DIFF` | 数据迁移差异 |
| `REPLAY_ENV_DIFF` | 回放环境差异 |
| `NO_BUSINESS_IMPACT` | 无业务影响 |
| `UNKNOWN` | 待确认 |

## 8. 聚合与取样规则

### 8.1 交易级取样

来源表：`tss_tran_comp`

取样口径：

```text
每个回放批次 + 每个交易码 + 每种 comp_result 各取 100 笔
```

如果原始表具备服务码，问题组可以进一步细化为：

```text
每个回放批次 + 每个交易码 + 每个服务码 + 每种 comp_result 各取 100 笔
```

不足 100 笔时全取。

排序使用稳定排序。优先级建议：

```text
record_time asc, tran_seq_no asc, id asc
```

如果真实表没有这些字段，实施时使用表内可用的时间字段、交易流水字段、主键字段替代。

交易级样本写入 `ana_issue_sample`，`source_table='tss_tran_comp'`。

### 8.2 字段级取样

来源表：`tss_field_comp`

过滤条件：

```text
comp_result = 0
```

字段级取样不直接按字段差异行独立取样，而是以交易流水为样本单位，以差异字段覆盖为目标。原因是同一交易流水可能同时出现多个字段差异，不同交易流水之间的字段差异也可能交叉。如果简单按“交易码 + 字段”各取 100 行，会造成同一流水重复入样，也不利于观察字段组合关系。

第一版字段级取样口径：

- 先按批次、交易码、服务码、交易流水聚合 `tss_field_comp where comp_result=0`，得到每笔流水的差异字段集合。
- 对每个交易码建立“交易流水 -> 差异字段集合”的覆盖关系。
- 自动选择样本流水，使每个差异字段最多覆盖 100 笔样本流水。
- 同一流水如果包含多个字段差异，只在 `ana_issue_sample` 中保存一次，并记录其覆盖的字段集合，字段集合仅限于同一个交易码范围
- 详情页打开该样本流水时，展示该流水下全部字段差异。
- tss_field_comp表中dest_trcd存储的 服务码&报文格式，如：S080030035CorpAcctInfoQry&bizjson,在采样时需要结合ana_tran_catalog映射关系转换为四位交易码,

字段名优先使用 `ana_field_mapping.std_field_name`。如果未配置字段映射，则使用 `tss_field_comp` 原始字段名作为临时字段 key。

样本选择建议：

- 稀有字段优先：某字段出现笔数不足 100 时，尽量全部覆盖。
- 组合覆盖优先：优先选择能覆盖更多尚未达到 100 笔目标字段的流水。
- 稳定排序兜底：当多个流水覆盖效果相同时，按稳定排序选择。
- 抽样程序要考虑到tss_field_comp和tss_tran_comp海量数据的问题，比如可能有几千万，可以考虑使用spring batch的rpw进行数据处理

```text
record_time asc, tran_seq_no asc, id asc
```

字段级问题组仍按字段维度生成，用于统计每个字段的影响笔数：

```text
batch_id + tran_code + service_code + std_field_name
```

字段级样本则按交易流水保存，用于在样本详情中展开该流水涉及的全部字段差异。`ana_issue_sample.source_table='tss_field_comp'`，`source_pk` 可保存代表字段差异行主键；如果需要精确关联全部字段差异，可在实现时增加样本字段关联表，或通过 `batch_id + tran_code + service_code + tran_seq_no` 回查原始表。

### 8.3 第一版暂不做复杂维度补样

第一版不按渠道、机构、金额区间、日期分布补样。后续如果发现同一交易同一字段下存在多种业务原因，再扩展为交易码或字段内的二次分组。

## 9. 风险分级

系统可自动给问题组打初始风险，人工可调整。

建议规则：

| 风险 | 规则 |
| --- | --- |
| `P0` | 原成功新失败、对比异常、关键交易大面积失败 |
| `P1` | 响应码不一致、关键字段差异、影响范围较大 |
| `P2` | 普通业务字段差异 |
| `P3` | 格式、时间、流水、无业务影响字段等低风险差异 |

风险调整必须写入 `ana_issue_action_log`。

## 10. 分析任务流程

一次分析任务按批次执行：

1. 读取 `tss_tran_comp`，按交易码、服务码、`comp_result` 聚合计数。
2. 对每个交易级聚合组生成 `ana_issue_group`。
3. 对每个交易级聚合组从 `tss_tran_comp` 取最多 100 笔，写入 `ana_issue_sample`。
4. 读取 `tss_field_comp` 中 `comp_result=0` 的记录，按交易码、服务码、字段名聚合计数。
5. 对每个字段级聚合组生成 `ana_issue_group`。
6. 按交易流水聚合字段差异集合，执行字段覆盖取样：每个字段最多覆盖 100 笔样本流水，同一流水只保存一次样本。
7. 生成 `ana_summary_snapshot`，供批次总览和管理汇总页快速查询。
8. 任务结束后更新 `ana_replay_batch.analysis_status` 和 `last_analyzed_at`。

重跑策略：

- 同一批次重跑时刷新自动生成的问题组和样本。
- 如果相同 `group_key` 仍存在，继承原问题组的状态、原因、责任人和结论。
- 人工操作日志不删除。
- 已关闭的问题组如果在新一轮仍出现，应标记为复现，状态可回退到 `WAIT_RETEST` 或 `ANALYZING`。

## 11. 权限设计

第一版建议三类角色：

| 角色 | 权限 |
| --- | --- |
| `viewer` | 查看批次、问题组、样本、汇总 |
| `analyst` | 标注原因、修改状态、追加备注、调整责任人 |
| `admin` | 维护交易说明、字段映射、原因字典、触发分析任务 |

## 12. 数据安全

- 平台表默认不复制完整大报文，只保存原始表引用。
- 样本详情实时从原始表读取录制返回和回放返回。
- 页面展示敏感字段时预留脱敏规则，例如账号、证件号、手机号、客户名称。
- 导出功能第一版只导出问题组和样本索引，不导出完整报文。

## 13. 查询与索引建议

平台表建议索引：

```sql
create index idx_ana_issue_group_batch_status
on ana_issue_group(batch_id, status, risk_level);

create index idx_ana_issue_group_batch_tran
on ana_issue_group(batch_id, tran_code, service_code);

create index idx_ana_issue_group_owner_status
on ana_issue_group(batch_id, owner, status);

create index idx_ana_issue_group_key
on ana_issue_group(batch_id, group_key);

create index idx_ana_issue_sample_group
on ana_issue_sample(group_id, sample_seq_no);

create index idx_ana_summary_snapshot_batch
on ana_summary_snapshot(batch_id, stat_dimension, stat_key);
```

原始表侧需要确认是否已有以下索引：

- 批次号
- 交易码
- 服务码
- `comp_result`
- 字段名
- 交易流水
- 记录时间

如缺失，建议只补查询索引，不改原始业务字段。

## 14. 验证策略

第一版必须验证三件事。

### 14.1 聚合计数正确

`ana_issue_group.affected_count` 必须能和原始表按同口径 `count(*)` 对上。

交易级：

```text
tss_tran_comp 按 batch_id + tran_code + service_code + comp_result count(*)
```

字段级：

```text
tss_field_comp 按 batch_id + tran_code + service_code + std_field_name count(*)
where comp_result = 0
```

### 14.2 样本数量正确

交易级每组最多 100 笔：

```text
batch_id + tran_code + service_code + comp_result <= 100
```

字段级样本按流水去重后，每个字段最多覆盖 100 笔样本流水：

```text
batch_id + tran_code + service_code + std_field_name 覆盖的 distinct tran_seq_no <= 100
```

同时需要验证同一交易流水在同一交易码、服务码下不会重复保存为多个字段级自动样本。

### 14.3 重跑可追溯

同一批次重跑后：

- 自动样本可以刷新。
- 人工原因不能丢。
- 状态不能丢。
- 责任人不能丢。
- 分析结论不能丢。
- 操作日志不能丢。

## 15. 第一版范围

第一版包含：

- 批次管理
- 分析任务手工触发
- 交易级问题组生成
- 字段级问题组生成
- 交易级每组 100 笔自动抽样
- 字段级按交易流水去重并按字段覆盖目标自动抽样
- 问题组列表
- 问题组详情
- 样本查看
- 原因标注
- 状态流转
- 责任人维护
- 管理汇总
- 交易说明维护
- 字段映射维护
