# 报表明细导出阶段组件 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将交易级明细、字段级明细和汇总报表拆为独立步骤组件，并在详情页展示纵向阶段进度。

**Architecture:** `ReportExportBatchRunner` 创建共享上下文并按交易级、字段级、汇总顺序调度步骤。命令服务在阶段开始时持久化 `current_stage`，控制器将任务状态转为页面阶段视图。

**Tech Stack:** Java 17、Spring JDBC、Spring MVC、Thymeleaf、JUnit 5、AssertJ、Mockito。

---

## 文件结构

- Create: `src/main/java/com/spdb/report/ReportExportStage.java`：阶段枚举和标签。
- Create: `src/main/java/com/spdb/report/ReportExportStep.java`：步骤接口。
- Create: `src/main/java/com/spdb/report/ReportExportContext.java`：批次和共享引用数据上下文。
- Create: `src/main/java/com/spdb/report/ReportExportReferenceDataLoader.java`：加载交易目录和字段映射。
- Create: `src/main/java/com/spdb/report/TransactionDetailExportStep.java`：交易级明细生成。
- Create: `src/main/java/com/spdb/report/FieldDetailExportStep.java`：字段级明细生成。
- Create: `src/main/java/com/spdb/report/SummaryExportStep.java`：汇总行生成。
- Create: `src/main/java/com/spdb/web/ReportExportStageView.java`：详情页阶段视图。
- Modify: `src/main/java/com/spdb/report/ReportExportBatchRunner.java`：顺序调度。
- Modify: `src/main/java/com/spdb/report/ReportExportCommandService.java`：阶段更新和行映射。
- Modify: `src/main/java/com/spdb/report/ReportExportCommandRow.java`：增加 `currentStage`。
- Modify: `src/main/java/com/spdb/web/ReportExportController.java`：提供 `stageViews` 模型。
- Modify: `src/main/resources/templates/report-exports/detail.html`：纵向阶段图标列表。
- Modify: `src/main/resources/static/css/app.css`：阶段状态样式。
- Modify: `src/test/java/com/spdb/report/ReportExportCommandServiceTest.java`：阶段持久化。
- Modify: `src/test/java/com/spdb/report/ReportExportBatchRunnerTest.java`：顺序及失败中止。
- Create: `src/test/java/com/spdb/web/ReportExportStageViewTest.java`：页面状态机。
- Modify: `src/test/java/com/spdb/web/ReportExportControllerTest.java`：详情模型。

### Task 1: 阶段模型与命令持久化

**Files:**
- Create: `src/main/java/com/spdb/report/ReportExportStage.java`
- Modify: `src/main/java/com/spdb/report/ReportExportCommandRow.java`
- Modify: `src/main/java/com/spdb/report/ReportExportCommandService.java`
- Modify: `src/test/java/com/spdb/report/ReportExportCommandServiceTest.java`

- [ ] **Step 1: 写入失败测试**

```java
@Test
void recordsCurrentStageAndClearsItOnlyAfterSuccess() {
    String batchId = service.createAndStart();
    service.markRunning(batchId);
    service.markStage(batchId, ReportExportStage.TRANSACTION_DETAILS);
    assertThat(service.findByBatchId(batchId).currentStage())
            .isEqualTo("TRANSACTION_DETAILS");

    service.markFailed(batchId, "transaction failed");
    assertThat(service.findByBatchId(batchId).currentStage())
            .isEqualTo("TRANSACTION_DETAILS");
}
```

- [ ] **Step 2: 确认测试失败**

Run: `mvn '-Dtest=ReportExportCommandServiceTest' test`

Expected: 编译失败，`ReportExportStage`、`markStage` 或 `currentStage` 尚不存在。

- [ ] **Step 3: 最小实现**

```java
public enum ReportExportStage {
    TRANSACTION_DETAILS("交易级明细"),
    FIELD_DETAILS("字段级明细"),
    SUMMARY("汇总报表");

    private final String label;
    ReportExportStage(String label) { this.label = label; }
    public String label() { return label; }
}
```

```java
public void markStage(String batchId, ReportExportStage stage) {
    jdbc.update("""
        update ana_report_export_command
           set current_stage = :stage, updated_at = current_timestamp
         where batch_id = :batchId and status = 'RUNNING'
        """, params(batchId).addValue("stage", stage.name()));
}
```

将 `current_stage` 加入命令查询和 `ReportExportCommandRow`；`markSucceeded` 清空该列，`markFailed` 保留该列。

- [ ] **Step 4: 确认测试通过**

Run: `mvn '-Dtest=ReportExportCommandServiceTest' test`

Expected: PASS。

- [ ] **Step 5: 提交**

Run: `git add src/main/java/com/spdb/report/ReportExportStage.java src/main/java/com/spdb/report/ReportExportCommandRow.java src/main/java/com/spdb/report/ReportExportCommandService.java src/test/java/com/spdb/report/ReportExportCommandServiceTest.java; git commit -m "feat: track report export execution stage"`

### Task 2: 拆分三个导出步骤

**Files:**
- Create: `src/main/java/com/spdb/report/ReportExportStep.java`
- Create: `src/main/java/com/spdb/report/ReportExportContext.java`
- Create: `src/main/java/com/spdb/report/ReportExportReferenceDataLoader.java`
- Create: `src/main/java/com/spdb/report/TransactionDetailExportStep.java`
- Create: `src/main/java/com/spdb/report/FieldDetailExportStep.java`
- Create: `src/main/java/com/spdb/report/SummaryExportStep.java`
- Modify: `src/main/java/com/spdb/report/ReportExportBatchRunner.java`
- Modify: `src/test/java/com/spdb/report/ReportExportBatchRunnerTest.java`

- [ ] **Step 1: 写入失败测试，定义顺序与中止**

```java
@Test
void runsStepsInTransactionFieldSummaryOrder() {
    runner.run("RPT1", "20260727", LocalDateTime.now());
    assertThat(events).containsExactly(
        "TRANSACTION_DETAILS", "FIELD_DETAILS", "SUMMARY");
}

@Test
void stopsAfterFailedTransactionStep() {
    doThrow(new IllegalStateException("failed")).when(transactionStep).run(any());
    assertThatThrownBy(() -> runner.run("RPT1", "20260727", LocalDateTime.now()))
        .isInstanceOf(IllegalStateException.class);
    verify(fieldStep, never()).run(any());
    verify(summaryStep, never()).run(any());
}
```

- [ ] **Step 2: 确认测试失败**

Run: `mvn '-Dtest=ReportExportBatchRunnerTest' test`

Expected: 编译失败，新步骤构造函数与接口不存在。

- [ ] **Step 3: 创建共享契约和引用数据加载器**

```java
interface ReportExportStep {
    void run(ReportExportContext context);
}

record ReportExportContext(String batchId, String reportDate,
                           LocalDateTime exportTime,
                           ReportExportReferenceData references) {
}
```

`ReportExportReferenceDataLoader.load()` 迁移现有交易目录和字段映射读取；保留服务码小写归一化、首条目录记录规则。

- [ ] **Step 4: 迁移既有生成逻辑**

`TransactionDetailExportStep` 接收 `DataSource` 与交易明细执行器，迁入游标、按服务码并行、去重和明细写入；`FieldDetailExportStep` 迁入字段读取、归一化、映射和字段表写入；`SummaryExportStep` 迁入交易/字段读取及按领域统计写入。所有既有 SQL 筛选、列值和幂等键保持不变。

- [ ] **Step 5: 将 Runner 缩减为调度器**

```java
public void run(String batchId, String reportDate, LocalDateTime exportTime) {
    ReportExportContext context = new ReportExportContext(
        batchId, reportDate, exportTime, referenceDataLoader.load());
    runStage(batchId, ReportExportStage.TRANSACTION_DETAILS, transactionStep, context);
    runStage(batchId, ReportExportStage.FIELD_DETAILS, fieldStep, context);
    runStage(batchId, ReportExportStage.SUMMARY, summaryStep, context);
}

private void runStage(String batchId, ReportExportStage stage,
                      ReportExportStep step, ReportExportContext context) {
    commandService.markStage(batchId, stage);
    step.run(context);
}
```

- [ ] **Step 6: 确认测试通过**

Run: `mvn '-Dtest=ReportExportBatchRunnerTest' test`

Expected: PASS，原交易明细、字段明细和汇总断言迁入对应步骤后仍通过。

- [ ] **Step 7: 提交**

Run: `git add src/main/java/com/spdb/report src/test/java/com/spdb/report/ReportExportBatchRunnerTest.java; git commit -m "refactor: split report export generation steps"`

### Task 3: 详情页纵向阶段图标

**Files:**
- Create: `src/main/java/com/spdb/web/ReportExportStageView.java`
- Create: `src/test/java/com/spdb/web/ReportExportStageViewTest.java`
- Modify: `src/main/java/com/spdb/web/ReportExportController.java`
- Modify: `src/main/resources/templates/report-exports/detail.html`
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/test/java/com/spdb/web/ReportExportControllerTest.java`

- [ ] **Step 1: 写入失败测试，定义失败阶段展示**

```java
@Test
void marksFailedStageAndFollowingStagesAsNotExecuted() {
    List<ReportExportStageView> views = ReportExportStageView.forCommand("FAILED", "FIELD_DETAILS");
    assertThat(views).extracting(ReportExportStageView::state)
        .containsExactly("completed", "failed", "not-executed");
}
```

- [ ] **Step 2: 确认测试失败**

Run: `mvn '-Dtest=ReportExportStageViewTest' test`

Expected: 编译失败，`ReportExportStageView` 不存在。

- [ ] **Step 3: 实现视图状态机和控制器模型**

```java
public record ReportExportStageView(String label, int number,
                                    String state, String stateLabel) {
    public static List<ReportExportStageView> forCommand(String status, String currentStage) {
        // 依次计算 completed、running、pending、failed、not-executed。
    }
}
```

在 `ReportExportController.detail` 中加入：

```java
model.addAttribute("stageViews", command == null ? List.of()
    : ReportExportStageView.forCommand(command.status(), command.currentStage()));
```

- [ ] **Step 4: 渲染列表和样式**

```html
<ol class="report-stage-list" th:if="${command != null}">
  <li th:each="stage : ${stageViews}" th:classappend="${'is-' + stage.state()}">
    <span class="report-stage-icon" th:text="${stage.state() == 'completed' ? '✓' : stage.number()}"></span>
    <span class="report-stage-name" th:text="${stage.label()}"></span>
    <span class="report-stage-state" th:text="${stage.stateLabel()}"></span>
  </li>
</ol>
```

为五种状态添加紧凑的绿色、蓝色、灰色和红色样式；轮询收到新的 `currentStage` 后刷新页面，让服务端阶段视图保持唯一来源。

- [ ] **Step 5: 确认测试通过**

Run: `mvn '-Dtest=ReportExportStageViewTest,ReportExportControllerTest' test`

Expected: PASS。

- [ ] **Step 6: 提交**

Run: `git add src/main/java/com/spdb/web src/main/resources/templates/report-exports/detail.html src/main/resources/static/css/app.css src/test/java/com/spdb/web; git commit -m "feat: show report export stage progress"`

### Task 4: 全量验证

**Files:**
- Verify: `src/main/java/com/spdb/report/`
- Verify: `src/main/java/com/spdb/web/`

- [ ] **Step 1: 运行全量测试**

Run: `mvn test`

Expected: `BUILD SUCCESS`。

- [ ] **Step 2: 检查工作区**

Run: `git diff --check; git status --short`

Expected: 无空白错误；除计划文档外无未提交文件。

- [ ] **Step 3: 手工验证**

创建任务，确认三个阶段依次显示蓝色处理中，完成后全部绿色；令字段步骤失败，确认交易级绿色、字段级红色、汇总灰色“未执行”。
