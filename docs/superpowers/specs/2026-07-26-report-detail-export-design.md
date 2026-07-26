# 报表明细导出设计

## 目标

在“执行分析”中新增独立的“报表明细导出”功能。每次回放完成后，用户发起一个批量任务，任务直接读取当前的 `tss_retcode_comp`、`tss_tran_comp` 和 `tss_field_comp` 全部数据，生成领域汇总报表、交易级问题明细和字段级问题明细。

该流程不再依赖采样任务、`ana_sampling_command`、`ana_sampling_summary`、`ana_tran_diff_result` 或 `ana_field_diff_result`。现有页面和数据保留，以便后续单独废弃。

`orig_cdate` 不作为新功能的输入或筛选条件。每次回放后，相关 `tss_*` 源表会被清理并重建，因此任务批次号和任务执行日期是结果追溯边界。

## 架构

新增“分析报表任务”模块，包含命令服务、异步执行器、批量执行器、汇总查询服务和 Web 控制器。

- 菜单入口：`执行分析 > 报表明细导出`。
- 页面创建不带日期参数的任务，并展示任务状态与历史结果。
- 后台任务读取当前三张 `tss_*` 表，生成独立批次号对应的全部结果。
- 汇总写入新表；两类问题明细分别写入既有的 `ana_tran_diff_tracking_export` 和 `ana_field_diff_tracking_export`。
- 旧采样、汇总历史和旧明细页面不参与新流程。

## 数据模型

### 分析报表任务表

新增 `ana_report_export_command`：

- `command_id`：主键。
- `batch_id`：唯一分析批次号。
- `report_date`：任务执行当天的自然日期，格式 `yyyyMMdd`。
- `status`：`PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`。
- `started_time`、`ended_time`、`error_message`、`created_time`、`updated_at`。

### 汇总报表表

新增 `ana_report_export_summary`，以 `(batch_id, module_name)` 唯一：

- `batch_id`、`report_date`、`module_name`。
- `covered_528_interface_count`、`sent_transaction_count`。
- `comp_result_1_count`、`comp_result_2_count`、`comp_result_3_count`、`comp_result_4_count`、`comp_result_8_count`。
- `success_rate`：保留为数值比率，页面以百分比显示。
- `diff_528_field_count`。
- `created_time`、`updated_at`。

领域来自 `ana_tran_catalog.module_name`。无法关联交易目录的源记录归入“未配置领域”，确保汇总与明细不丢数据。

### 既有明细表

两张既有表不调整问题跟踪字段：

- `ana_tran_diff_tracking_export` 保存交易级问题明细。
- `ana_field_diff_tracking_export` 保存字段级问题明细。

任务批次号写入两表的 `source_batch_id`。登记日期、业务日期和导出时间均使用 `report_date` 与任务启动时间；问题处理预留字段保持空值。

## 执行流程与事务

1. 用户创建任务，服务生成批次号并写入 `PENDING` 命令。
2. 异步执行器将命令原子更新为 `RUNNING`，重复调度不重复执行。
3. 执行器在一个数据库事务内写入领域汇总、交易级明细和字段级明细。
4. 事务提交后命令更新为 `SUCCEEDED`。
5. 任一步失败时事务回滚，批次不保留任何汇总或明细行；命令更新为 `FAILED` 并记录长度受限的错误信息。

相同源数据可重复执行。每次生成新的 `batch_id`，历史批次与当前 `tss_*` 表数据无耦合。

## 汇总统计规则

汇总报表按“批次 + 领域”一行展示，字段及口径如下：

| 列 | 规则 |
| --- | --- |
| 业务日期 | 任务执行当天 `report_date` |
| 批次 | `batch_id` |
| 领域 | 匹配到的 `module_name`，否则“未配置领域” |
| 覆盖528接口 | 当前领域内，`tss_tran_comp.dest_trcd` 按 `&` 截取第一段后的服务码去重数 |
| 发送交易量 | 当前领域内 `tss_tran_comp` 行数 |
| 528成功/CCBS失败 | `comp_result = 1` 行数 |
| 528失败/CCBS成功 | `comp_result = 2` 行数 |
| 二者均失败 | `comp_result = 3` 行数 |
| 二者均成功 | `comp_result = 4` 行数 |
| 二者均失败不一致 | `comp_result = 8` 行数 |
| 成功率 | `(comp_result_3_count + comp_result_4_count) / sent_transaction_count`；分母为零时为 `0` |
| 差异字段数（按528字段去重） | 当前领域内字段表规范化后的 `orig_field_name` 去重数 |

`comp_result` 的其他状态不在汇总表中单列，但不会阻碍任务完成。

## 交易级问题明细

交易级以 `tss_tran_comp` 为驱动，仅处理 `comp_result in ('1', '2', '3', '7', '8')` 的记录。

使用流水号及转换关联键左关联 `tss_retcode_comp`，并采用以下归一规则：

1. 存在响应码明细时，取 `tss_retcode_comp.service_code` 的 `&` 前第一段，按“服务码 + 原响应码 + 目标响应码”归一。
2. 不存在响应码明细时，按“`dest_trcd + comp_result`”归一。
3. 两种分支在内部使用不同的归一类别，避免键文本相同导致错误合并。
4. 每个归一组只写入一条 `ana_tran_diff_tracking_export`。代表交易按流水号、转换索引稳定排序取首条。

交易名称、领域和负责人从交易目录补齐；无法匹配时保留源表字段并归入“未配置领域”。响应码相关字段仅在第一种归一分支中填充。

`comp_result` 为 `0`、`4`、`5`、`6` 的交易不进入交易级问题明细。

## 字段级问题明细

字段级仅读取 `tss_field_comp`，不关联 `tss_tran_comp`。

归一规则：

1. 服务码取 `dest_trcd` 的 `&` 前第一段。
2. `orig_field_name` 含 `.` 时仅保留分隔后的前两段。例如 `items.0.amount` 规范化为 `items.0`。
3. 归一键为“服务码 + 规范化原字段名 + `dest_field_name`”。
4. 每个归一组写入一条 `ana_field_diff_tracking_export`；代表记录按流水号、转换索引、字段序号稳定排序取首条。

交易目录和字段映射仅用于补齐交易名称、领域、负责人、SOP/SOAP/BizJSON/中文字段名及映射状态，不参与归一。字段表仅包含 `comp_result = 0` 的差异记录，因此全部源行均属于字段级问题候选。

## 页面

新页面位于 `/report-exports`：

- “执行报表明细导出”按钮创建任务。
- 任务列表显示批次、业务日期、状态、开始时间、结束时间、错误信息和汇总查看入口。
- 汇总详情显示与目标表一致的领域统计列。
- 交易级、字段级入口分别按 `source_batch_id` 查询既有两张跟踪明细表。
- 新页面不提供 TXT 或 ZIP 下载；“导出”在此处表示将源数据批量归一并落库。

## 验证

测试至少覆盖：

- 任务创建、异步状态迁移、重复任务隔离和失败回滚。
- 三张 `tss_*` 表的无日期筛选直接读取。
- 交易级两种归一分支、排除状态 `0/4/5/6`、代表记录稳定选择。
- 字段级不关联交易表、数组字段名归一和目标字段名参与归一。
- 各领域汇总列、成功率公式、零分母及“未配置领域”。
- 菜单入口、任务页面、汇总页面和两个明细跳转。
