# 批次领域报表 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增独立的手动批次领域报表任务与页面，以小型持久化汇总结果替代对大规模原始明细的在线扫描。

**Architecture:** 报表任务以完成的采样批次为输入，异步读取当前保留的响应日志、交易比对、字段映射和字段差异数据，分别预聚合后写入领域交易统计、领域字段统计和配置缺口表。页面仅查询这些汇总表，并通过现有轮询模式展示任务状态。

**Tech Stack:** Spring Boot 3、Spring JDBC、PostgreSQL、Thymeleaf、JUnit 5、AssertJ、原生 JavaScript。

---

### Task 1: 创建报表存储模型和 DDL

**Files:**
- Modify: `db/ddl.sql`
- Create: `src/main/java/com/spdb/report/BatchDomainReportRow.java`
- Create: `src/main/java/com/spdb/report/BatchDomainFieldStatRow.java`
- Create: `src/main/java/com/spdb/report/BatchReportGapRow.java`
- Create: `src/main/java/com/spdb/report/BatchDomainReportCommandRow.java`
- Test: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] **Step 1: 写失败 DDL 布局测试**

在 `DatabaseScriptLayoutTest` 断言 `ddl.sql` 包含：

```java
assertThat(ddl).contains("ana_batch_domain_report_command");
assertThat(ddl).contains("ana_batch_domain_transaction_stat");
assertThat(ddl).contains("ana_batch_domain_field_stat");
assertThat(ddl).contains("ana_batch_report_gap");
assertThat(ddl).contains("batch_id varchar(64) not null");
```

- [ ] **Step 2: 验证 RED**

运行：`mvn -Dtest=DatabaseScriptLayoutTest test`

预期：失败，因新表不存在。

- [ ] **Step 3: 添加 DDL**

添加四张表：命令表持有批次、状态、开始/结束时间、错误信息；交易统计表持有领域、覆盖服务数、响应量及结果 1/2/3/4/8；字段统计表持有字段总数、差异与无差异数；缺口表持有 `UNCONFIGURED_SERVICE` 或 `UNMAPPED_FIELD`、服务码、报文类型、字段标识、影响量。为所有结果表创建 `(batch_id, module_name)` 或 `(batch_id, gap_type)` 索引。

- [ ] **Step 4: 添加 records 并验证 GREEN**

创建与各表列对应的 Java records，运行：`mvn -Dtest=DatabaseScriptLayoutTest test`。

### Task 2: 实现按批次的预聚合服务

**Files:**
- Create: `src/main/java/com/spdb/report/BatchDomainReportService.java`
- Create: `src/main/java/com/spdb/report/BatchDomainReportRunner.java`
- Create: `src/main/java/com/spdb/report/BatchDomainReportTaskLauncher.java`
- Create: `src/main/java/com/spdb/report/BatchDomainReportAsyncExecutor.java`
- Test: `src/test/java/com/spdb/report/BatchDomainReportServiceTest.java`

- [ ] **Step 1: 写聚合失败测试**

使用 H2 建立最小响应日志、交易目录、交易比对、字段映射及字段差异数据；测试一个批次的领域统计符合：

```java
assertThat(rows).singleElement().satisfies(row -> {
    assertThat(row.moduleName()).isEqualTo("存款");
    assertThat(row.coveredServiceCount()).isEqualTo(2);
    assertThat(row.sentTransactionCount()).isEqualTo(3);
    assertThat(row.compResult2Count()).isEqualTo(1);
    assertThat(row.compResult8Count()).isEqualTo(1);
});
```

同时测试字段统计和两类缺口：未配置服务与未映射字段。

- [ ] **Step 2: 验证 RED**

运行：`mvn -Dtest=BatchDomainReportServiceTest test`

预期：失败，报表服务不存在。

- [ ] **Step 3: 实现聚合 SQL**

`BatchDomainReportService.generate(batchId)` 按如下顺序执行：

1. 校验批次存在且状态为 `COMPLETED`。
2. 以 `split_part(msg_flow_log_response.txn_code, '&', 1)` 聚合响应日志，不更新源表。
3. 将已聚合服务码关联目录领域；未关联行写入 `UNCONFIGURED_SERVICE` 缺口表。
4. 按服务码聚合 `tss_tran_comp` 的 `comp_result` 1、2、3、4、8，再关联领域。
5. 以字段映射 `(tran_code, service_code, std_field_name)` 去重统计字段总数；以批次字段差异同一键去重统计已映射差异字段；计算无差异字段。
6. 将 `UNMAPPED` 字段写入 `UNMAPPED_FIELD` 缺口表。
7. 在事务中替换同一批次旧结果并发布新结果。

- [ ] **Step 4: 实现任务状态与异步执行**

命令创建时写入 `PENDING`；Runner 将状态改为 `RUNNING`，调用聚合服务后改为 `SUCCEEDED`，异常时写入 `FAILED` 与截断错误信息。`BatchDomainReportAsyncExecutor` 复用专用 `ThreadPoolTaskExecutor` 执行 Runner。

- [ ] **Step 5: 验证 GREEN**

运行：`mvn -Dtest=BatchDomainReportServiceTest test`

预期：`BUILD SUCCESS`。

### Task 3: 暴露独立页面与菜单

**Files:**
- Create: `src/main/java/com/spdb/web/BatchDomainReportController.java`
- Create: `src/main/resources/templates/sampling/domain-reports.html`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/com/spdb/web/BatchDomainReportControllerTest.java`
- Test: `src/test/java/com/spdb/web/BatchDomainReportTemplateTest.java`
- Test: `src/test/java/com/spdb/web/LayoutTemplateTest.java`

- [ ] **Step 1: 写失败页面与菜单测试**

断言导航在“执行分析”中包含：

```java
assertThat(html).contains("/sampling/domain-reports");
assertThat(html).contains("批次领域报表");
```

断言新模板包含批次选择、生成表单、任务状态、五个结果列、三个字段列及配置缺口区。

- [ ] **Step 2: 验证 RED**

运行：`mvn -Dtest=BatchDomainReportControllerTest,BatchDomainReportTemplateTest,LayoutTemplateTest test`

预期：失败，控制器与模板不存在。

- [ ] **Step 3: 实现控制器和模板**

实现：

```java
@GetMapping("/sampling/domain-reports")
String reports(@RequestParam(required = false) String batchId, Model model)

@PostMapping("/sampling/domain-reports")
String generate(@RequestParam String batchId, RedirectAttributes attributes)

@GetMapping("/sampling/domain-reports/{batchId}/progress")
@ResponseBody BatchDomainReportCommandRow progress(@PathVariable String batchId)
```

页面选择批次、发起任务、轮询 `PENDING/RUNNING`、展示成功报表或失败提示；宽表使用现有横向滚动容器。缺口区以小字展示服务/交易或字段标识和影响量。

- [ ] **Step 4: 验证 GREEN**

运行：`mvn -Dtest=BatchDomainReportControllerTest,BatchDomainReportTemplateTest,LayoutTemplateTest test`

预期：`BUILD SUCCESS`。

### Task 4: 集成验证

**Files:**
- Modify: `db/seed.sql`（仅增加报表测试所需的可重复数据时）

- [ ] **Step 1: 运行相关测试集合**

运行：

```powershell
mvn '-Dtest=BatchDomainReportServiceTest,BatchDomainReportControllerTest,BatchDomainReportTemplateTest,LayoutTemplateTest,AppCssStyleTest,DatabaseScriptLayoutTest' test
```

预期：`BUILD SUCCESS`。

- [ ] **Step 2: 浏览器验证**

启动应用，选择当前保留源明细的完成批次，点击“生成报表”，确认状态变为成功、领域行展示、`comp_result = '8'` 关注列可见、底部存在配置缺口提示；在 390px 宽度确认宽表可横向滚动且左侧抽屉可用。
