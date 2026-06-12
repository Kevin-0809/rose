# 采样批量重设计

## 背景

当前采样批量主要依赖集合化 SQL，把 `tss_tran_comp`、`tss_field_comp`、`tss_retcode_comp` 聚合进 `ana_sample_group` 和 `ana_sample_detail`。这个实现能快速生成结果，但存在几个关键问题：

- 交易统计和候选生成依赖 `ana_tran_catalog` 内连接，未配置服务会被漏算。
- 字段映射只按 SOP 字段匹配，A825 这类同一交易存在 SOP、SOAP、BizJSON 三种报文时，语义相同但报文字段名不同的问题会被拆散。
- 字段级问题按单字段聚合，无法表达“同一流水的一组字段同时出错”这个更接近人工分析的根因视角。
- 大段复杂 SQL 难以测试、解释和迭代。

新的设计目标是：原始事实不漏数，配置只增强展示和语义归并；批量主流程由程序组织，SQL 负责高效读取和批量写入。

## 目标

- 以 `tss_tran_comp` 作为交易事实和交易级统计基准。
- `tss_field_comp` 和 `tss_retcode_comp` 作为差异详情来源，不能决定交易是否存在。
- 使用字段映射把 SOP、SOAP、BizJSON 的不同字段名归一为同一语义字段。
- 字段级问题采用“两层归并”：一级按语义字段组合生成问题组，二级保留每个样本流水的实际字段明细。
- 支持未配置交易和未映射字段进入结果，并标记状态。
- 降低 SQL 复杂度，把字段归一、组合签名、样本选择等逻辑放到 Java 程序中。
- 批次可重跑，同一输入数据生成稳定结果。

## 非目标

- 不改动 `tss_tran_comp`、`tss_field_comp`、`tss_retcode_comp` 原始表。
- 不引入 Spring Batch。
- 本次重设计聚焦采样批量、采样结果表、查询页面和导出；人工定因、状态流转和操作日志不纳入本次范围。
- 本次不解析完整 JSON/XML 报文结构，只基于当前字段表中的字段名和值做语义归并和签名。

## 总体口径

采样批量按 `orig_cdate` 执行。事实范围来自原始表：

- 交易总量来自 `tss_tran_comp where orig_cdate = :origCdate`。
- 交易级问题来自 `tss_tran_comp.comp_result in ('1', '2', '8')`。
- 响应码详情来自 `tss_retcode_comp`，按 `mesg_seq` 和 `orig_cdate` 关联交易。
- 字段级问题来自 `tss_field_comp.comp_result = '0'`，优先分析交易级成功的流水，即 `tss_tran_comp.comp_result = '4'`。

配置表不再过滤事实范围：

- `ana_tran_catalog` 用于补充 `tran_code`、交易名称、模块、负责人。
- `ana_field_mapping` 用于字段语义归一。
- 未命中的交易写入结果，`tran_code` 使用可推导值或 `UNKNOWN`，并标记 `config_status = UNCONFIGURED_SERVICE`。
- 未命中的字段写入结果，标准字段名退回原始字段名，并标记 `mapping_status = UNMAPPED`。

## 问题类型

重设计后 `sample_type` 明确定义三类问题：

- `TRAN_RESULT`：交易结果不一致，来自 `tss_tran_comp`。
- `RETURN_CODE`：响应码差异，来自 `tss_retcode_comp`。
- `FIELD_DIFF`：字段值差异，来自 `tss_field_comp`。

页面、查询服务和导出同步支持这三类问题。交易级问题不依赖响应码表是否有记录，响应码表只补充 `RETURN_CODE` 详情。

## 字段语义归一

字段映射必须按报文类型匹配，而不是只匹配 SOP 字段：

- `message_type = sop`：优先匹配 `ana_field_mapping.sop_field_name`。
- `message_type = soap`：优先匹配 `ana_field_mapping.soap_field_name`。
- `message_type = bizjson`：优先匹配 `ana_field_mapping.bizjson_field_name`。
- 兜底匹配 `std_field_name`、`sop_field_name`、`soap_field_name`、`bizjson_field_name` 任意列。

归一结果包含：

- `std_field_name`：标准语义字段名。
- `field_cn_name`：字段中文名。
- `raw_field_name`：原始字段名，保留在样本字段明细中。
- `message_type`：报文类型，保留在样本中，不参与字段级问题组主键。
- `mapping_status`：`MAPPED` 或 `UNMAPPED`。

例如 A825：

| message_type | raw_field_name | std_field_name |
| --- | --- | --- |
| bizjson | `CurrencyId` | `currency_id` |
| sop | `HUOBDH` | `currency_id` |
| bizjson | `FcyCollCrspBnkLkg` | `link_info` |
| sop | `FAB251` | `link_info` |

这样 `11111111111` 和 `11111111114` 虽然报文字段名不同，但可以归入同一个字段组合问题。

## 字段级两层归并

字段级问题不再直接按单个字段名生成问题组，而是按“同一流水的语义字段差异组合”生成问题组。

### 一级问题组

字段级问题组主键：

```text
orig_cdate
+ tran_code
+ base_service_code
+ sample_type = FIELD_DIFF
+ semantic_diff_signature
```

其中：

- `base_service_code` 是去掉 `&sop`、`&soap`、`&bizjson` 后的服务码。
- `message_type` 不参与字段级问题组主键。
- `semantic_diff_signature` 是同一流水下所有字段差异归一后的稳定签名。

签名生成规则：

1. 将同一流水、同一会话索引下的字段差异收集成列表。
2. 每条字段差异映射为 `std_field_name + ':' + normalized_value_pattern`。
3. 按 `std_field_name` 排序。
4. 用 `|` 拼接为签名。
5. 签名过长时保存完整签名文本，同时保存 MD5 用于索引。

`normalized_value_pattern` 使用实际差异值：

```text
orig_field_value -> dest_field_value
```

本次实现先使用字段表中已经输出的值生成模式，不额外解析复杂报文结构。

### 二级样本和字段明细

每个问题组下保存样本流水。样本流水下要能展示具体字段明细：

- `mesg_seq`
- `message_type`
- `raw_field_name`
- `std_field_name`
- `field_cn_name`
- `orig_field_value`
- `dest_field_value`
- `mapping_status`

现有 `ana_sample_detail` 一行只能表达一个字段差异。为了表达“两层归并”，新增字段明细表：

```text
ana_sample_detail       样本流水级，一行一个样本流水
ana_sample_detail_field 样本字段级，一行一个字段差异
```

页面和导出按新的样本流水表、样本字段明细表同步改造，不保留旧的一行一个字段差异展示模型。

## 数据模型调整

调整或新增以下字段。

`ana_sample_group`：

- `orig_cdate`
- `config_status`
- `mapping_status`
- `semantic_signature`
- `semantic_signature_hash`
- `semantic_field_names`
- `message_types`
- `affected_tran_count`
- `affected_field_count`

`ana_sample_detail`：

- 保持样本流水维度。
- 增加 `message_type`、`config_status`。
- 对 `FIELD_DIFF`，不再要求一行就是一个字段。

新增 `ana_sample_detail_field`：

- `field_detail_id`
- `sample_id`
- `group_id`
- `batch_id`
- `mesg_seq`
- `message_type`
- `raw_field_name`
- `std_field_name`
- `field_cn_name`
- `orig_field_value`
- `dest_field_value`
- `mapping_status`
- `field_index`

## 批量程序结构

新增或重构为以下组件。

### `SamplingBatchRunner`

负责编排批次：

1. 校验批次和业务日期。
2. 清理同 `batch_id` 的历史结果。
3. 加载配置快照。
4. 流式读取交易事实。
5. 读取并归并响应码差异。
6. 读取并归并字段差异。
7. 生成问题组。
8. 选择样本。
9. 批量写入结果表。
10. 写入汇总。
11. 更新批次状态。

### `SamplingConfigSnapshot`

一次批次开始时加载配置，避免每条记录查数据库：

- `service_code -> TranCatalogInfo`
- `(tran_code, service_code, message_type, raw_field_name) -> FieldSemanticInfo`
- 兜底字段名索引

### `TranFactReader`

使用数据库游标或 JDBC 流式查询读取 `tss_tran_comp`，禁止使用 offset/limit 分页扫描：

```sql
select *
from tss_tran_comp
where orig_cdate = :origCdate
order by mesg_seq, conv_index, conv_cindex
```

程序内生成 `TranFact`，并计算交易级统计。

流式读取要求：

- 查询连接在读取期间保持事务打开，避免驱动一次性拉取全量结果。
- `Statement.setFetchSize` 使用固定批量，例如 1000 或 5000。
- 结果集按 `mesg_seq, conv_index, conv_cindex` 稳定排序。
- 不使用 `offset`，避免大数据量下重复扫描和排序成本。

### `ReturnCodeIssueReader`

使用游标或 JDBC 流式查询读取响应码差异：

```sql
select *
from tss_retcode_comp
where orig_cdate = :origCdate
order by mesg_seq
```

程序内按流水关联 `TranFact`。若存在响应码记录但交易事实缺失，标记为孤儿响应码记录，不进入交易总量，但可写入错误统计或日志。

### `FieldDiffReader`

使用游标或 JDBC 流式查询读取字段差异：

```sql
select *
from tss_field_comp
where orig_cdate = :origCdate
  and comp_result = '0'
order by mesg_seq, conv_index, conv_cindex, field_index
```

程序内按流水聚合为 `FieldDiffSampleCandidate`，每个候选包含同一流水的一组字段差异。

### `IssueGrouper`

负责生成稳定问题组：

- 交易级按交易结果分组。
- 响应码按原/目标响应码分组。
- 字段级按语义字段组合签名分组。

### `SamplePicker`

负责稳定抽样：

- 每组默认最多 20 个样本流水。
- 按 `mesg_seq, conv_index, conv_cindex` 排序。
- 不随机，保证重跑稳定。

### `SamplingResultWriter`

负责批量写入：

- 使用 `JdbcTemplate.batchUpdate`。
- 每 500 或 1000 行一批提交。
- 写入顺序为 group -> detail -> detail_field -> summary。

## 页面和导出同步改造

页面不兼容旧结构，随新结果模型一次性调整。

### 采样分组页

分组页展示 `ana_sample_group`，一行一个问题组：

- 批次号、业务日期。
- 问题类型：`TRAN_RESULT`、`RETURN_CODE`、`FIELD_DIFF`。
- 交易码、交易名称、基础服务码、模块、负责人。
- 配置状态、字段映射状态。
- 语义字段组合：`semantic_field_names`。
- 涉及报文类型：`message_types`。
- 影响交易数、影响字段数、样本流水数。
- 响应码差异值或交易结果码。

筛选条件：

- 批次号、业务日期、问题类型。
- 交易码、服务码、模块、负责人。
- 配置状态、映射状态。
- 语义字段名。
- 报文类型。

### 采样样本页

样本页展示 `ana_sample_detail`，一行一个样本流水：

- 批次号、问题组、流水号。
- 交易码、服务码、报文类型。
- 问题类型、交易结果码。
- 字段数量、响应码信息。
- 负责人、配置状态。

点击样本流水进入字段明细区域，查询 `ana_sample_detail_field`：

- 标准字段名。
- 字段中文名。
- 原始报文字段名。
- 528 字段值。
- CCBS 字段值。
- 映射状态。
- 字段序号。

### 字段明细导出

导出分为三个文件或三个 sheet：

- 问题组 sheet：来自 `ana_sample_group`。
- 样本流水 sheet：来自 `ana_sample_detail`。
- 样本字段明细 sheet：来自 `ana_sample_detail_field`。

字段级导出必须同时包含标准字段名和原始字段名，保证 A825 这类跨报文问题既能按语义合并，又能定位到真实报文字段。

## 详细批量步骤

1. 创建批次指令，状态为 `CREATED`。
2. 异步执行器把状态改为 `RUNNING`。
3. `SamplingBatchRunner` 开始执行。
4. 删除同 `batch_id` 的 `ana_sample_detail_field`、`ana_sample_detail`、`ana_sample_group`、`ana_sampling_summary`。
5. 加载 `ana_tran_catalog` 和 `ana_field_mapping` 到内存快照。
6. 流式读取 `tss_tran_comp`：
   - 记录总交易数。
   - 统计各 `comp_result` 数量。
   - 建立 `mesg_seq + conv_index + conv_cindex` 到交易事实的索引。
   - 对 `comp_result in ('1','2','8')` 生成交易级候选。
7. 读取 `tss_retcode_comp`：
   - 关联交易事实。
   - 生成响应码候选。
8. 读取 `tss_field_comp.comp_result = '0'`：
   - 关联交易事实。
   - 默认只对交易级 `comp_result = '4'` 的流水生成字段级候选。
   - 按流水聚合字段差异。
   - 对每个字段差异做语义归一。
   - 生成语义字段组合签名。
9. `IssueGrouper` 合并候选为问题组。
10. `SamplePicker` 为每个问题组选择代表样本。
11. 写入 `ana_sample_group`。
12. 写入 `ana_sample_detail`。
13. 写入 `ana_sample_detail_field`。
14. 写入 `ana_sampling_summary`。
15. 删除临时内存结构，状态改为 `COMPLETED`。
16. 任一步失败则状态改为 `FAILED`，记录错误消息。

## 汇总指标

`ana_sampling_summary` 包含：

- `total_tran_count`
- `comp_result_1_count`
- `comp_result_2_count`
- `comp_result_3_count`
- `comp_result_4_count`
- `comp_result_8_count`
- `tran_issue_count`
- `return_code_issue_count`
- `field_diff_count`
- `field_diff_tran_count`
- `fully_matched_count`
- `unconfigured_service_count`
- `unmapped_field_count`
- `sample_group_count`
- `sample_detail_count`

`fully_matched_count` 定义为：

```text
tss_tran_comp.comp_result = '4'
且该流水没有 tss_field_comp.comp_result = '0'
```

## A825 示例

字段映射：

| tran_code | service_code | std_field_name | sop_field_name | bizjson_field_name |
| --- | --- | --- | --- | --- |
| A825 | S030030014FcyCollCrspBnkLkgQry | currency_id | HUOBDH | CurrencyId |
| A825 | S030030014FcyCollCrspBnkLkgQry | link_info | FAB251 | FcyCollCrspBnkLkg |

原始差异：

| mesg_seq | message_type | raw_field_name | orig_value | dest_value |
| --- | --- | --- | --- | --- |
| 11111111111 | bizjson | CurrencyId | 111 | 222 |
| 11111111111 | bizjson | FcyCollCrspBnkLkg | `A1/B1` | `A/B` |
| 11111111114 | sop | HUOBDH | 111 | 222 |
| 11111111114 | sop | FAB251 | `A1/B1` | `A/B` |

归一后两个流水的签名一致：

```text
currency_id:111->222|link_info:A1/B1->A/B
```

因此生成一个字段级问题组：

| sample_type | tran_code | service_code | semantic_field_names | affected_tran_count |
| --- | --- | --- | --- | --- |
| FIELD_DIFF | A825 | S030030014FcyCollCrspBnkLkgQry | `currency_id,link_info` | 2 |

样本流水保留不同报文字段名：

| mesg_seq | message_type | std_field_name | raw_field_name |
| --- | --- | --- | --- |
| 11111111111 | bizjson | currency_id | CurrencyId |
| 11111111111 | bizjson | link_info | FcyCollCrspBnkLkg |
| 11111111114 | sop | currency_id | HUOBDH |
| 11111111114 | sop | link_info | FAB251 |

## 错误处理

- 配置缺失不失败，写入 `UNCONFIGURED_SERVICE` 或 `UNMAPPED`。
- 原始字段记录找不到交易事实时不进入正常问题组，记录孤儿字段差异数量。
- 批量写入失败时整个批次标记 `FAILED`。
- 重跑同一个业务日期会创建新批次；重跑同一个批次 ID 会先清理旧结果。

## 性能策略

- 原始表读取使用简单 SQL 和有序流式处理。
- 禁止 offset/limit 分页查询原始大表。
- 所有原始大表读取使用游标或 JDBC 流式查询。
- 读取连接设置 `autoCommit=false`，并设置合理 `fetchSize`。
- 配置表一次加载进内存。
- 字段差异按 `mesg_seq + conv_index + conv_cindex` 连续聚合，避免把全量字段明细长期放在内存。
- 问题组聚合状态保留在内存，内存对象只保存分组统计、签名和最多 20 个样本引用，不保存全量字段明细。
- 样本字段明细只对被抽中的样本保留并写入。
- 若单批次问题组数量超过内存阈值，批量失败并提示缩小业务日期范围或增加分片条件；不自动退化为复杂 SQL 中间表方案。
- 写入使用批量提交。
- SQL 只承担筛选、排序和批量写入，不承担复杂签名和语义归并。

## 测试策略

- 单元测试字段映射匹配：
  - SOP 字段匹配。
  - SOAP 字段匹配。
  - BizJSON 字段匹配。
  - 兜底匹配。
  - 未映射字段。
- 单元测试 A825 场景：
  - `11111111111` 和 `11111111114` 归入同一字段组合问题组。
  - 样本字段明细保留各自原始字段名和报文类型。
- 单元测试汇总口径：
  - 交易总数不受 `ana_tran_catalog` 缺失影响。
  - `fully_matched_count` 排除有字段差异的成功交易。
- 集成测试批次可重跑：
  - 同一输入生成稳定分组和样本。
  - 清理同批次旧结果后重新写入。
- 回归测试页面和导出：
  - 分组页能展示语义字段组合。
  - 明细页能展示样本流水和字段明细。

## 实施顺序

1. 增加或调整 DDL，支持语义签名、配置状态、样本字段明细。
2. 增加批量处理领域模型。
3. 实现配置快照和字段语义归一。
4. 实现交易事实读取和汇总统计。
5. 实现响应码候选。
6. 实现字段差异按流水组合候选。
7. 实现问题归并和稳定抽样。
8. 实现批量写入。
9. 调整查询服务、页面和导出。
10. 用 A825 样例和现有测试验证。
