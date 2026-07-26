# 报表明细导出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在“执行分析”中提供独立的异步批量任务，直接从当前 `tss_*` 表生成领域汇总、交易级问题明细和字段级问题明细。

**Architecture:** 新建 `com.spdb.reportexport` 模块管理命令生命周期和事务性物化。批处理器不读取采样结果表：交易问题由 `tss_tran_comp` 驱动并按响应码存在与否分支归一，字段问题仅由 `tss_field_comp` 驱动；汇总和两类跟踪明细均以新批次号隔离。

**Tech Stack:** Java 17、Spring MVC、Spring JDBC、Spring Transaction、Spring `ThreadPoolTaskExecutor`、Thymeleaf、PostgreSQL/GaussDB、H2 PostgreSQL mode、JUnit 5、AssertJ。

---

## 文件结构

- Create: `src/main/java/com/spdb/reportexport/ReportExportCommandRow.java` - 任务列表与详情行。
- Create: `src/main/java/com/spdb/reportexport/ReportExportSummaryRow.java` - 单领域汇总行。
- Create: `src/main/java/com/spdb/reportexport/ReportExportTaskLauncher.java` - 异步启动边界。
- Create: `src/main/java/com/spdb/reportexport/ReportExportAsyncExecutor.java` - 任务线程执行器。
- Create: `src/main/java/com/spdb/reportexport/ReportExportBatchRunner.java` - 三类结果的事务性 SQL 物化。
- Create: `src/main/java/com/spdb/reportexport/ReportExportCommandService.java` - 批次创建、状态迁移和查询。
- Create: `src/main/java/com/spdb/web/ReportExportController.java` - 页面路由与提交入口。
- Create: `src/main/resources/templates/report-exports/commands.html` - 任务列表和创建操作。
- Create: `src/main/resources/templates/report-exports/summary.html` - 领域汇总详情。
- Create: `src/main/resources/templates/report-exports/transaction-details.html` - 本批次交易级跟踪明细。
- Create: `src/main/resources/templates/report-exports/field-details.html` - 本批次字段级跟踪明细。
- Create: `src/test/java/com/spdb/reportexport/ReportExportCommandServiceTest.java` - 命令生命周期测试。
- Create: `src/test/java/com/spdb/reportexport/ReportExportBatchRunnerTest.java` - 直接源表归一和回滚测试。
- Create: `src/test/java/com/spdb/web/ReportExportControllerTest.java` - 控制器测试。
- Modify: `db/ddl.sql` - 新命令表、汇总表、约束、注释和索引。
- Modify: `src/main/resources/templates/fragments/layout.html` - 新菜单链接。
- Modify: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java` - 新 DDL 布局断言。
- Modify: `src/test/java/com/spdb/web/SampleDetailTemplateTest.java` - 导航模板断言；若职责变得不清晰，改名为 `NavigationTemplateTest` 后更新测试类引用。

### Task 1: 落库结构

**Files:**
- Modify: `db/ddl.sql`
- Modify: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] **Step 1: 写 DDL 布局失败测试**

在 `DatabaseScriptLayoutTest` 增加以下测试，确保命令和汇总表不是对旧采样表的复用：

```java
@Test
void ddlContainsReportExportCommandAndSummaryTables() throws Exception {
    String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8).toLowerCase();

    assertThat(ddl).contains("create table if not exists ana_report_export_command");
    assertThat(ddl).contains("batch_id varchar(64) not null");
    assertThat(ddl).contains("report_date varchar(8) not null");
    assertThat(ddl).contains("check (status in ('pending','running','succeeded','failed'))");
    assertThat(ddl).contains("constraint uk_ana_report_export_command_batch unique (batch_id)");
    assertThat(ddl).contains("create table if not exists ana_report_export_summary");
    assertThat(ddl).contains("constraint uk_ana_report_export_summary unique (batch_id, module_name)");
    assertThat(ddl).contains("covered_528_interface_count bigint not null default 0");
    assertThat(ddl).contains("success_rate numeric(12,8) not null default 0");
    assertThat(ddl).contains("diff_528_field_count bigint not null default 0");
    assertThat(ddl).contains("idx_ana_report_export_command_status");
    assertThat(ddl).contains("idx_ana_report_export_summary_batch");
}
```

- [ ] **Step 2: 运行失败测试**

Run: `mvn "-Dtest=DatabaseScriptLayoutTest#ddlContainsReportExportCommandAndSummaryTables" test`

Expected: FAIL，因为两个新表和索引尚不存在。

- [ ] **Step 3: 在 DDL 中添加命令表和汇总表**

在 `ana_sampling_*` 相关定义之前添加如下可重复执行的建表和索引 SQL，并为每一列按现有风格添加中文 `comment on`：

```sql
create table if not exists ana_report_export_command (
    command_id bigserial primary key,
    batch_id varchar(64) not null,
    report_date varchar(8) not null,
    status varchar(32) not null default 'PENDING',
    started_time timestamp,
    ended_time timestamp,
    error_message varchar(4000),
    created_time timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_report_export_command_batch unique (batch_id),
    constraint ck_ana_report_export_command_status
        check (status in ('PENDING','RUNNING','SUCCEEDED','FAILED'))
);

create table if not exists ana_report_export_summary (
    summary_id bigserial primary key,
    batch_id varchar(64) not null,
    report_date varchar(8) not null,
    module_name varchar(100) not null,
    covered_528_interface_count bigint not null default 0,
    sent_transaction_count bigint not null default 0,
    comp_result_1_count bigint not null default 0,
    comp_result_2_count bigint not null default 0,
    comp_result_3_count bigint not null default 0,
    comp_result_4_count bigint not null default 0,
    comp_result_8_count bigint not null default 0,
    success_rate numeric(12,8) not null default 0,
    diff_528_field_count bigint not null default 0,
    created_time timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_report_export_summary unique (batch_id, module_name)
);

create index if not exists idx_ana_report_export_command_status
on ana_report_export_command(status, created_time desc);

create index if not exists idx_ana_report_export_summary_batch
on ana_report_export_summary(batch_id, module_name);
```

- [ ] **Step 4: 运行 DDL 测试**

Run: `mvn "-Dtest=DatabaseScriptLayoutTest#ddlContainsReportExportCommandAndSummaryTables" test`

Expected: PASS。

- [ ] **Step 5: 提交结构变更**

```powershell
git add -- db/ddl.sql src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java
git commit -m "feat: add report export persistence schema"
```

### Task 2: 命令生命周期

**Files:**
- Create: `src/main/java/com/spdb/reportexport/ReportExportCommandRow.java`
- Create: `src/main/java/com/spdb/reportexport/ReportExportSummaryRow.java`
- Create: `src/main/java/com/spdb/reportexport/ReportExportTaskLauncher.java`
- Create: `src/main/java/com/spdb/reportexport/ReportExportCommandService.java`
- Create: `src/test/java/com/spdb/reportexport/ReportExportCommandServiceTest.java`

- [ ] **Step 1: 写命令服务失败测试**

使用 H2 PostgreSQL mode 创建 `ana_report_export_command` 和 `ana_report_export_summary`，并在测试中传入记录调用参数的 `ReportExportTaskLauncher`。测试固定时钟并断言：

```java
@Test
void createStartsAReportExportWithoutSourceDate() {
    String batchId = service.createAndStart();

    Map<String, Object> row = jdbc.queryForMap(
            "select batch_id, report_date, status from ana_report_export_command where batch_id = :batchId",
            new MapSqlParameterSource("batchId", batchId));
    assertThat(batchId).startsWith("RPT20260726-");
    assertThat(row).containsEntry("report_date", "20260726").containsEntry("status", "PENDING");
    assertThat(launchedBatchIds).containsExactly(batchId);
}

@Test
void stateUpdatesAreCompareAndSetAndSummaryIsQueryable() {
    String batchId = insertPendingCommand("RPT20260726-101530-0001");

    assertThat(service.markRunning(batchId)).isTrue();
    assertThat(service.markRunning(batchId)).isFalse();
    jdbc.update("insert into ana_report_export_summary(batch_id, report_date, module_name, sent_transaction_count) "
                    + "values (:batchId, '20260726', '存款', 12)",
            new MapSqlParameterSource("batchId", batchId));
    service.markSucceeded(batchId);

    assertThat(service.findByBatchId(batchId).status()).isEqualTo("SUCCEEDED");
    assertThat(service.findSummaries(batchId)).extracting(ReportExportSummaryRow::sentTransactionCount)
            .containsExactly(12L);
}
```

- [ ] **Step 2: 运行失败测试**

Run: `mvn "-Dtest=ReportExportCommandServiceTest" test`

Expected: FAIL，因为 `com.spdb.reportexport` 类型尚不存在。

- [ ] **Step 3: 实现记录、启动边界和命令服务**

定义记录和接口：

```java
public record ReportExportCommandRow(Long commandId, String batchId, String reportDate, String status,
        LocalDateTime startedTime, LocalDateTime endedTime, String errorMessage, LocalDateTime createdTime) {}

public record ReportExportSummaryRow(String batchId, String reportDate, String moduleName,
        long covered528InterfaceCount, long sentTransactionCount, long compResult1Count,
        long compResult2Count, long compResult3Count, long compResult4Count, long compResult8Count,
        BigDecimal successRate, long diff528FieldCount) {}

public interface ReportExportTaskLauncher {
    void launch(String batchId);
}
```

`ReportExportCommandService` 使用 `Clock`、`SecureRandom` 和 `ObjectProvider<ReportExportTaskLauncher>`。`createAndStart()` 生成 `RPTyyyyMMdd-HHmmss-####`，插入 `PENDING` 后启动任务。状态迁移必须使用下列 compare-and-set SQL，避免重复调度：

```java
public boolean markRunning(String batchId) {
    return jdbc.update("""
            update ana_report_export_command
               set status = 'RUNNING', started_time = current_timestamp,
                   ended_time = null, error_message = null, updated_at = current_timestamp
             where batch_id = :batchId and status = 'PENDING'
            """, params(batchId)) == 1;
}
```

实现 `markSucceeded`，仅更新 `RUNNING`；实现 `markFailed`，仅更新 `RUNNING` 或 `PENDING`，并把错误截断为 4000 个字符。`findByBatchId` 和 `findSummaries` 必须按 `batch_id` 直接查询，不读取任何 `ana_sampling_*` 表。

- [ ] **Step 4: 运行命令服务测试**

Run: `mvn "-Dtest=ReportExportCommandServiceTest" test`

Expected: PASS。

- [ ] **Step 5: 提交命令生命周期**

```powershell
git add -- src/main/java/com/spdb/reportexport/ReportExportCommandRow.java src/main/java/com/spdb/reportexport/ReportExportSummaryRow.java src/main/java/com/spdb/reportexport/ReportExportTaskLauncher.java src/main/java/com/spdb/reportexport/ReportExportCommandService.java src/test/java/com/spdb/reportexport/ReportExportCommandServiceTest.java
git commit -m "feat: add report export commands"
```

### Task 3: 直接源表批处理与归一

**Files:**
- Create: `src/main/java/com/spdb/reportexport/ReportExportBatchRunner.java`
- Create: `src/test/java/com/spdb/reportexport/ReportExportBatchRunnerTest.java`

- [ ] **Step 1: 写批处理失败测试**

测试准备：创建三张 `tss_*` 源表、交易目录、字段映射、两张既有跟踪导出表和新汇总表。插入以下最小数据：

```sql
insert into tss_tran_comp values
('M1', '20260725', 1, 1, '20260725', 'SVC_A&soap', '0', '1', '1'),
('M2', '20260725', 1, 1, '20260725', 'SVC_A&soap', '0', '1', '1'),
('M3', '20260725', 1, 1, '20260725', 'SVC_B&bizjson', '0', '3', '3'),
('M4', '20260725', 1, 1, '20260725', 'SVC_C&sop', '0', '4', '4'),
('M5', '20260725', 1, 1, '20260725', 'SVC_D&sop', '0', '7', '7');
insert into tss_retcode_comp values ('M1', 'SVC_A&soap', '20260725', '00000000000', '原成功', 'E01', '新失败');
insert into tss_field_comp values
('F1', '20260725', 'SVC_C&sop', 1, 1, 0, 1, 'sop', 'items.0.amount', '100', 'Amount', '200', '0'),
('F2', '20260725', 'SVC_C&sop', 1, 1, 0, 2, 'sop', 'items.0.currency', 'CNY', 'Currency', 'USD', '0');
```

调用 `runner.run(batchId, reportDate, startedAt)` 后断言：

```java
assertThat(count("ana_tran_diff_tracking_export", batchId)).isEqualTo(3L);
assertThat(count("ana_field_diff_tracking_export", batchId)).isEqualTo(2L);
assertThat(jdbc.queryForObject("select count(*) from ana_tran_diff_tracking_export "
        + "where source_batch_id = :batchId and service_code = 'SVC_A'", params(batchId), Long.class)).isEqualTo(1L);
assertThat(jdbc.queryForObject("select count(*) from ana_tran_diff_tracking_export "
        + "where source_batch_id = :batchId and service_code = 'SVC_C'", params(batchId), Long.class)).isZero();
assertThat(jdbc.queryForObject("select count(*) from ana_report_export_summary "
        + "where batch_id = :batchId and module_name = '未配置领域'", params(batchId), Long.class)).isEqualTo(1L);
```

在种子中将 `SVC_A`、`SVC_B` 和 `SVC_C` 配置为“存款”领域，将 `SVC_D` 留空。再断言 `items.0.amount` 与 `items.0.currency` 均使用 `items.0` 作为规范化原字段名，但因目标字段名不同保留两条字段级明细；汇总中领域“存款”的成功率等于 `2 / 4`（`comp_result 3 + 4` 除以发送量）。

增加回滚测试：让字段导出表缺少必填列或插入约束拒绝，断言汇总与交易级导出表均没有该批次行。

- [ ] **Step 2: 运行失败测试**

Run: `mvn "-Dtest=ReportExportBatchRunnerTest" test`

Expected: FAIL，因为批处理器尚不存在。

- [ ] **Step 3: 实现单事务批处理器**

`ReportExportBatchRunner` 构造 `TransactionTemplate`，并只暴露：

```java
public void run(String batchId, String reportDate, LocalDateTime exportTime) {
    transactionTemplate.executeWithoutResult(status -> {
        insertSummaries(batchId, reportDate);
        insertTransactionDetails(batchId, reportDate, exportTime);
        insertFieldDetails(batchId, reportDate, exportTime);
    });
}
```

实现时采用 set-based `insert ... select`，不把源表行读取到 Java 集合。

交易 SQL 必须满足：

```sql
where t.comp_result in ('1', '2', '3', '7', '8')
```

并在 CTE 中生成两种带前缀的键：

```sql
case when r.mesg_seq is not null then
    'RET|' || split_part(r.service_code, '&', 1) || '|' || coalesce(r.orig_error_code, '') || '|' || coalesce(r.dest_error_code, '')
else
    'TRAN|' || coalesce(t.dest_trcd, '') || '|' || t.comp_result
end as issue_key
```

按 `issue_key` 使用 `row_number() over (partition by issue_key order by t.mesg_seq, t.conv_index, t.conv_cindex)` 取首行，再插入跟踪表。响应码分支写入响应码和描述；无响应码分支写入空响应码列。服务码取响应码服务码或 `dest_trcd` 的 `&` 前段。

字段 SQL 只能从 `tss_field_comp f` 读取。规范化字段名使用：

```sql
case when position('.' in coalesce(f.orig_field_name, '')) > 0
     then split_part(f.orig_field_name, '.', 1) || '.' || split_part(f.orig_field_name, '.', 2)
     else coalesce(f.orig_field_name, '') end
```

按“服务码 + 规范化原字段名 + `dest_field_name`”分区取稳定首行。映射和目录只能 `left join` 用于填充，不得作为 `where` 或归一键条件。

汇总 SQL 必须按 `dest_trcd` 服务码关联目录领域，并以 `coalesce(c.module_name, '未配置领域')` 分组；字段数 CTE 以同样领域关联后 `count(distinct normalized_orig_field_name)`。成功率使用：

```sql
case when count(*) = 0 then 0
     else cast(sum(case when t.comp_result in ('3', '4') then 1 else 0 end) as numeric) / count(*) end
```

为 H2 写单独 SQL 常量，仅替换 PostgreSQL 不支持的 `split_part`、`position` 或 lateral join 语法；两套 SQL 必须表达相同的归一规则。

- [ ] **Step 4: 运行批处理测试**

Run: `mvn "-Dtest=ReportExportBatchRunnerTest" test`

Expected: PASS。

- [ ] **Step 5: 提交批处理实现**

```powershell
git add -- src/main/java/com/spdb/reportexport/ReportExportBatchRunner.java src/test/java/com/spdb/reportexport/ReportExportBatchRunnerTest.java
git commit -m "feat: materialize report export details"
```

### Task 4: 异步执行器

**Files:**
- Create: `src/main/java/com/spdb/reportexport/ReportExportAsyncExecutor.java`
- Modify: `src/main/java/com/spdb/reportexport/ReportExportCommandService.java`
- Modify: `src/test/java/com/spdb/reportexport/ReportExportCommandServiceTest.java`

- [ ] **Step 1: 写异步失败路径测试**

给 `ReportExportCommandServiceTest` 增加一个 `ReportExportBatchRunner` 抛出异常的测试替身，验证任务保持可追溯：

```java
@Test
void executorMarksCommandFailedWhenBatchRunnerThrows() {
    String batchId = insertPendingCommand("RPT20260726-101530-0002");
    executor.runSynchronously(batchId);

    ReportExportCommandRow row = service.findByBatchId(batchId);
    assertThat(row.status()).isEqualTo("FAILED");
    assertThat(row.errorMessage()).contains("simulated failure");
}
```

- [ ] **Step 2: 运行失败测试**

Run: `mvn "-Dtest=ReportExportCommandServiceTest#executorMarksCommandFailedWhenBatchRunnerThrows" test`

Expected: FAIL，因为执行器尚不存在。

- [ ] **Step 3: 实现执行器**

使用已有 `samplingTaskExecutor`，避免新建线程池。执行器的核心逻辑如下，并将 `runSynchronously` 设为包可见以供测试：

```java
@Component
public class ReportExportAsyncExecutor implements ReportExportTaskLauncher {
    @Override
    public void launch(String batchId) {
        executor.execute(() -> runSynchronously(batchId));
    }

    void runSynchronously(String batchId) {
        if (!commandService.markRunning(batchId)) return;
        try {
            ReportExportCommandRow command = commandService.findByBatchId(batchId);
            batchRunner.run(command.batchId(), command.reportDate(), LocalDateTime.now(clock));
            commandService.markSucceeded(batchId);
        } catch (RuntimeException exception) {
            commandService.markFailed(batchId, exception.getMessage());
        }
    }
}
```

注入 `ObjectProvider<ReportExportCommandService>`，与现有 `SamplingAsyncExecutor` 一致，避免循环依赖。`findByBatchId` 返回 `null` 时抛出明确异常并转为 `FAILED`。

- [ ] **Step 4: 运行异步测试**

Run: `mvn "-Dtest=ReportExportCommandServiceTest" test`

Expected: PASS。

- [ ] **Step 5: 提交异步执行器**

```powershell
git add -- src/main/java/com/spdb/reportexport/ReportExportAsyncExecutor.java src/main/java/com/spdb/reportexport/ReportExportCommandService.java src/test/java/com/spdb/reportexport/ReportExportCommandServiceTest.java
git commit -m "feat: run report exports asynchronously"
```

### Task 5: 菜单、任务页和汇总详情

**Files:**
- Create: `src/main/java/com/spdb/web/ReportExportController.java`
- Create: `src/main/resources/templates/report-exports/commands.html`
- Create: `src/main/resources/templates/report-exports/summary.html`
- Create: `src/main/resources/templates/report-exports/transaction-details.html`
- Create: `src/main/resources/templates/report-exports/field-details.html`
- Create: `src/test/java/com/spdb/web/ReportExportControllerTest.java`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Modify: `src/test/java/com/spdb/web/SampleDetailTemplateTest.java`

- [ ] **Step 1: 写控制器与模板失败测试**

测试控制器重定向和模型：

```java
@Test
void createRedirectsToTheNewReportExportBatch() {
    String view = controller.create(redirectAttributes);

    assertThat(view).isEqualTo("redirect:/report-exports");
    assertThat(redirectAttributes.getFlashAttributes()).containsKey("message");
}

@Test
void summaryOnlyShowsRowsForSucceededBatch() {
    String view = controller.summary("RPT20260726-101530-0001", model);

    assertThat(view).isEqualTo("report-exports/summary");
    assertThat(model.getAttribute("active")).isEqualTo("report-exports");
}
```

模板测试断言：

```java
assertThat(layout).contains("href=\"/report-exports\"").contains("报表明细导出");
assertThat(commands).contains("action=\"/report-exports\"").contains("执行报表明细导出");
assertThat(summary).contains("覆盖528接口").contains("二者均失败不一致").contains("差异字段数（按528字段去重）");
```

- [ ] **Step 2: 运行失败测试**

Run: `mvn "-Dtest=ReportExportControllerTest,SampleDetailTemplateTest" test`

Expected: FAIL，因为控制器、页面和导航尚不存在。

- [ ] **Step 3: 实现路由和模板**

实现以下路由：

```java
@GetMapping("/report-exports")
String commands(Model model)

@PostMapping("/report-exports")
String create(RedirectAttributes redirectAttributes)

@GetMapping("/report-exports/{batchId}")
String summary(@PathVariable String batchId, Model model)

@GetMapping("/report-exports/{batchId}/transaction-details")
String transactionDetails(@PathVariable String batchId)

@GetMapping("/report-exports/{batchId}/field-details")
String fieldDetails(@PathVariable String batchId)
```

后两个入口重定向到既有 `/samples/transaction-diffs?batchId={batchId}` 与 `/samples/field-diffs?batchId={batchId}` 不可行，因为旧页面查询的是 `ana_*_diff_result`。因此在本任务中将 `SampleController` 的明细查询扩展为识别 `reportExportBatchId`，或新增专用只读路由和模板；选择后者：在 `ReportExportController` 中按 `source_batch_id` 查询两张跟踪表并分别渲染 `report-exports/transaction-details.html`、`report-exports/field-details.html`。同时创建这两个模板，并在此任务的测试中断言列出对应批次记录，避免旧采样页面耦合。

`commands.html` 复用 `fragments/layout :: sidebar` 和 pager，含一个无输入项的 POST 表单及任务表。`summary.html` 显示设计文档定义的全部 12 个汇总列，并把成功率格式化为百分比。菜单使用 `active == 'report-exports'`。

- [ ] **Step 4: 运行 Web 测试**

Run: `mvn "-Dtest=ReportExportControllerTest,SampleDetailTemplateTest" test`

Expected: PASS。

- [ ] **Step 5: 提交页面功能**

```powershell
git add -- src/main/java/com/spdb/web/ReportExportController.java src/main/resources/templates/fragments/layout.html src/main/resources/templates/report-exports src/test/java/com/spdb/web/ReportExportControllerTest.java src/test/java/com/spdb/web/SampleDetailTemplateTest.java
git commit -m "feat: add report detail export pages"
```

### Task 6: 全量验证

**Files:** 所有上述文件。

- [ ] **Step 1: 运行功能相关测试**

Run: `mvn "-Dtest=DatabaseScriptLayoutTest,ReportExportCommandServiceTest,ReportExportBatchRunnerTest,ReportExportControllerTest,SampleDetailTemplateTest" test`

Expected: `Failures: 0, Errors: 0`。

- [ ] **Step 2: 运行全量测试**

Run: `mvn test`

Expected: `BUILD SUCCESS`。

- [ ] **Step 3: 检查工作区**

Run: `git diff --check; git status --short`

Expected: 无空白错误；不得修改或回退已有的用户工作区改动。

- [ ] **Step 4: 审查提交内容**

Run: `git log --oneline -6; git show --stat --oneline HEAD`

Expected: 本功能提交只包含报表明细导出相关文件。
