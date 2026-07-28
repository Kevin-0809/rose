# Report Export Summary Sheet Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `汇总信息` Sheet in report export Excel with the approved previous/current batch summary layout and metrics.

**Architecture:** Persist all summary metrics in `ana_report_export_summary` during report generation, then have the Excel service read current and previous successful batches from persisted summary rows. Existing domain detail Sheets remain unchanged.

**Tech Stack:** Java 17, Spring JDBC, Apache POI SXSSFWorkbook, PostgreSQL DDL, H2-backed JUnit 5 tests, AssertJ, Maven.

---

## File Structure

- Modify `db/ddl.sql`: add persisted summary metric columns and comments to `ana_report_export_summary`.
- Modify `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`: assert the new DDL columns exist.
- Modify `src/main/java/com/spdb/report/ReportExportBatchRunner.java`: compute and persist `field_pass_transaction_count`, `comparison_pass_rate`, issue counts, and duplicate issue counts.
- Modify `src/test/java/com/spdb/report/ReportExportBatchRunnerTest.java`: cover persisted field-pass, problem-total, duplicate, and rate metrics.
- Modify `src/main/java/com/spdb/report/ReportExportSummaryRow.java`: add summary fields needed by page/detail callers.
- Modify `src/main/java/com/spdb/report/ReportExportCommandService.java`: select and map the new summary fields.
- Modify `src/test/java/com/spdb/report/ReportExportCommandServiceTest.java`: keep summary mapping tests aligned with the expanded row.
- Modify `src/main/java/com/spdb/report/ReportExportExcelService.java`: replace old summary Sheet rendering with previous/current batch sections.
- Modify `src/test/java/com/spdb/report/ReportExportExcelServiceTest.java`: verify Sheet layout, previous-batch lookup, metrics, blank issue-resolution columns, and detail Sheet preservation.

---

### Task 1: Persist Summary Schema

**Files:**
- Modify: `db/ddl.sql`
- Modify: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] **Step 1: Write the failing DDL test**

Add assertions to `DatabaseScriptLayoutTest.ddlContainsReportExportCommandAndSummaryTables`.

```java
assertThat(ddl).contains(
        "field_pass_transaction_count bigint not null default 0",
        "comparison_pass_rate numeric(12,8) not null default 0",
        "transaction_issue_count bigint not null default 0",
        "field_issue_count bigint not null default 0",
        "issue_total_count bigint not null default 0",
        "duplicate_issue_count bigint not null default 0",
        "comment on column ana_report_export_summary.field_pass_transaction_count is '二者均成功且无字段差异交易数'",
        "comment on column ana_report_export_summary.comparison_pass_rate is '比对通过率'",
        "comment on column ana_report_export_summary.transaction_issue_count is '交易级差异总数'",
        "comment on column ana_report_export_summary.field_issue_count is '字段级差异总数'",
        "comment on column ana_report_export_summary.issue_total_count is '问题总数'",
        "comment on column ana_report_export_summary.duplicate_issue_count is '重复问题数'");
```

- [ ] **Step 2: Run the DDL test and verify it fails**

Run: `mvn "-Dtest=DatabaseScriptLayoutTest" test`

Expected: FAIL because the new `ana_report_export_summary` columns are not present in `db/ddl.sql`.

- [ ] **Step 3: Add the DDL columns and comments**

In `db/ddl.sql`, extend `ana_report_export_summary` after `success_rate`:

```sql
    field_pass_transaction_count bigint not null default 0,
    comparison_pass_rate numeric(12,8) not null default 0,
    transaction_issue_count bigint not null default 0,
    field_issue_count bigint not null default 0,
    issue_total_count bigint not null default 0,
    duplicate_issue_count bigint not null default 0,
```

Add upgrade-safe `alter table` statements after the table definition:

```sql
alter table ana_report_export_summary add column if not exists field_pass_transaction_count bigint not null default 0;
alter table ana_report_export_summary add column if not exists comparison_pass_rate numeric(12,8) not null default 0;
alter table ana_report_export_summary add column if not exists transaction_issue_count bigint not null default 0;
alter table ana_report_export_summary add column if not exists field_issue_count bigint not null default 0;
alter table ana_report_export_summary add column if not exists issue_total_count bigint not null default 0;
alter table ana_report_export_summary add column if not exists duplicate_issue_count bigint not null default 0;
```

Add comments:

```sql
comment on column ana_report_export_summary.field_pass_transaction_count is '二者均成功且无字段差异交易数';
comment on column ana_report_export_summary.comparison_pass_rate is '比对通过率';
comment on column ana_report_export_summary.transaction_issue_count is '交易级差异总数';
comment on column ana_report_export_summary.field_issue_count is '字段级差异总数';
comment on column ana_report_export_summary.issue_total_count is '问题总数';
comment on column ana_report_export_summary.duplicate_issue_count is '重复问题数';
```

Update the existing `success_rate` comment to:

```sql
comment on column ana_report_export_summary.success_rate is '成功率';
```

- [ ] **Step 4: Run the DDL test and verify it passes**

Run: `mvn "-Dtest=DatabaseScriptLayoutTest" test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add db/ddl.sql src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java
git commit -m "feat: add report export summary metrics schema"
```

---

### Task 2: Persist Batch Summary Metrics

**Files:**
- Modify: `src/test/java/com/spdb/report/ReportExportBatchRunnerTest.java`
- Modify: `src/main/java/com/spdb/report/ReportExportBatchRunner.java`

- [ ] **Step 1: Expand the H2 summary schema in the test fixture**

In `ReportExportBatchRunnerTest.createSchema()`, replace the `ana_report_export_summary` create statement with:

```java
jdbc.execute("create table ana_report_export_summary (batch_id varchar(64), report_date varchar(8), module_name varchar(100), covered_528_interface_count bigint, sent_transaction_count bigint, comp_result_1_count bigint, comp_result_2_count bigint, comp_result_3_count bigint, comp_result_4_count bigint, comp_result_8_count bigint, success_rate decimal(12,8), diff_528_field_count bigint, field_pass_transaction_count bigint not null default 0, comparison_pass_rate decimal(12,8) not null default 0, transaction_issue_count bigint not null default 0, field_issue_count bigint not null default 0, issue_total_count bigint not null default 0, duplicate_issue_count bigint not null default 0)");
```

- [ ] **Step 2: Write the failing summary metrics test**

Add this test to `ReportExportBatchRunnerTest`:

```java
@Test
void persistsExtendedSummaryMetricsForFieldPassProblemsAndDuplicates() {
    jdbc.update("insert into ana_tran_catalog(tran_code, service_code, tran_name, module_name, owner) values ('T001', 'SVC1', '交易一', '支付', '负责人')");
    jdbc.update("""
            insert into tss_tran_comp values
            ('OK-FIELD-PASS', '20260728', 1, 1, 'SVC1&soap', '4'),
            ('OK-FIELD-DIFF', '20260728', 2, 1, 'SVC1&soap', '4'),
            ('FAIL-SAME', '20260728', 3, 1, 'SVC1&soap', '3'),
            ('FAIL-DIFF', '20260728', 4, 1, 'SVC1&soap', '3'),
            ('ORIG-FAIL', '20260728', 5, 1, 'SVC1&soap', '1')
            """);
    jdbc.update("""
            insert into tss_retcode_comp values
            ('FAIL-SAME', '20260728', 'SVC1&soap', 'E1', 'orig failed', 'E1', 'dest failed'),
            ('FAIL-DIFF', '20260728', 'SVC1&soap', 'E2', 'orig failed', 'E3', 'dest failed'),
            ('ORIG-FAIL', '20260728', 'SVC1&soap', 'E4', 'orig failed', '000000000000', 'dest ok')
            """);
    jdbc.update("insert into tss_field_comp values ('OK-FIELD-DIFF', '20260728', 2, 1, 1, 'SVC1&soap', 'Request.amount', '1', 'Request.amount', '2')");
    jdbc.update("""
            insert into ana_diff_issue(issue_key, issue_level, service_code, tran_code, tran_name, module_name,
                transaction_owner, orig_error_code, dest_error_code, normalized_source_field_name, issue_status,
                first_seen_date, last_seen_date, first_seen_batch_id, last_seen_batch_id, occurrence_batch_count)
            values
            ('TRAN|svc1|e4|000000000000', 'TRANSACTION', 'SVC1', 'T001', '交易一', '支付',
                '负责人', 'E4', '000000000000', null, 'OPEN',
                date '2026-07-01', date '2026-07-16', 'RPT20260716-101530-2048', 'RPT20260716-101530-2048', 1),
            ('FIELD|svc1|request.amount', 'FIELD', 'SVC1', 'T001', '交易一', '支付',
                '负责人', null, null, 'request.amount', 'OPEN',
                date '2026-07-01', date '2026-07-16', 'RPT20260716-101530-2048', 'RPT20260716-101530-2048', 1)
            """);

    runner.run("BATCH-EXTENDED-SUMMARY", "20260728", LocalDateTime.of(2026, 7, 28, 10, 0));

    Map<String, Object> summary = jdbc.queryForMap("""
            select sent_transaction_count, comp_result_1_count, comp_result_3_count, comp_result_4_count,
                   comp_result_8_count, success_rate, field_pass_transaction_count, comparison_pass_rate,
                   transaction_issue_count, field_issue_count, issue_total_count, duplicate_issue_count
            from ana_report_export_summary
            where batch_id = 'BATCH-EXTENDED-SUMMARY' and module_name = '支付'
            """);
    assertThat(summary).containsEntry("sent_transaction_count", 5L)
            .containsEntry("comp_result_1_count", 1L)
            .containsEntry("comp_result_3_count", 1L)
            .containsEntry("comp_result_4_count", 2L)
            .containsEntry("comp_result_8_count", 1L)
            .containsEntry("field_pass_transaction_count", 1L)
            .containsEntry("transaction_issue_count", 3L)
            .containsEntry("field_issue_count", 1L)
            .containsEntry("issue_total_count", 4L)
            .containsEntry("duplicate_issue_count", 2L);
    assertThat(summary.get("success_rate").toString()).isEqualTo("0.60000000");
    assertThat(summary.get("comparison_pass_rate").toString()).isEqualTo("0.40000000");
}
```

- [ ] **Step 3: Run the focused test and verify it fails**

Run: `mvn "-Dtest=ReportExportBatchRunnerTest#persistsExtendedSummaryMetricsForFieldPassProblemsAndDuplicates" test`

Expected: FAIL because the runner does not write the new columns yet.

- [ ] **Step 4: Implement metric persistence**

In `ReportExportBatchRunner`, add helper records near existing local records:

```java
private record TranIdentity(String mesgSeq, String origCdate, int convIndex, int convCindex) {}
private record SummaryExtension(long fieldPassTransactionCount, long transactionIssueCount,
                                long fieldIssueCount, long issueTotalCount, long duplicateIssueCount) {}
```

Add a helper to build field-diff identities:

```java
private Set<TranIdentity> fieldDiffIdentities(List<Field> fields) {
    Set<TranIdentity> result = new HashSet<>();
    for (Field field : fields) {
        result.add(new TranIdentity(field.mesgSeq(), field.origCdate(), field.convIndex(), field.convCindex()));
    }
    return result;
}
```

Update `insertSummaries(...)` so `field_pass_transaction_count` and `comparison_pass_rate` are included in the insert. The core calculation should be:

```java
Set<TranIdentity> fieldDiffs = fieldDiffIdentities(fields);
long fieldPass = rows.stream()
        .filter(row -> "4".equals(row.compResult()))
        .filter(row -> !fieldDiffs.contains(new TranIdentity(row.mesgSeq(), row.origCdate(), row.convIndex(), row.convCindex())))
        .count();
BigDecimal successRate = rate(three + four, total);
BigDecimal comparisonPassRate = rate(fieldPass + three, total);
```

Add this reusable rate helper:

```java
private static BigDecimal rate(long numerator, long denominator) {
    return denominator == 0 ? BigDecimal.ZERO
            : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP);
}
```

After `issueLedgerService.materializeBatch(...)` in `run(...)`, call:

```java
updateSummaryIssueMetrics(batchId);
```

Implement it with SQL grouped by `module_name`:

```java
private void updateSummaryIssueMetrics(String batchId) {
    jdbc.update("""
            update ana_report_export_summary s
               set transaction_issue_count = coalesce(t.transaction_count, 0),
                   field_issue_count = coalesce(f.field_count, 0),
                   issue_total_count = coalesce(t.transaction_count, 0) + coalesce(f.field_count, 0),
                   duplicate_issue_count = coalesce(t.duplicate_count, 0) + coalesce(f.duplicate_count, 0),
                   updated_at = current_timestamp
              from (select module_name, count(*) transaction_count,
                           sum(case when historical_occurrence_count > 0 then 1 else 0 end) duplicate_count
                      from ana_tran_diff_tracking_export
                     where source_batch_id = :batchId
                     group by module_name) t
              full join (select module_name, count(*) field_count,
                                sum(case when historical_occurrence_count > 0 then 1 else 0 end) duplicate_count
                           from ana_field_diff_tracking_export
                          where source_batch_id = :batchId
                          group by module_name) f
                on f.module_name = t.module_name
             where s.batch_id = :batchId
               and s.module_name = coalesce(t.module_name, f.module_name)
            """, new MapSqlParameterSource("batchId", batchId));
}
```

- [ ] **Step 5: Run focused and adjacent tests**

Run: `mvn "-Dtest=ReportExportBatchRunnerTest" test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spdb/report/ReportExportBatchRunner.java src/test/java/com/spdb/report/ReportExportBatchRunnerTest.java
git commit -m "feat: persist report export summary metrics"
```

---

### Task 3: Expand Summary Query Model

**Files:**
- Modify: `src/main/java/com/spdb/report/ReportExportSummaryRow.java`
- Modify: `src/main/java/com/spdb/report/ReportExportCommandService.java`
- Modify: `src/test/java/com/spdb/report/ReportExportCommandServiceTest.java`

- [ ] **Step 1: Expand the command service test schema**

In `ReportExportCommandServiceTest`, update its H2 `ana_report_export_summary` create statement to include:

```sql
field_pass_transaction_count bigint not null default 0,
comparison_pass_rate decimal(12,8) not null default 0,
transaction_issue_count bigint not null default 0,
field_issue_count bigint not null default 0,
issue_total_count bigint not null default 0,
duplicate_issue_count bigint not null default 0
```

- [ ] **Step 2: Write a failing mapping assertion**

In the test that verifies `findSummaries`, insert a row with explicit values and assert the record exposes them:

```java
jdbc.update("""
        insert into ana_report_export_summary(batch_id, report_date, module_name, covered_528_interface_count,
            sent_transaction_count, comp_result_1_count, comp_result_2_count, comp_result_3_count,
            comp_result_4_count, comp_result_8_count, diff_528_field_count, success_rate,
            field_pass_transaction_count, comparison_pass_rate, transaction_issue_count, field_issue_count,
            issue_total_count, duplicate_issue_count)
        values ('RPT1','20260728','支付',2,10,1,2,3,4,5,6,0.70000000,4,0.70000000,7,8,15,9)
        """);

ReportExportSummaryRow row = service.findSummaries("RPT1").get(0);
assertThat(row.fieldPassTransactionCount()).isEqualTo(4L);
assertThat(row.comparisonPassRate().toString()).isEqualTo("0.70000000");
assertThat(row.transactionIssueCount()).isEqualTo(7L);
assertThat(row.fieldIssueCount()).isEqualTo(8L);
assertThat(row.issueTotalCount()).isEqualTo(15L);
assertThat(row.duplicateIssueCount()).isEqualTo(9L);
```

- [ ] **Step 3: Run the command service test and verify it fails**

Run: `mvn "-Dtest=ReportExportCommandServiceTest" test`

Expected: FAIL because `ReportExportSummaryRow` does not expose the new fields.

- [ ] **Step 4: Expand the record and mapper**

Update `ReportExportSummaryRow`:

```java
public record ReportExportSummaryRow(
        long summaryId,
        String batchId,
        String reportDate,
        String moduleName,
        long covered528InterfaceCount,
        long sentTransactionCount,
        long compResult1Count,
        long compResult2Count,
        long compResult3Count,
        long compResult4Count,
        long compResult8Count,
        long diff528FieldCount,
        BigDecimal successRate,
        long fieldPassTransactionCount,
        BigDecimal comparisonPassRate,
        long transactionIssueCount,
        long fieldIssueCount,
        long issueTotalCount,
        long duplicateIssueCount
) {
}
```

Update `ReportExportCommandService.findSummaries(...)` select list to include the new columns and map them in the same order.

- [ ] **Step 5: Run mapped tests**

Run: `mvn "-Dtest=ReportExportCommandServiceTest" test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spdb/report/ReportExportSummaryRow.java src/main/java/com/spdb/report/ReportExportCommandService.java src/test/java/com/spdb/report/ReportExportCommandServiceTest.java
git commit -m "feat: expose report export summary metrics"
```

---

### Task 4: Replace Excel 汇总信息 Sheet

**Files:**
- Modify: `src/test/java/com/spdb/report/ReportExportExcelServiceTest.java`
- Modify: `src/main/java/com/spdb/report/ReportExportExcelService.java`

- [ ] **Step 1: Expand the Excel test schema**

In `ReportExportExcelServiceTest.setUp()`, create `ana_report_export_command` and expand the summary table:

```java
jdbc.execute("create table ana_report_export_command(command_id bigint primary key, batch_id varchar(64), report_date varchar(8), status varchar(32), created_time timestamp)");
jdbc.execute("create table ana_report_export_summary(batch_id varchar(64), report_date varchar(8), module_name varchar(100), covered_528_interface_count bigint, sent_transaction_count bigint, comp_result_1_count bigint, comp_result_2_count bigint, comp_result_3_count bigint, comp_result_4_count bigint, comp_result_8_count bigint, success_rate decimal(12,8), diff_528_field_count bigint, field_pass_transaction_count bigint not null default 0, comparison_pass_rate decimal(12,8) not null default 0, transaction_issue_count bigint not null default 0, field_issue_count bigint not null default 0, issue_total_count bigint not null default 0, duplicate_issue_count bigint not null default 0)");
```

- [ ] **Step 2: Write the failing Excel layout test**

Replace or add a test that seeds previous and current commands:

```java
@Test
void streamsPreviousAndCurrentBatchSummarySheetWithApprovedMetrics() throws Exception {
    jdbc.update("insert into ana_report_export_command values (1,'RPT20260716-101530-2048','20260716','SUCCEEDED','2026-07-16 10:15:30')");
    jdbc.update("insert into ana_report_export_command values (2,'RPT20260720-090000-0001','20260720','FAILED','2026-07-20 09:00:00')");
    jdbc.update("insert into ana_report_export_command values (3,'RPT20260728-132831-6664','20260728','SUCCEEDED','2026-07-28 13:28:31')");
    jdbc.update("""
            insert into ana_report_export_summary values
            ('RPT20260716-101530-2048','20260716','支付',2,10,1,2,3,4,5,0.70000000,6,4,0.70000000,7,8,15,0),
            ('RPT20260728-132831-6664','20260728','支付',2,10,1,2,3,4,5,0.70000000,6,4,0.70000000,5,4,9,3)
            """);
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    service.stream("RPT20260728-132831-6664", output);

    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
        var sheet = workbook.getSheetAt(0);
        assertThat(sheet.getSheetName()).isEqualTo("汇总信息");
        assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("上一批次");
        assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("RPT20260716-101530-2048");
        assertThat(sheet.getRow(3).getCell(6).getStringCellValue()).isEqualTo("3");
        assertThat(sheet.getRow(3).getCell(7).getStringCellValue()).isEqualTo("5");
        assertThat(sheet.getRow(3).getCell(8).getStringCellValue()).isEqualTo("4");
        assertThat(sheet.getRow(3).getCell(9).getNumericCellValue()).isEqualTo(0.7d);
        assertThat(sheet.getRow(3).getCell(10).getNumericCellValue()).isEqualTo(0.7d);
        assertThat(sheet.getRow(3).getCell(11).getStringCellValue()).isEqualTo("15");
        assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).contains("本批次");
        assertThat(sheet.getRow(9).getCell(0).getStringCellValue()).isEqualTo("RPT20260728-132831-6664");
        assertThat(sheet.getRow(9).getCell(11).getStringCellValue()).isEqualTo("9");
        assertThat(sheet.getRow(9).getCell(12).getStringCellValue()).isEqualTo("3");
        assertThat(sheet.getRow(9).getCell(13).getNumericCellValue()).isEqualTo(3d / 9d);
        assertThat(sheet.getRow(9).getCell(14).getNumericCellValue()).isEqualTo((15d - 3d) / 15d);
        assertThat(sheet.getRow(9).getCell(15).getStringCellValue()).isBlank();
        assertThat(sheet.getRow(9).getCell(20).getStringCellValue()).isBlank();
    }
}
```

Keep the row indexes in the assertions by rendering the previous section at rows 0-4, one blank row at row 5, and the current section at rows 6-10.

- [ ] **Step 3: Run the Excel test and verify it fails**

Run: `mvn "-Dtest=ReportExportExcelServiceTest#streamsPreviousAndCurrentBatchSummarySheetWithApprovedMetrics" test`

Expected: FAIL because the old `汇总信息` Sheet has a single section and no previous batch lookup.

- [ ] **Step 4: Implement previous/current summary rendering**

In `ReportExportExcelService`, add a private summary row record:

```java
private record SummaryRow(String batchId, String reportDate, String moduleName, long covered,
                          long sent, long one, long two, long three, long four, long eight,
                          BigDecimal successRate, long fieldPass, BigDecimal comparisonPassRate,
                          long issueTotal, long duplicateIssues) {}
```

Add helpers:

```java
private String previousSucceededBatchId(String batchId) {
    List<String> rows = jdbc.query("""
            select prev.batch_id
              from ana_report_export_command cur
              join ana_report_export_command prev
                on prev.status = 'SUCCEEDED'
               and (prev.created_time < cur.created_time
                    or (prev.created_time = cur.created_time and prev.command_id < cur.command_id))
             where cur.batch_id = :batchId
             order by prev.created_time desc, prev.command_id desc
             limit 1
            """, params(batchId), (rs, rowNum) -> rs.getString("batch_id"));
    return rows.isEmpty() ? null : rows.get(0);
}

private List<SummaryRow> summaryRows(String batchId) {
    if (batchId == null || batchId.isBlank()) return List.of();
    return jdbc.query("""
            select batch_id, report_date, module_name, covered_528_interface_count, sent_transaction_count,
                   comp_result_1_count, comp_result_2_count, comp_result_3_count, comp_result_4_count,
                   comp_result_8_count, success_rate, field_pass_transaction_count, comparison_pass_rate,
                   issue_total_count, duplicate_issue_count
              from ana_report_export_summary
             where batch_id = :batchId
             order by module_name
            """, params(batchId), (rs, rowNum) -> new SummaryRow(
            rs.getString("batch_id"), rs.getString("report_date"), rs.getString("module_name"),
            rs.getLong("covered_528_interface_count"), rs.getLong("sent_transaction_count"),
            rs.getLong("comp_result_1_count"), rs.getLong("comp_result_2_count"),
            rs.getLong("comp_result_3_count"), rs.getLong("comp_result_4_count"),
            rs.getLong("comp_result_8_count"), rs.getBigDecimal("success_rate"),
            rs.getLong("field_pass_transaction_count"), rs.getBigDecimal("comparison_pass_rate"),
            rs.getLong("issue_total_count"), rs.getLong("duplicate_issue_count")));
}
```

Rewrite `writeSummary(...)` to:

```java
private void writeSummary(SXSSFWorkbook book, String batchId, Styles styles) {
    SXSSFSheet sheet = book.createSheet("汇总信息");
    int row = 0;
    String previousBatchId = previousSucceededBatchId(batchId);
    row = writeSummarySection(sheet, row, "上一批次", previousBatchId, summaryRows(previousBatchId), false, styles);
    row++;
    writeSummarySection(sheet, row, "本批次", batchId, summaryRows(batchId), true, styles);
    sheet.createFreezePane(0, 2);
    for (int i = 0; i < 21; i++) sheet.setColumnWidth(i, i == 0 ? 6400 : 3600);
}
```

Implement `writeSummarySection(...)` with the approved headers:

```java
private int writeSummarySection(SXSSFSheet sheet, int rowIndex, String label, String batchId,
                                List<SummaryRow> rows, boolean current, Styles styles) {
    int totalColumns = current ? 21 : 18;
    mergedCell(sheet, rowIndex, rowIndex, 0, totalColumns - 1,
            "回放日期：" + sectionDate(rows, batchId) + "（" + label + "）", current ? styles.currentDateHeader : styles.previousDateHeader);
    rowIndex++;
    Row head = sheet.createRow(rowIndex++);
    // Create merged headers: 批次, 领域, 覆盖528接口, 发送交易量, 交易状态分类统计, 成功率, 比对通过率, 问题总数,
    // current-only 重复问题/重复率/上轮问题解决率, 已解决问题分类统计（待验证）, 问题解决进度.
    Row sub = sheet.createRow(rowIndex++);
    // Sub headers: 528成功/CCBS失败, 528失败/CCBS成功, 二者均失败响应码一致,
    // 二者均失败响应码不一致, 二者均成功, 迁移问题, 防腐问题, 功能问题, 新核心下线, 其他问题.
    for (SummaryRow row : rows) rowIndex = writeSummaryDataRow(sheet, rowIndex, row, current, null, styles);
    writeSummaryTotalRow(sheet, rowIndex++, rows, current, styles);
    return rowIndex;
}
```

Use numeric percent cells for `successRate`, `comparisonPassRate`, `重复率`, and `上轮问题解决率`, all with `0.00%`. Write blank strings for all five resolved-category columns and `问题解决进度`.

- [ ] **Step 5: Add styles needed by the new summary**

Extend `Styles` with previous/current section styles:

```java
private final CellStyle previousDateHeader;
private final CellStyle previousHeader;
private final CellStyle previousSubHeader;
private final CellStyle currentDateHeader;
private final CellStyle currentHeader;
private final CellStyle currentSubHeader;
private final CellStyle issueHeader;
private final CellStyle totalBody;
private final CellStyle totalPercent;
```

Use the preview colors:

```java
previousDateHeader = headerStyle(book, "B7D7C0");
previousHeader = headerStyle(book, "C6E0B4");
previousSubHeader = headerStyle(book, "E2F0D9");
currentDateHeader = headerStyle(book, "F4CCCC");
currentHeader = headerStyle(book, "FCE4D6");
currentSubHeader = headerStyle(book, "FCE4D6");
issueHeader = headerStyle(book, "FFF2CC");
totalBody = style(book, "EEF2F7");
totalPercent = style(book, "EEF2F7");
```

Set `totalPercent` data format to `0.00%`.

- [ ] **Step 6: Run Excel tests**

Run: `mvn "-Dtest=ReportExportExcelServiceTest" test`

Expected: PASS, including the existing detail Sheet assertions.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/spdb/report/ReportExportExcelService.java src/test/java/com/spdb/report/ReportExportExcelServiceTest.java
git commit -m "feat: render report export summary comparison sheet"
```

---

### Task 5: Final Verification

**Files:**
- Verify all files changed in Tasks 1-4.

- [ ] **Step 1: Run focused report export tests**

Run:

```bash
mvn "-Dtest=DatabaseScriptLayoutTest,ReportExportBatchRunnerTest,ReportExportCommandServiceTest,ReportExportExcelServiceTest,ReportExportControllerTest" test
```

Expected: PASS.

- [ ] **Step 2: Run full test suite**

Run:

```bash
mvn test
```

Expected: PASS.

- [ ] **Step 3: Check formatting and working tree**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` reports no whitespace errors. `git status --short` only shows intended files if any final changes remain.

- [ ] **Step 4: Commit final cleanup if needed**

If Step 3 shows intended uncommitted fixes, commit them:

```bash
git add db/ddl.sql src/main/java/com/spdb/report src/test/java/com/spdb/report src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java
git commit -m "test: verify report export summary sheet"
```

If the tree is already clean, do not create an empty commit.
