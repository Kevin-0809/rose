# Leadership Summary Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a leadership-ready Excel workbook to the transaction diff zip export, matching the approved sample layout and adding module-owner configuration data.

**Architecture:** Keep existing transaction txt exports unchanged. Add query DTOs for leadership rows, add `ana_module_owner_config` as the module owner source, aggregate rows in Java from existing service-level and success-stat streams, and write a multi-sheet SXSSF workbook into the existing zip.

**Tech Stack:** Java 17, Spring JDBC, Apache POI SXSSF, JUnit 5, AssertJ, H2 PostgreSQL mode.

---

### Task 1: Failing Export Tests

**Files:**
- Modify: `src/test/java/com/spdb/sample/SampleExcelExportServiceTest.java`

- [ ] Add tests that assert transaction zip includes `leadership_summary_yyyyMMddHHmm.xlsx`, existing txt entries are unchanged, and the workbook contains sheets `领导总览`, `责任人看板`, `领域看板`, `领域负责人配置`, `服务码明细`, `字段差异摘要`.
- [ ] Run `mvn -Dtest=SampleExcelExportServiceTest test`; expected failure: leadership workbook entry or new methods are missing.

### Task 2: Failing Query And DDL Tests

**Files:**
- Modify: `src/test/java/com/spdb/sample/SampleQueryServiceTest.java`
- Modify: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] Add H2 tables/seeds for `ana_module_owner_config`.
- [ ] Add tests for streaming leadership service rows with module name and for streaming module owner config rows.
- [ ] Add DDL assertions for `ana_module_owner_config`.
- [ ] Run `mvn -Dtest=SampleQueryServiceTest,DatabaseScriptLayoutTest test`; expected failure: query methods and DDL table are missing.

### Task 3: Implement Query DTOs And SQL

**Files:**
- Create: `src/main/java/com/spdb/sample/LeadershipServiceReportRow.java`
- Create: `src/main/java/com/spdb/sample/LeadershipServiceReportConsumer.java`
- Create: `src/main/java/com/spdb/sample/ModuleOwnerConfigRow.java`
- Create: `src/main/java/com/spdb/sample/ModuleOwnerConfigConsumer.java`
- Modify: `src/main/java/com/spdb/sample/SampleQueryService.java`
- Modify: `db/ddl.sql`

- [ ] Add DTOs mirroring service report plus module name, and module owner config fields.
- [ ] Add `streamLeadershipServiceReport(...)`, reusing service report SQL with module name and config owner join.
- [ ] Add `streamModuleOwnerConfigs(...)`.
- [ ] Add `ana_module_owner_config` DDL and index.
- [ ] Run `mvn -Dtest=SampleQueryServiceTest,DatabaseScriptLayoutTest test`; expected pass.

### Task 4: Implement Leadership Workbook Export

**Files:**
- Modify: `src/main/java/com/spdb/sample/SampleExcelExportService.java`

- [ ] Add `leadership_summary_{timestamp}.xlsx` into `streamTransactionDiffExport(...)` zip.
- [ ] Collect leadership service rows, transaction success stat rows, and module owner config rows.
- [ ] Write sheets: `领导总览`, `责任人看板`, `领域看板`, `领域负责人配置`, `服务码明细`, `字段差异摘要`.
- [ ] Match approved sample style: compact KPI row, conclusion strip, dark blue section headers, alternating table rows, percent and number formats, conditional risk highlight, freeze panes, filters.
- [ ] Run `mvn -Dtest=SampleExcelExportServiceTest test`; expected pass.

### Task 5: Full Verification

**Files:** all modified files.

- [ ] Run `mvn test`.
- [ ] Inspect generated test workbook structure through tests; no manual artifact required.
- [ ] Report changed files and verification output.
