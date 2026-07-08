# Transaction List Async Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make financial transaction list import run in the background with retry and visible page progress.

**Architecture:** Add a persistent task table and service around the existing transaction list import flow. The controller creates a task and redirects to a progress page; a background executor runs the task, updates progress after each batch, and the page polls a JSON endpoint.

**Tech Stack:** Spring MVC, Spring JDBC, Thymeleaf, `ThreadPoolTaskExecutor`, Apache POI, H2/PostgreSQL-compatible DDL, JUnit 5.

---

### Task 1: Task State Model And DDL

**Files:**
- Modify: `db/ddl.sql`
- Create: `src/main/java/com/spdb/config/TransactionListImportTaskRow.java`
- Create: `src/main/java/com/spdb/config/TransactionListImportProgressRow.java`
- Test: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] Add `ana_transaction_list_import_task` DDL with status constraint and status/created index.
- [ ] Add row records with `completionPercent()` and `progressText()`.
- [ ] Run `mvn -Dtest=DatabaseScriptLayoutTest test`.

### Task 2: Task Service

**Files:**
- Create: `src/main/java/com/spdb/config/TransactionListImportTaskService.java`
- Create: `src/main/java/com/spdb/config/TransactionListImportTaskLauncher.java`
- Test: `src/test/java/com/spdb/config/TransactionListImportTaskServiceTest.java`

- [ ] Write failing tests for task creation, progress updates, completion, and failure.
- [ ] Implement JDBC-backed task service and launcher hook.
- [ ] Run `mvn -Dtest=TransactionListImportTaskServiceTest test`.

### Task 3: Retryable Background Runner

**Files:**
- Create: `src/main/java/com/spdb/config/TransactionListImportExecutionConfig.java`
- Create: `src/main/java/com/spdb/config/TransactionListImportAsyncExecutor.java`
- Create: `src/main/java/com/spdb/config/TransactionListImportTaskRunner.java`
- Modify: `src/main/java/com/spdb/config/TransactionListImportService.java`
- Test: `src/test/java/com/spdb/config/TransactionListImportTaskRunnerTest.java`

- [ ] Write failing tests proving a failed download is retried and progress is updated after each batch.
- [ ] Extract retryable batch execution around the existing parser/client/import service.
- [ ] Keep existing synchronous `importList(Path)` tests passing.
- [ ] Run `mvn -Dtest=TransactionListImportServiceTest,TransactionListImportTaskRunnerTest test`.

### Task 4: Controller And Progress Page

**Files:**
- Modify: `src/main/java/com/spdb/web/ConfigImportController.java`
- Create: `src/main/resources/templates/config/import-list-progress.html`
- Modify: `src/main/resources/templates/config/import.html`
- Test: `src/test/java/com/spdb/web/ConfigImportControllerTest.java`
- Test: `src/test/java/com/spdb/web/ConfigImportTemplateTest.java`

- [ ] Write failing controller tests for upload redirect, progress page, and JSON progress.
- [ ] Write failing template tests for progress bar and polling script.
- [ ] Implement controller endpoints and templates.
- [ ] Run `mvn -Dtest=ConfigImportControllerTest,ConfigImportTemplateTest test`.

### Task 5: Verification

**Files:**
- All touched files.

- [ ] Run focused config/import tests.
- [ ] Run `git diff --check`.
- [ ] Summarize modified files and verification output.
