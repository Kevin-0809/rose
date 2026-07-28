# 报表明细导出汇总信息 Sheet 重设计

## 目标

替换“回放报表明细”Excel 中第一个 `汇总信息` Sheet 的内容结构，按参考图片展示上一批次与本批次的领域级统计。

下载入口、文件生成方式和后续领域明细 Sheet 保持不变。新的 `汇总信息` Sheet 只替换原汇总页内容，不新增额外 Sheet。

## 批次选择

当前批次为下载路径中的 `batchId`，例如 `RPT20260728-132831-6664`。Sheet 中的“批次”列直接使用数据库中的完整批次号，不做截断或重新生成。

上一批次按以下规则查找：

```sql
select batch_id
from ana_report_export_command
where status = 'SUCCEEDED'
  and (created_time, command_id) < (:currentCreatedTime, :currentCommandId)
order by created_time desc, command_id desc
limit 1
```

若没有上一批次，上一批次区域保留表头，不输出领域数据行，仅输出空合计行；本批次区域仍正常输出。

## Sheet 结构

Sheet 名称继续使用 `汇总信息`。

Sheet 分为上下两段：

- 上一批次：绿色表头，展示上一成功批次的统计。
- 本批次：粉色表头，展示当前批次统计，并额外展示重复问题、重复率、上轮问题解决率。

两段都按领域输出数据行，并在末尾输出合计行。合计行的百分比按总分子与总分母重新计算，不对领域百分比做平均。

交易状态列顺序为：

1. `528成功/CCBS失败`
2. `528失败/CCBS成功`
3. `二者均失败响应码一致`
4. `二者均失败响应码不一致`
5. `二者均成功`

`已解决问题分类统计（待验证）` 下的 `迁移问题`、`防腐问题`、`功能问题`、`新核心下线`、`其他问题`，以及 `问题解决进度`，所有轮次均留空，仅保留列位与样式。

## 指标口径

基础交易状态字段来自 `ana_report_export_summary`：

- `comp_result_1_count`：`528成功/CCBS失败`
- `comp_result_2_count`：`528失败/CCBS成功`
- `comp_result_3_count`：`二者均失败响应码一致`
- `comp_result_8_count`：`二者均失败响应码不一致`
- `comp_result_4_count`：`二者均成功`

成功率：

```text
(comp_result_4_count + comp_result_3_count) / sent_transaction_count
```

比对通过率：

```text
(field_pass_transaction_count + comp_result_3_count) / sent_transaction_count
```

其中 `field_pass_transaction_count` 表示“二者均成功且在 `tss_field_comp` 表中无记录的交易个数总和”。

问题总数：

```text
transaction_issue_count + field_issue_count
```

重复问题：

```text
count(ana_tran_diff_tracking_export where historical_occurrence_count > 0)
+ count(ana_field_diff_tracking_export where historical_occurrence_count > 0)
```

重复率：

```text
duplicate_issue_count / issue_total_count
```

上轮问题解决率：

```text
(上一批次 issue_total_count - 本批次 duplicate_issue_count) / 上一批次 issue_total_count
```

所有百分比在 Excel 中显示为保留两位小数的百分比。分母为 0 时显示 `0.00%`。

## 数据固化

为避免历史批次导出时二次统计，`汇总信息` Sheet 主要读取 `ana_report_export_summary` 的固化结果。当前批次生成时负责把新指标写入汇总表。

扩展 `ana_report_export_summary`：

```sql
alter table ana_report_export_summary
    add column if not exists field_pass_transaction_count bigint not null default 0,
    add column if not exists comparison_pass_rate numeric(12,8) not null default 0,
    add column if not exists transaction_issue_count bigint not null default 0,
    add column if not exists field_issue_count bigint not null default 0,
    add column if not exists issue_total_count bigint not null default 0,
    add column if not exists duplicate_issue_count bigint not null default 0;
```

字段含义：

- `field_pass_transaction_count`：二者均成功且无字段差异的交易数。
- `comparison_pass_rate`：比对通过率。
- `transaction_issue_count`：交易级差异总数。
- `field_issue_count`：字段级差异总数。
- `issue_total_count`：问题总数。
- `duplicate_issue_count`：重复问题数。

`success_rate` 继续保留，但其口径明确为“成功率”，即 `(二者均成功 + 二者均失败响应码一致) / 发送交易量`。

## 生成流程

`ReportExportBatchRunner` 在当前批次生成过程中完成以下统计并写入 `ana_report_export_summary`：

1. 读取 `tss_tran_comp`、`tss_retcode_comp` 与交易目录，按领域汇总发送交易量、覆盖接口数、五类交易状态。
2. 统计 `field_pass_transaction_count`：只统计 `comp_result = 4` 的交易，并以 `mesg_seq + orig_cdate + conv_index + conv_cindex` 判断 `tss_field_comp` 中是否没有对应字段差异记录。
3. 生成交易级明细和字段级明细后，按领域统计两张明细表行数，得到 `transaction_issue_count`、`field_issue_count`、`issue_total_count`。
4. 在问题台账快照写入后，按领域统计两张明细表中 `historical_occurrence_count > 0` 的行数，得到 `duplicate_issue_count`。
5. 更新 `ana_report_export_summary` 的新增字段与比率字段。

如果实现时现有流程先写汇总、后写明细和台账快照，则需要在台账快照完成后补充一次汇总扩展字段更新，避免重复问题数取不到快照值。

## Excel 输出

`ReportExportExcelService.writeSummary(...)` 改为读取当前批次和上一批次的 `ana_report_export_summary`。查询结果按领域排序，写入两段表格。

本批次区域比上一批次区域额外包含：

- `重复问题`
- `重复率`
- `上轮问题解决率`

上一批次区域不展示这些本批次专属列。

生成批次时固化 `comparison_pass_rate`。Excel 服务读取领域行的固化比率；合计行按固化的分子字段重新计算，不读取或平均领域行比率。

## 兼容性

旧历史批次可能缺少新增字段的真实数据。DDL 默认值为 0，旧批次下载时不回扫历史原始表。

新逻辑上线后生成的批次会带完整扩展统计。上一批次如果是旧批次，则按已固化字段输出，可显示 0 值；不为了补齐旧批次而重新统计 `tss_*` 或历史明细。

## 测试

新增或更新测试覆盖：

- 批次号在 `汇总信息` Sheet 中使用完整 `batch_id`。
- `汇总信息` Sheet 名称不变，且仍为第一个 Sheet。
- 交易状态表头拆分为响应码一致与不一致两列，并位于 `二者均成功` 前。
- 成功率按 `(comp_result_4_count + comp_result_3_count) / sent_transaction_count` 输出两位小数百分比。
- 比对通过率按 `(field_pass_transaction_count + comp_result_3_count) / sent_transaction_count` 输出两位小数百分比。
- 问题总数等于交易级差异总数加字段级差异总数。
- 本批次重复问题按 `historical_occurrence_count > 0` 统计。
- 重复率和上轮问题解决率按总分子与总分母计算。
- 合计行百分比不平均领域百分比。
- 上一批次选择当前批次前最近 `SUCCEEDED` 批次，忽略失败、执行中和当前批次之后的记录。
- `已解决问题分类统计（待验证）` 与 `问题解决进度` 保持空白。
