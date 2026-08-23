# 报表导出汇总页尾部列删除 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让日报、未脱敏日报和周报的“汇总信息”页在“问题总数”列结束，不再生成 N 列及之后的人工维护列。

**Architecture:** 保持现有 `ReportExportExcelService` 汇总查询和导出入口不变，仅收窄汇总 Sheet 的渲染契约。上下批次区块统一使用 A-M 共 13 列，表头、标题、公式说明、数据行、合计行及列宽全部按同一末列索引 12 渲染。

**Tech Stack:** Java 17, Spring Boot, Apache POI SXSSF, JUnit 5, AssertJ, Maven。

---

### Task 1: 更新汇总 Sheet 的失败测试

**Files:**
- Modify: `src/test/java/com/spdb/report/ReportExportExcelServiceTest.java`

- [ ] **Step 1: 调整已有日报汇总断言**

在现有汇总 Sheet 测试中，将上一批次和本批次的表头、合并区域及数据行断言改为要求末列为零基索引 12：

```java
assertThat(sheet.getRow(previousHeaderRow).getCell(12).getStringCellValue()).isEqualTo("问题总数");
assertThat(sheet.getRow(currentHeaderRow).getCell(12).getStringCellValue()).isEqualTo("问题总数");
assertMergedRegion(sheet, previousHeaderRow, previousHeaderRow, 0, 12);
assertMergedRegion(sheet, currentHeaderRow, currentHeaderRow, 0, 12);
assertMergedRegion(sheet, previousFormulaRow, previousFormulaRow, 0, 12);
assertMergedRegion(sheet, currentFormulaRow, currentFormulaRow, 0, 12);
assertThat(sheet.getRow(previousDataRow).getCell(13)).isNull();
assertThat(sheet.getRow(currentDataRow).getCell(13)).isNull();
```

同时删除旧测试中对“上一批次未解决问题数量”“上轮问题解决率”、已解决问题分类和“问题解决进度”的正向断言，并要求公式说明不包含“上轮问题解决率”。

- [ ] **Step 2: 调整周报汇总断言**

在已有周报导出测试中对“汇总信息”Sheet 应用相同契约，确认上下周期区块均不存在第 14 列及之后的单元格和合并区域。

- [ ] **Step 3: 运行定向测试确认 RED**

Run: `mvn -Dtest=ReportExportExcelServiceTest test`

Expected: 失败，失败原因是当前实现仍在 N 列及之后创建表头、空白单元格或合并区域。

### Task 2: 收窄汇总渲染实现

**Files:**
- Modify: `src/main/java/com/spdb/report/ReportExportExcelService.java:159-307`

- [ ] **Step 1: 统一汇总区块末列和公式说明末列**

将 `writeSummary` 的列宽循环限制为 `i < 13`；将 `writeSummarySection` 和 `writeSummaryFormulas` 的 `lastColumn` 固定为 12。

- [ ] **Step 2: 删除尾部表头渲染**

保留“问题总数”表头，移除 `current` 和 `previous` 分支中 N 列及之后的合并单元格以及 `writeSolvedIssueSubHeaders` 调用；删除不再使用的 `writeSolvedIssueSubHeaders` 方法。

- [ ] **Step 3: 删除尾部数据和合计写入**

在 `writeSummaryDataRow` 与 `writeSummaryTotalRow` 中保留到索引 12 的写入，移除所有 `blankCells` 对索引 13 及之后的写入。

- [ ] **Step 4: 运行定向测试确认 GREEN**

Run: `mvn -Dtest=ReportExportExcelServiceTest test`

Expected: PASS，且没有公式错误或 POI 合并区域异常。

### Task 3: 完成回归验证

**Files:**
- No additional files.

- [ ] **Step 1: 运行报表导出相关测试**

Run: `mvn -Dtest=ReportExportExcelServiceTest,ReportExportControllerTest,ReportExportTemplateTest test`

Expected: PASS。

- [ ] **Step 2: 运行完整测试套件**

Run: `mvn test`

Expected: 退出码 0，所有测试通过。

- [ ] **Step 3: 检查工作区差异**

Run: `git diff --check` and `git status --short`

Expected: 无空白错误；仅包含本次测试和实现相关修改，保留用户原有未跟踪文件。
