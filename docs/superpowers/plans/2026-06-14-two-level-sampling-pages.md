# Two-Level Sampling Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement two-level sampling results where `RETURN_CODE` represents all transaction-level differences and `FIELD_DIFF` represents field-level differences, with separate pages and exports.

**Architecture:** Keep the existing Java sampling engine and query services, but change classification at the source so no new `TRAN_RESULT` rows are written. Add focused query/controller/template paths for transaction-level and field-level views, while reusing existing criteria and Excel streaming patterns.

**Tech Stack:** Spring Boot 3, JDBC/NamedParameterJdbcTemplate, Thymeleaf, Apache POI, openGauss-compatible SQL, JUnit 5, AssertJ.

---

### Task 1: Sampling Classification And Mapping Columns

**Files:**
- Modify: `src/test/java/com/spdb/sampling/SamplingBatchRunnerTest.java`
- Modify: `src/main/java/com/spdb/sampling/SamplingBatchRunner.java`
- Modify: `src/main/java/com/spdb/sampling/engine/SampleDetailDraft.java`

- [ ] Write failing tests in `SamplingBatchRunnerTest`:
  - Assert `ana_sample_group` has no `TRAN_RESULT`.
  - Assert transaction-result mismatches are written as `RETURN_CODE`.
  - Assert `ana_sample_detail` rows contain `sop_field_name`, `soap_field_name`, `bizjson_field_name`, `field_cn_name`.
- [ ] Run: `mvn -Dtest=SamplingBatchRunnerTest test`
  - Expected before implementation: failure showing old `TRAN_RESULT` behavior or missing mapping columns.
- [ ] Modify `SamplingBatchRunner.tranResultCandidate` to emit `RETURN_CODE`.
- [ ] Carry field mapping display names into `SampleDetailDraft` and insert `sop_field_name`, `soap_field_name`, `bizjson_field_name`, `field_cn_name`.
- [ ] Run: `mvn -Dtest=SamplingBatchRunnerTest test`
  - Expected after implementation: 0 failures.

### Task 2: DDL Constraint Upgrade

**Files:**
- Modify: `db/ddl.sql`
- Modify: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] Write failing DDL assertions that `ck_ana_sample_group_type` and `ck_ana_sample_detail_type` only allow `RETURN_CODE` and `FIELD_DIFF`.
- [ ] Run: `mvn -Dtest=DatabaseScriptLayoutTest test`
  - Expected before implementation: failure because `TRAN_RESULT` is still present.
- [ ] Update create-table and upgrade constraint SQL to remove `TRAN_RESULT`.
- [ ] Run: `mvn -Dtest=DatabaseScriptLayoutTest test`
  - Expected after implementation: 0 failures.

### Task 3: Query And Export Service Support

**Files:**
- Modify: `src/main/java/com/spdb/sample/SampleQueryService.java`
- Modify: `src/main/java/com/spdb/sample/SampleDetailRow.java`
- Modify: `src/main/java/com/spdb/sample/SampleExcelExportService.java`
- Modify: `src/test/java/com/spdb/sample/SampleQueryServiceTest.java`
- Modify: `src/test/java/com/spdb/sample/SampleExcelExportServiceTest.java`

- [ ] Write failing query tests for transaction-level details filtered to `RETURN_CODE` and field-level details filtered to `FIELD_DIFF`.
- [ ] Write failing export tests that field-level exports include SOP/SOAP/BizJSON/中文名 columns.
- [ ] Run: `mvn -Dtest=SampleQueryServiceTest,SampleExcelExportServiceTest test`
  - Expected before implementation: missing columns or filtering behavior.
- [ ] Add mapped field columns to `SampleDetailRow` and `detailSelect`.
- [ ] Add service methods that force `sample_type` for transaction and field detail exports.
- [ ] Update Excel headers and row writers.
- [ ] Run: `mvn -Dtest=SampleQueryServiceTest,SampleExcelExportServiceTest test`
  - Expected after implementation: 0 failures.

### Task 4: Controller And Templates

**Files:**
- Modify: `src/main/java/com/spdb/web/SampleController.java`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Create: `src/main/resources/templates/samples/transaction-diffs.html`
- Create: `src/main/resources/templates/samples/field-diffs.html`
- Modify: `src/test/java/com/spdb/web/SampleDetailTemplateTest.java`
- Create or modify: template tests under `src/test/java/com/spdb/web`

- [ ] Write failing template/controller structure tests for `/samples/transaction-diffs`, `/samples/transaction-diffs/export`, `/samples/field-diffs`, `/samples/field-diffs/export`.
- [ ] Run the targeted web/template tests.
- [ ] Add controller methods that build `SampleSearchCriteria` with forced `RETURN_CODE` or `FIELD_DIFF`.
- [ ] Add the two templates using existing table/filter CSS.
- [ ] Update navigation labels.
- [ ] Run the targeted web/template tests again.

### Task 5: Database And End-To-End Verification

**Files:**
- No new files expected.

- [ ] Run: `mvn test`
  - Expected: all tests pass.
- [ ] Apply DDL locally:
  - `gsql -h localhost -p 15432 -d postgres -U tss -W 'Tss@123456' -f db/ddl.sql`
- [ ] Start the app:
  - `mvn spring-boot:run`
- [ ] Submit a sampling batch for known data:
  - POST `/sampling/commands` with `origCdate=20260611`.
- [ ] Query `ana_sampling_command` and confirm latest batch is `COMPLETED`.
- [ ] Query `ana_sample_group` and confirm sample types are only `RETURN_CODE` and `FIELD_DIFF`.
- [ ] Check page HTTP 200:
  - `/samples/transaction-diffs`
  - `/samples/field-diffs`
  - their export URLs with a batch filter.

### Task 6: Commit And Push

**Files:**
- All modified files from previous tasks.

- [ ] Run `git status --short --branch`.
- [ ] Commit implementation:
  - `git commit -m "feat: split transaction and field sampling results"`
- [ ] Try `git push origin master`.
- [ ] If SSH times out, leave local commit intact and report the exact commit hash and retry command.
