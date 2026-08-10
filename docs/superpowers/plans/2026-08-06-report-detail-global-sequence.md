# 日报明细 Excel 全局流水号 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在普通日报和未脱敏日报的每个领域明细 Sheet 中，在 `流水号` 后输出按尾部 26 位关联得到的 `全局流水号`，并保持周报、汇总页、延迟分布页和全量问题清单不变。

**Architecture:** 将日报领域查询与全量问题清单查询的列投影、表头和行写入参数化隔离。日报查询以跟踪表别名 `detail` 为外层行，通过相关子查询从 `msg_flow_log_request` 取 `global_seq_no`，按 `txn_time ASC NULLS LAST, source_ip ASC` 限定第一条；未命中自然得到空值并写成空单元格。

**Tech Stack:** Java 17、Spring JDBC、PostgreSQL/openGauss SQL、Apache POI SXSSF、JUnit 5、AssertJ、H2 PostgreSQL 模式。

## Global Constraints

- 只改变 `/report-exports/{batchId}/excel`、`/daily`、`/daily-raw` 的领域明细 Sheet。
- `全局流水号` 必须紧跟 `流水号`，未命中或 `global_seq_no` 为 NULL 时留空。
- `tran_seq_no` 使用 `right(..., 26)` 与 `msg_flow_log_request.trans_id` 匹配。
- 全量问题清单、周报、汇总和延迟分布保持原列与行为。
- 不修改数据库表结构、迁移写入逻辑或 HTTP 路径。

---

### Task 1: 为日报列契约和匹配行为写失败测试

**Files:**
- Modify: `src/test/java/com/spdb/report/ReportExportExcelServiceTest.java`
- Modify: test setup DDL in the same file to create `msg_flow_log_request`

**Interfaces:**
- Consumes: existing `ReportExportExcelService.stream`, `streamRawDaily`, `streamFullIssueList`.
- Produces: executable assertions for the 26-column daily contract and unchanged 25-column full issue contract.

- [ ] **Step 1: Add request table fixture and daily assertions**

Create `msg_flow_log_request(source_ip varchar(64), trans_id varchar(64), txn_time bigint, global_seq_no varchar(64))`. Add rows covering a long `tran_seq_no`, unmatched sequence, NULL global sequence, duplicate `trans_id` with different times/IPs, one transaction row and one field row. Assert the daily and raw daily Sheet header at index 17 is `流水号`, index 18 is `全局流水号`, and the values at index 18 follow the lookup rule.

```java
assertThat(sheet.getRow(0).getCell(17).getStringCellValue()).isEqualTo("流水号");
assertThat(sheet.getRow(0).getCell(18).getStringCellValue()).isEqualTo("全局流水号");
assertThat(sheet.getRow(1).getCell(18).getStringCellValue()).isEqualTo("GLOBAL-EARLIEST");
assertThat(sheet.getRow(2).getCell(18).getStringCellValue()).isBlank();
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn "-Dtest=ReportExportExcelServiceTest" test`

Expected: FAIL because the current daily header has no `全局流水号`, the query does not expose `global_seq_no`, and the request fixture table is not yet used by production SQL.

### Task 2: Isolate daily and full-issue column contracts

**Files:**
- Modify: `src/main/java/com/spdb/report/ReportExportExcelService.java`
- Test: `src/test/java/com/spdb/report/ReportExportExcelServiceTest.java`

**Interfaces:**
- Produces: `DAILY_DETAIL_HEADERS` with `全局流水号` after `流水号`; existing base headers remain available to full issue export.
- Produces: `writeDetailHeader(sheet, headers)`, `finishDetailSheet(sheet, headerCount)`, and row-writing behavior selected by the daily/full-issue caller.

- [ ] **Step 1: Add the daily header variant and parameterize sheet finishing**

Keep the existing 25 base headers for `全量问题清单`; derive a 26-header daily array by inserting `全局流水号` immediately after the `流水号` element. Pass the relevant header count to auto-filter and column-width setup.

- [ ] **Step 2: Run the focused test and verify the header failure is resolved**

Run: `mvn "-Dtest=ReportExportExcelServiceTest" test`

Expected: the header assertions pass, while lookup-value assertions still fail until Task 3.

### Task 3: Add correlated global-sequence lookup to daily streaming queries

**Files:**
- Modify: `src/main/java/com/spdb/report/ReportExportExcelService.java`
- Test: `src/test/java/com/spdb/report/ReportExportExcelServiceTest.java`

**Interfaces:**
- Consumes: `global_seq_no` query alias in both transaction and field daily streams.
- Produces: a `writeDetailRowCells` path that writes `global_seq_no` at column 18 only for daily rows.

- [ ] **Step 1: Alias the daily source table and add the correlated projection**

Use this projection in `streamDetails` for both tracking tables:

```sql
(
  select request.global_seq_no
    from msg_flow_log_request request
   where request.trans_id = right(detail.tran_seq_no, 26)
   order by request.txn_time asc nulls last, request.source_ip asc
   limit 1
) as global_seq_no
```

Keep raw-field columns conditional exactly as before. Leave `streamAllDetails` unchanged and without the new projection.

- [ ] **Step 2: Write the optional cell in the shared row mapper**

Pass a `dailyDetail` boolean (or equivalent existing-local parameter) from `streamDetails`; after writing `tran_seq_no`, write `text(rs.getObject("global_seq_no"))` only for daily rows, then continue with `defect_fix_date` and the remaining fields. For full issue rows retain the original 25-value mapping and never read `global_seq_no`.

- [ ] **Step 3: Run focused tests and verify GREEN**

Run: `mvn "-Dtest=ReportExportExcelServiceTest" test`

Expected: all daily lookup, raw daily, duplicate ordering, null/unmatched, transaction/field, and unchanged full issue assertions pass.

### Task 4: Regression verification and packaging

**Files:**
- No additional source files; inspect `src/main/java/com/spdb/report/ReportExportExcelService.java` and its test.

- [ ] **Step 1: Run the full Maven test suite**

Run: `mvn test`

Expected: all existing and new tests pass with no compilation errors.

- [ ] **Step 2: Inspect the generated workbook contract through tests**

Confirm daily and raw daily are 26 columns, full issue list is 25 columns, and no weekly/summary assertions changed.

- [ ] **Step 3: Create a source ZIP containing only the implemented Rose source and tests**

Run from `/Users/java`:

```bash
zip -r rose-report-global-sequence-20260806.zip rose/src/main/java/com/spdb/report/ReportExportExcelService.java rose/src/test/java/com/spdb/report/ReportExportExcelServiceTest.java rose/docs/superpowers/plans/2026-08-06-report-detail-global-sequence.md
```

Verify the archive contains the modified Java source, focused test, and implementation plan, and report its absolute path and SHA-256.
