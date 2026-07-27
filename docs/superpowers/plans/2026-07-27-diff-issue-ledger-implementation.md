# Diff Issue Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可维护的统一差异问题台账，并将历史出现统计和整改快照带入每个成功生成的报表批次。

**Architecture:** `ana_diff_issue` 是问题的唯一可编辑主数据；现有交易级和字段级导出表继续保存不可变的批次快照。`ReportExportBatchRunner` 先完成两类明细写入，再在一个事务中按本批次去重后的 `issue_key` 更新台账和当前批次快照，因此失败批次不会影响历史统计。

**Tech Stack:** Java 21、Spring MVC、Spring JDBC、PostgreSQL/H2、Thymeleaf、JUnit 5、AssertJ、Mockito。

---

## 文件结构

- `db/ddl.sql`：创建台账表、增加两张明细表快照字段、索引与逐字段注释。
- `src/main/java/com/spdb/report/DiffIssueLedgerService.java`：本批次问题去重、台账创建/更新、明细快照回填。
- `src/main/java/com/spdb/report/DiffIssueRow.java`、`DiffIssueUpdate.java`、`DiffIssueSearch.java`：台账查询与更新的不可变数据对象。
- `src/main/java/com/spdb/report/ReportExportBatchRunner.java`：生成稳定键，调用批次完成后的台账快照服务，并在失败时清理当前批次输出。
- `src/main/java/com/spdb/report/ReportExportCommandService.java` 与明细行 record：读取新增的历史统计字段。
- `src/main/java/com/spdb/web/DiffIssueController.java`：台账页面、详情和更新端点。
- `src/main/resources/templates/diff-issues/list.html`、`detail.html`：台账列表与维护页面。
- `src/main/resources/templates/fragments/layout.html`：新增侧边栏入口。
- 相关 `src/test/java`：DDL、台账服务、采集、控制器、模板与 Excel 回归测试。

### Task 1: 数据库结构和 DDL 契约

**Files:**
- Modify: `db/ddl.sql`
- Modify: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] **Step 1: 写入失败的 DDL 契约测试**

在 `DatabaseScriptLayoutTest` 中新增 `ddlContainsDiffIssueLedgerAndComments`。断言 `ana_diff_issue` 包含 `issue_key varchar(600) not null unique`、级别约束、状态约束、首次/最近日期、累计批次数及两个索引；对台账表每一个列名断言存在中文 `comment on column`。同时断言两张明细表均包含 `issue_id`、`issue_key`、`historical_occurrence_count`、`first_seen_date` 和 `previous_seen_date`，并逐个具有中文注释。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=DatabaseScriptLayoutTest test`

Expected: FAIL，提示缺少 `ana_diff_issue` 或新增快照字段。

- [ ] **Step 3: 实现 DDL**

在两张导出明细表定义中，在 `export_id` 后增加：

```sql
issue_id bigint,
issue_key varchar(600),
historical_occurrence_count bigint not null default 0,
first_seen_date date,
previous_seen_date date,
```

在表注释区域后创建设计文档中定义的 `ana_diff_issue`，并使用完整 `comment on table` 和每列 `comment on column`。为新增明细列增加注释；添加：

```sql
create index if not exists idx_ana_diff_issue_status_last_seen
on ana_diff_issue(issue_status, last_seen_date desc);
create index if not exists idx_ana_diff_issue_service_field
on ana_diff_issue(service_code, normalized_source_field_name);
create index if not exists idx_ana_tran_diff_tracking_export_issue
on ana_tran_diff_tracking_export(issue_id);
create index if not exists idx_ana_field_diff_tracking_export_issue
on ana_field_diff_tracking_export(issue_id);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -Dtest=DatabaseScriptLayoutTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add db/ddl.sql src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java
git commit -m "feat: add diff issue ledger schema"
```

### Task 2: 台账服务和事务性快照回填

**Files:**
- Create: `src/main/java/com/spdb/report/DiffIssueLedgerService.java`
- Create: `src/main/java/com/spdb/report/DiffIssueRow.java`
- Create: `src/main/java/com/spdb/report/DiffIssueUpdate.java`
- Create: `src/main/java/com/spdb/report/DiffIssueSearch.java`
- Create: `src/test/java/com/spdb/report/DiffIssueLedgerServiceTest.java`

- [ ] **Step 1: 写入失败测试**

创建 H2 PostgreSQL 模式下的 `DiffIssueLedgerServiceTest`，建最小台账及两张明细表。覆盖：

```java
@Test void firstOccurrenceCreatesOpenLedgerAndWritesZeroHistory() { }
@Test void laterBatchIncrementsOnceAndCopiesMaintenanceFields() { }
@Test void resolvedIssueReopensWhenSeenAgainButKeepsSolution() { }
@Test void oneKeyRepeatedInOneBatchIncrementsOnlyOnce() { }
@Test void updateRejectsResolvedWithoutResolutionDate() { }
```

第二个测试先通过 `update` 写入 `problemType`、`preliminaryAnalysis`、`finalSolution`、`resolver` 和 `resolutionDate`，再处理第二批同键明细，断言当前明细的 `historical_occurrence_count=1`、`first_seen_date` 为第一批日期、`previous_seen_date` 为第一批日期，并断言维护字段被复制。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=DiffIssueLedgerServiceTest test`

Expected: FAIL，编译错误提示 `DiffIssueLedgerService` 不存在。

- [ ] **Step 3: 实现数据对象与服务**

定义台账查询 record：

```java
public record DiffIssueRow(long issueId, String issueKey, String issueLevel, String serviceCode,
        String tranCode, String tranName, String moduleName, String transactionOwner,
        String origErrorCode, String destErrorCode, String normalizedSourceFieldName,
        String problemType, String problemDescription, String preliminaryAnalysis,
        String finalSolution, String issueStatus, String coordinationRequired, String resolver,
        LocalDate resolutionDate, LocalDate defectFixDate, LocalDate firstSeenDate,
        LocalDate lastSeenDate, String firstSeenBatchId, String lastSeenBatchId,
        long occurrenceBatchCount, LocalDateTime updatedAt) { }
```

`DiffIssueLedgerService.materializeBatch(batchId, businessDate)` 在 `TransactionTemplate` 中执行：从两张明细表读取本批次的不同 `issue_key` 和元数据；按键查询主表；新键插入 `OPEN` 台账，旧键读取更新前的次数/日期后更新 `last_seen_*` 和次数。将更新前统计及台账维护字段用 `update ... where source_batch_id=:batchId and issue_key=:issueKey` 写回两张明细表。旧记录状态为 `RESOLVED` 时更新为 `OPEN`，但不得覆盖人工维护字段。

`update(issueId, DiffIssueUpdate update, LocalDateTime expectedUpdatedAt)` 使用 `updated_at=:expectedUpdatedAt` 更新可维护列并显式 `updated_at=current_timestamp`；影响行数为 `0` 时区分不存在（404）和已修改（并发冲突）。当状态为 `RESOLVED` 且解决日期为空时抛出 `IllegalArgumentException`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -Dtest=DiffIssueLedgerServiceTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add src/main/java/com/spdb/report/DiffIssueLedgerService.java src/main/java/com/spdb/report/DiffIssueRow.java src/main/java/com/spdb/report/DiffIssueUpdate.java src/test/java/com/spdb/report/DiffIssueLedgerServiceTest.java
git commit -m "feat: materialize diff issue ledger"
```

### Task 3: 采集生成键、收尾关联和失败清理

**Files:**
- Modify: `src/main/java/com/spdb/report/ReportExportBatchRunner.java`
- Modify: `src/test/java/com/spdb/report/ReportExportBatchRunnerTest.java`

- [ ] **Step 1: 写入失败测试**

在 `ReportExportBatchRunnerTest` 添加两个测试：交易级行写入 `issue_key='TRAN|svc1|o1|d1'`；字段级行写入 `issue_key='FIELD|svc1|request.amount'`，键来自映射前的规范化源字段名。再添加失败测试：交易流式阶段抛出异常时，当前 `batch_id` 的汇总、交易明细和字段明细均为零，且 `ana_diff_issue` 没有当前批次导致的统计更新。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=ReportExportBatchRunnerTest test`

Expected: FAIL，新增列为空或失败后字段明细未清理。

- [ ] **Step 3: 实现生成和收尾流程**

向构造函数注入 `DiffIssueLedgerService`，保留兼容测试构造函数。增加键生成函数：

```java
private static String transactionIssueKey(String service, String orig, String dest) {
    return "TRAN|" + issuePart(service) + "|" + issuePart(orig) + "|" + issuePart(dest);
}
private static String fieldIssueKey(String service, String normalizedSourceField) {
    return "FIELD|" + issuePart(service) + "|" + issuePart(normalizedSourceField);
}
private static String issuePart(String value) {
    return text(value).trim().toLowerCase(Locale.ROOT);
}
```

将 `issue_key` 加入交易级 `MERGE` 和字段级 `INSERT`。`run` 成功完成字段与交易明细生成后调用 `ledgerService.materializeBatch(batchId, LocalDate.parse(reportDate, BASIC_ISO_DATE))`。捕获任一步骤的运行时异常时删除该批次的 `ana_report_export_summary`、两张导出明细；因为台账更新尚未开始或在事务内完成，失败不累计历史次数。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -Dtest=ReportExportBatchRunnerTest,DiffIssueLedgerServiceTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add src/main/java/com/spdb/report/ReportExportBatchRunner.java src/test/java/com/spdb/report/ReportExportBatchRunnerTest.java
git commit -m "feat: link report details to issue ledger"
```

### Task 4: 当前批次详情和 Excel 展示历史统计

**Files:**
- Modify: `src/main/java/com/spdb/report/ReportExportTransactionDetailRow.java`
- Modify: `src/main/java/com/spdb/report/ReportExportFieldDetailRow.java`
- Modify: `src/main/java/com/spdb/report/ReportExportCommandService.java`
- Modify: `src/main/java/com/spdb/report/ReportExportExcelService.java`
- Modify: `src/main/resources/templates/report-exports/detail.html`
- Modify: `src/test/java/com/spdb/report/ReportExportCommandServiceTest.java`
- Modify: `src/test/java/com/spdb/report/ReportExportExcelServiceTest.java`
- Modify: `src/test/java/com/spdb/web/ReportExportControllerTest.java`

- [ ] **Step 1: 写入失败测试**

扩展两类 detail record 的测试夹具，插入 `historical_occurrence_count=3`、`first_seen_date=2026-07-01`、`previous_seen_date=2026-07-20`，断言查询对象和详情模型保留它们。Excel 测试断言表头和数据行出现“历史出现批次数”“首次出现日期”“上次出现日期”。模板测试断言两个明细表列有这些字段且不显示空日期为字符串 `null`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=ReportExportCommandServiceTest,ReportExportExcelServiceTest,ReportExportControllerTest test`

Expected: FAIL，record 构造参数或导出列尚不存在。

- [ ] **Step 3: 实现读取与展示**

在两个 record 尾部增加 `long historicalOccurrenceCount, LocalDate firstSeenDate, LocalDate previousSeenDate`。所有明细查询显式选择新增列并映射为 `LocalDate`。Excel 查询与标题数组追加三个列；模板的交易级与字段级表格在问题描述后展示历史次数和两项日期，并将 `issue_id` 链接到 `/diff-issues/{id}`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -Dtest=ReportExportCommandServiceTest,ReportExportExcelServiceTest,ReportExportControllerTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add src/main/java/com/spdb/report/ReportExportTransactionDetailRow.java src/main/java/com/spdb/report/ReportExportFieldDetailRow.java src/main/java/com/spdb/report/ReportExportCommandService.java src/main/java/com/spdb/report/ReportExportExcelService.java src/main/resources/templates/report-exports/detail.html src/test/java/com/spdb/report/ReportExportCommandServiceTest.java src/test/java/com/spdb/report/ReportExportExcelServiceTest.java src/test/java/com/spdb/web/ReportExportControllerTest.java
git commit -m "feat: show issue history on export details"
```

### Task 5: 台账查询、维护页面与导航

**Files:**
- Create: `src/main/java/com/spdb/web/DiffIssueController.java`
- Create: `src/main/resources/templates/diff-issues/list.html`
- Create: `src/main/resources/templates/diff-issues/detail.html`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Create: `src/test/java/com/spdb/web/DiffIssueControllerTest.java`
- Create: `src/test/java/com/spdb/web/DiffIssueTemplateTest.java`

- [ ] **Step 1: 写入失败测试**

控制器测试使用伪造 `DiffIssueLedgerService`，断言：`GET /diff-issues` 使用 50 条分页且传递级别、状态、服务码、领域、负责人、日期范围、关键字筛选；详情不存在时返回 404；`PATCH /api/diff-issues/{id}` 将请求字段传给 `update`；非法已解决无日期返回 400；乐观锁冲突返回 409。模板测试断言存在筛选字段、只读身份字段、维护字段和 `/diff-issues` 导航入口。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=DiffIssueControllerTest,DiffIssueTemplateTest test`

Expected: FAIL，控制器和模板不存在。

- [ ] **Step 3: 实现服务查询与 MVC/JSON 边界**

在 `DiffIssueLedgerService` 增加：

```java
public PagedResult<DiffIssueRow> search(DiffIssueSearch search, PageRequestParams page)
public DiffIssueRow find(long issueId)
```

`DiffIssueSearch` 保存全部可选过滤条件。查询使用命名参数，日期范围比较 `first_seen_date`/`last_seen_date`，关键字匹配服务码、交易码、交易名、规范化字段名和问题描述。

`DiffIssueController` 提供 `GET /diff-issues` 和 `GET /diff-issues/{id}` 渲染页面；提供 `PATCH /api/diff-issues/{id}` 接收 JSON `DiffIssueUpdate` 与 `If-Unmodified-Since`，分别映射 400、404、409。列表页用现有 `panel`、`filter-grid`、`table-wrap`、`pager` 样式；详情页采用单层表单，不允许编辑 `issue_key`、级别、问题身份或历史统计。侧边栏在“报表明细导出”旁增加“问题台账”链接，激活值为 `diff-issues`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -Dtest=DiffIssueControllerTest,DiffIssueTemplateTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add src/main/java/com/spdb/report/DiffIssueLedgerService.java src/main/java/com/spdb/web/DiffIssueController.java src/main/resources/templates/diff-issues/list.html src/main/resources/templates/diff-issues/detail.html src/main/resources/templates/fragments/layout.html src/test/java/com/spdb/web/DiffIssueControllerTest.java src/test/java/com/spdb/web/DiffIssueTemplateTest.java
git commit -m "feat: add diff issue ledger page"
```

### Task 6: 全量验证和回归

**Files:**
- Modify only if a failing test identifies a defect in its owning file.

- [ ] **Step 1: 执行聚焦测试集**

Run: `mvn -Dtest=DatabaseScriptLayoutTest,DiffIssueLedgerServiceTest,ReportExportBatchRunnerTest,ReportExportCommandServiceTest,ReportExportExcelServiceTest,DiffIssueControllerTest,DiffIssueTemplateTest test`

Expected: PASS。

- [ ] **Step 2: 执行完整测试集**

Run: `mvn test`

Expected: PASS，无失败、错误或跳过的新增测试。

- [ ] **Step 3: 检查变更与页面入口**

Run: `git diff --check; git status --short`

Expected: 无空白错误；仅包含本功能预期文件。启动应用后访问 `/diff-issues`，确认导航、筛选、编辑和从报表明细跳转均可用。
