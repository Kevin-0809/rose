# 接口比对明细增加平均耗时设计

## 目标

在导出 Excel 的“接口比对明细”Sheet 中，为每个接口增加 528 和 CCBS 的平均交易耗时。

## 数据口径

- 数据来源为 `tss_dest_pkg`。
- 使用 `split_part(orig_trcd, '&', 1)` 取得 S 码，并与接口汇总的 `service_code` 关联。
- `dest_sys = '528'` 时计算 528 平均耗时，`dest_sys = 'ccbs'` 时计算 CCBS 平均耗时。
- 对 `tran_take_time` 使用数据库 `avg` 聚合；`mesg_seq` 是唯一流水号，不通过其他表追加去重。
- 没有匹配记录或耗时为空时，Excel 对应单元格保持空白。
- 合计行不填平均耗时，避免将不同接口的平均值相加造成错误统计。

## 实现方案

`ReportExportExcelService.interfaceSummaryRows` 在读取接口汇总时，增加一个按 S 码聚合的耗时子查询或 CTE，并左连接到接口汇总表。查询一次完成所有接口的平均值计算，避免按接口逐条查询。接口汇总记录仍作为主表，保证没有耗时数据的接口也能正常导出。

`InterfaceSummaryRow` 增加两个可空数值字段：`average528TakeTime` 和 `averageCcbsTakeTime`。写入 Sheet 时在“发送交易量”之后新增“528平均耗时”“CCBS平均耗时”两列，使用数值单元格；后续比例列索引整体顺延。列宽、筛选范围和冻结窗格按新增列数同步调整。

## 测试

在 `ReportExportExcelServiceTest` 中增加覆盖：

1. `orig_trcd` 含 `&` 时按第一段 S 码正确归属接口。
2. 528 与 CCBS 分别计算平均值。
3. 同一接口无某个目标系统耗时时，对应 Excel 单元格为空。
4. 新表头、数据列和合计行列位置正确，原有成功率和通过率仍保持正确。

