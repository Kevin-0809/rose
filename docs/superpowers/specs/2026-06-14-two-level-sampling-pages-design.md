# Two-Level Sampling Pages Design

## Goal

采样结果只保留两类问题：交易级差异和字段级差异。交易级差异统一使用 `RETURN_CODE`，字段级差异使用 `FIELD_DIFF`。页面按这两类分别展示，并支持按当前查询条件导出。

## Classification

- `RETURN_CODE` 表示交易级差异，包含原 `TRAN_RESULT` 覆盖的 `comp_result in ('1','2','8')`，以及 `tss_retcode_comp` 中的响应码差异。
- `FIELD_DIFF` 表示字段级差异，来源于 `comp_result = '4'` 且字段比对存在差异的流水。
- 不再新增或展示 `TRAN_RESULT`。旧库约束和新建约束都升级为只允许 `RETURN_CODE`、`FIELD_DIFF`。

## Sampling Write Model

采样批量继续用 Java 聚合和 JDBC 流式读取源表。交易结果差异和响应码差异都写入 `ana_sample_group.sample_type = 'RETURN_CODE'`、`ana_sample_detail.sample_type = 'RETURN_CODE'`。为了区分具体来源，语义签名保留 `TRAN_RESULT:<comp_result>` 或 `RETURN_CODE:<orig>-><dest>`。

字段级差异写入 `FIELD_DIFF`。字段映射信息从 `ana_field_mapping` 贯穿到明细和字段明细：

- `sop_field_name`
- `soap_field_name`
- `bizjson_field_name`
- `field_cn_name`

当一个字段级样本包含多个字段时，`ana_sample_detail` 中这些字段使用逗号拼接的去重汇总，`ana_sample_detail_field` 保留逐字段明细。

## Pages

新增两个主入口：

- `/samples/transaction-diffs`：交易级差异，只查询 `RETURN_CODE`。展示业务日期、批次、交易码、服务码、报文类型、流水号、交易结果、528/CCBS 响应码和响应描述、责任人、数量。支持 `/samples/transaction-diffs/export`。
- `/samples/field-diffs`：字段级差异，只查询 `FIELD_DIFF`。展示字段映射、字段中文名、528 值、CCBS 值、报文类型、流水号、责任人等。支持 `/samples/field-diffs/export`。

现有通用分组/明细查询服务保留作为底层能力，页面入口调整为更明确的两类结果，避免用户在同一张表里混看交易级和字段级差异。

## Testing

- 批量测试验证不再生成 `TRAN_RESULT`，交易结果差异和响应码差异都归入 `RETURN_CODE`。
- 批量测试验证 `ana_sample_detail` 补齐 SOP、SOAP、BizJSON、字段中文名。
- DDL 测试验证 check constraint 只允许 `RETURN_CODE`、`FIELD_DIFF`。
- 页面模板测试验证新增交易级/字段级页面及导出入口。
- 全量 `mvn test` 作为最终回归。
