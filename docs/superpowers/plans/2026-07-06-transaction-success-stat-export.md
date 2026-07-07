# Transaction Success Stat Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `transdiff_success_{yyyyMMddHHmm}.txt` to the transaction export zip with the agreed “二者都成功” field statistics.

**Architecture:** Add a focused query stream in `SampleQueryService` that aggregates `tss_tran_comp.comp_result='4'` with field mapping and field-diff statistics. Add a row record/consumer for the result, then have `SampleExcelExportService` write the new txt entry using the existing UTF-8 zip export flow and `!` delimiter.

**Tech Stack:** Java 17, Spring JDBC `NamedParameterJdbcTemplate`, JUnit 5, AssertJ, Maven.

---

### Task 1: Query Contract and Export File

**Files:**
- Create: `src/main/java/com/spdb/sample/TransactionSuccessStatRow.java`
- Create: `src/main/java/com/spdb/sample/TransactionSuccessStatConsumer.java`
- Modify: `src/main/java/com/spdb/sample/SampleQueryService.java`
- Modify: `src/main/java/com/spdb/sample/SampleExcelExportService.java`
- Test: `src/test/java/com/spdb/sample/SampleExcelExportServiceTest.java`

- [ ] **Step 1: Write failing test**

Add a test that stubs `SampleQueryService.streamTransactionSuccessStats(...)` and expects the zip to contain `transdiff_success_202607061014.txt` with the agreed header and one data row.

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
mvn "-Dtest=SampleExcelExportServiceTest#streamsTransactionDiffExportWithSuccessStatFile" test
```

Expected: compile or assertion failure because success stat row/query/export does not exist yet.

- [ ] **Step 3: Implement minimal export support**

Add `TransactionSuccessStatRow`, `TransactionSuccessStatConsumer`, `SampleQueryService.streamTransactionSuccessStats(...)`, and write the new success txt entry in `SampleExcelExportService.streamTransactionDiffExport(...)`.

- [ ] **Step 4: Run targeted test**

Run:

```powershell
mvn "-Dtest=SampleExcelExportServiceTest#streamsTransactionDiffExportWithSuccessStatFile" test
```

Expected: PASS.

### Task 2: SQL Aggregation

**Files:**
- Modify: `src/main/java/com/spdb/sample/SampleQueryService.java`
- Test: `src/test/java/com/spdb/sample/SampleQueryServiceTest.java`

- [ ] **Step 1: Write failing SQL test**

Add a structure test that calls `streamTransactionSuccessStats(...)` on a mocked `NamedParameterJdbcTemplate`, captures SQL, and asserts it includes:
- `t.comp_result = '4'`
- array index normalization with `regexp_replace(..., '\\[[0-9]+\\]', '', 'g')`
- `ana_tran_catalog.module_name`
- `ana_tran_catalog.owner`

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
mvn "-Dtest=SampleQueryServiceTest#streamTransactionSuccessStatsBuildsNormalizedFieldAggregationQuery" test
```

Expected: FAIL because SQL method is incomplete.

- [ ] **Step 3: Implement SQL aggregation**

Use grouped subqueries over `tss_tran_comp`, `ana_field_mapping`, `tss_field_comp`, and `ana_tran_catalog`, preserving `SampleSearchCriteria` filters for batch/date/tran/service/message/owner.

- [ ] **Step 4: Run targeted tests**

Run:

```powershell
mvn "-Dtest=SampleQueryServiceTest#streamTransactionSuccessStatsBuildsNormalizedFieldAggregationQuery,SampleExcelExportServiceTest#streamsTransactionDiffExportWithSuccessStatFile" test
```

Expected: PASS.
