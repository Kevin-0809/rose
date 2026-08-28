# 接口比对明细平均耗时 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在接口比对明细 Excel Sheet 中增加每个接口的 528/CCBS 平均交易耗时。

**Architecture:** 在 `ReportExportExcelService` 的接口汇总查询中通过一次 CTE 聚合 `tss_dest_pkg`，按 `orig_trcd` 的 `&` 前第一段关联 S 码；接口汇总仍为主表，耗时缺失时保留空值。扩展行记录和 Excel 列写入逻辑，合计行耗时留空。

**Tech Stack:** Java 17、Spring JDBC `NamedParameterJdbcTemplate`、Apache POI、JUnit 5、AssertJ、H2 PostgreSQL mode。

---

### Task 1: 为平均耗时写入失败测试

**Files:**
- Modify: `src/test/java/com/spdb/report/ReportExportExcelServiceTest.java`

- [ ] **Step 1: 扩展测试数据库表**

在 `setUp()` 增加 `tss_dest_pkg(mesg_seq varchar(64), orig_trcd varchar(200), dest_sys varchar(32), tran_take_time decimal(18,6))`。

- [ ] **Step 2: 增加失败测试数据和断言**

在 `dailyExportAddsInterfaceComparisonSheetWithOwnersAndRates` 中插入 `SVC1&soap` 的 528 耗时 10、30 和 CCBS 耗时 20；插入一个 `SVC2&soap` 且只有 528 耗时的接口汇总行，验证 `SVC1` 两列平均值为 20、20，`SVC2` 的 CCBS 单元格为空，并验证新表头位于第 8、9 列、成功率顺延到第 16 列、通过率顺延到第 17 列。测试先运行并确认因当前实现缺少列/表查询而失败。

- [ ] **Step 3: 运行定向测试确认 RED**

运行 `mvn -q -Dtest=ReportExportExcelServiceTest#dailyExportAddsInterfaceComparisonSheetWithOwnersAndRates test`，预期失败原因是新列尚未生成。

### Task 2: 实现耗时聚合和 Excel 列

**Files:**
- Modify: `src/main/java/com/spdb/report/ReportExportExcelService.java`

- [ ] **Step 1: 扩展接口行模型和查询映射**

在 `InterfaceSummaryRow` 增加两个 `BigDecimal` 字段；`interfaceSummaryRows` 使用按 S 码聚合的 CTE：`split_part(orig_trcd, '&', 1)`，分别 `avg(case when dest_sys = '528' ...)` 和 `avg(case when dest_sys = 'ccbs' ...)`，再左连接接口汇总表。

- [ ] **Step 2: 写入两列平均耗时**

表头在“发送交易量”后增加“528平均耗时”“CCBS平均耗时”；数据行使用数值单元格，成功率和通过率列索引调整为 16、17；合计行对应耗时列留空，其余汇总列同步顺延；自动筛选和列宽按新表头长度工作。

- [ ] **Step 3: 运行定向测试确认 GREEN**

运行同一 Maven 定向测试，预期通过。

### Task 3: 回归验证和提交

**Files:**
- Test: `src/test/java/com/spdb/report/ReportExportExcelServiceTest.java`
- Modify: `src/main/java/com/spdb/report/ReportExportExcelService.java`

- [ ] **Step 1: 运行报表相关测试**

运行 `mvn -q -Dtest=ReportExportExcelServiceTest,ReportExportBatchRunnerTest,ReportExportCommandServiceTest test`，预期全部通过。

- [ ] **Step 2: 检查差异**

运行 `git diff --check`，确认无空白错误；检查新增 SQL 只依赖 `tss_dest_pkg` 和现有接口汇总表。

- [ ] **Step 3: 提交实现**

运行 `git add src/main/java/com/spdb/report/ReportExportExcelService.java src/test/java/com/spdb/report/ReportExportExcelServiceTest.java && git commit -m "feat: add interface average take time"`。

