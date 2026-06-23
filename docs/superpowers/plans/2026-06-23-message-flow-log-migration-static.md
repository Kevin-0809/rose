# 报文日志跨库迁移 - 阶段一实现计划（静态页面 + 静态测试数据）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 先交付迁移功能的静态页面与 Mock 交互，包含指令列表页、进度详情页、导航接入、JS 轮询 Mock JSON，覆盖各状态展示效果。

**Architecture:** `MigrationController` 返回硬编码 Mock 数据（无数据库依赖），两个 Thymeleaf 模板沿用现有 `fragments/layout` 风格，进度页用 JS 轮询 Mock JSON 端点模拟实时更新。

**Tech Stack:** Spring Boot + Thymeleaf + JUnit 5 + Mockito + AssertJ（与现有测试一致，纯单元测试，无 Spring 上下文）

## Global Constraints

- 源 schema 固定 `bxds`，目标 schema 固定 `tss`，迁移对象固定 `msg_flow_log_request` + `msg_flow_log_response` 两表
- 时间范围以 `response_time` 为准（bigint），用户只需输入响应时间范围
- 两表通过 `(trans_id, source_ip)` 配对，单边缺失丢弃
- `migrated_rows` 统计交易笔数（= response 行数 = request 行数）
- 页面布局沿用 `fragments/layout :: topbar` + `page-head` + `panel` 风格
- 状态用彩色 tag：COMPLETED/CANCELLED 绿色、FAILED/RUNNING 黄色、CREATED 默认
- 导航项 `active == 'migration'`，文本「数据迁移」
- 测试风格：纯单元测试（mock 依赖），不启动 Spring 上下文，与 `MessageFlowLogEntryControllerTest` 一致
- 构建命令：`mvn -q test`
- 提交信息风格：与现有 `git log` 一致，如 "Add message flow migration command page"

---

## 文件结构

| 文件 | 责任 | 阶段一状态 |
|---|---|---|
| `src/main/java/com/spdb/migration/MigrationCommandForm.java` | 表单 record | 新建（Mock 用） |
| `src/main/java/com/spdb/migration/MigrationCommandRow.java` | 指令列表行 record | 新建（Mock 用） |
| `src/main/java/com/spdb/migration/MigrationShardRow.java` | 分片详情 record | 新建（Mock 用） |
| `src/main/java/com/spdb/migration/MigrationProgressRow.java` | 进度聚合 record | 新建（Mock 用） |
| `src/main/java/com/spdb/migration/MigrationMockData.java` | 静态测试数据工厂 | 新建 |
| `src/main/java/com/spdb/web/MigrationController.java` | 控制器（Mock 数据） | 新建 |
| `src/main/resources/templates/migration/commands.html` | 指令列表 + 创建表单 | 新建 |
| `src/main/resources/templates/migration/progress.html` | 进度详情页 | 新建 |
| `src/main/resources/templates/fragments/layout.html` | 导航增加「数据迁移」 | 修改 |
| `src/test/java/com/spdb/web/MigrationControllerTest.java` | 控制器单元测试 | 新建 |
| `src/test/java/com/spdb/web/LayoutTemplateTest.java` | 导航断言补充 | 修改 |

阶段一不涉及 DDL、服务层、线程池、数据库。

---

### Task 1: 迁移领域 record 与静态测试数据工厂

**Files:**
- Create: `src/main/java/com/spdb/migration/MigrationCommandForm.java`
- Create: `src/main/java/com/spdb/migration/MigrationCommandRow.java`
- Create: `src/main/java/com/spdb/migration/MigrationShardRow.java`
- Create: `src/main/java/com/spdb/migration/MigrationProgressRow.java`
- Create: `src/main/java/com/spdb/migration/MigrationMockData.java`
- Test: `src/test/java/com/spdb/migration/MigrationMockDataTest.java`

**Interfaces:**
- Produces: `MigrationCommandForm(long timeFrom, long timeTo, long windowSeconds, int parallelism, String remark)`
- Produces: `MigrationCommandRow(long commandId, String status, long timeFrom, long timeTo, long windowSeconds, int parallelism, long totalShardCount, long completedShardCount, long failedShardCount, long migratedRows, long skippedRows, long droppedRows, String durationText, LocalDateTime createdTime, LocalDateTime startedTime, LocalDateTime endedTime, String errorMessage, String remark)`
- Produces: `MigrationShardRow(int shardSeq, long timeFrom, long timeTo, String status, long migratedRows, long skippedRows, long droppedRows, int attempts, long durationSeconds, String errorMessage)`
- Produces: `MigrationProgressRow(long commandId, String status, long timeFrom, long timeTo, long windowSeconds, int parallelism, long totalShardCount, long completedShardCount, long failedShardCount, long migratedRows, long skippedRows, long droppedRows, Long durationSeconds, LocalDateTime startedTime, LocalDateTime endedTime, String errorMessage, List<MigrationShardRow> shards)`
- Produces: `MigrationMockData.commandRows()` / `MigrationMockData.progress(long commandId)`

- [ ] **Step 1: 写失败测试 — Mock 数据工厂**

```java
package com.spdb.migration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationMockDataTest {

    @Test
    void commandRowsCoversAllStatuses() {
        List<MigrationCommandRow> rows = MigrationMockData.commandRows();

        assertThat(rows).isNotEmpty();
        assertThat(rows).extracting(MigrationCommandRow::status)
                .contains("CREATED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED");
    }

    @Test
    void progressForRunningCommandIncludesShards() {
        MigrationProgressRow progress = MigrationMockData.progress(2L);

        assertThat(progress.commandId()).isEqualTo(2L);
        assertThat(progress.status()).isEqualTo("RUNNING");
        assertThat(progress.totalShardCount()).isGreaterThan(0);
        assertThat(progress.completedShardCount()).isLessThanOrEqualTo(progress.totalShardCount());
        assertThat(progress.shards()).isNotEmpty();
        assertThat(progress.shards()).extracting(MigrationShardRow::status)
                .contains("COMPLETED", "RUNNING", "PENDING");
    }

    @Test
    void progressForUnknownCommandReturnsNull() {
        assertThat(MigrationMockData.progress(9999L)).isNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=MigrationMockDataTest test`
Expected: FAIL，编译错误（类不存在）

- [ ] **Step 3: 实现 MigrationCommandForm record**

```java
package com.spdb.migration;

public record MigrationCommandForm(
        long timeFrom,
        long timeTo,
        long windowSeconds,
        int parallelism,
        String remark
) {
    public static MigrationCommandForm empty() {
        return new MigrationCommandForm(0L, 0L, 3600L, 2, "");
    }
}
```

- [ ] **Step 4: 实现 MigrationCommandRow record**

```java
package com.spdb.migration;

import java.time.LocalDateTime;

public record MigrationCommandRow(
        long commandId,
        String status,
        long timeFrom,
        long timeTo,
        long windowSeconds,
        int parallelism,
        long totalShardCount,
        long completedShardCount,
        long failedShardCount,
        long migratedRows,
        long skippedRows,
        long droppedRows,
        String durationText,
        LocalDateTime createdTime,
        LocalDateTime startedTime,
        LocalDateTime endedTime,
        String errorMessage,
        String remark
) {}
```

- [ ] **Step 5: 实现 MigrationShardRow record**

```java
package com.spdb.migration;

public record MigrationShardRow(
        int shardSeq,
        long timeFrom,
        long timeTo,
        String status,
        long migratedRows,
        long skippedRows,
        long droppedRows,
        int attempts,
        long durationSeconds,
        String errorMessage
) {}
```

- [ ] **Step 6: 实现 MigrationProgressRow record**

```java
package com.spdb.migration;

import java.time.LocalDateTime;
import java.util.List;

public record MigrationProgressRow(
        long commandId,
        String status,
        long timeFrom,
        long timeTo,
        long windowSeconds,
        int parallelism,
        long totalShardCount,
        long completedShardCount,
        long failedShardCount,
        long migratedRows,
        long skippedRows,
        long droppedRows,
        Long durationSeconds,
        LocalDateTime startedTime,
        LocalDateTime endedTime,
        String errorMessage,
        List<MigrationShardRow> shards
) {}
```

- [ ] **Step 7: 实现 MigrationMockData 静态数据工厂**

```java
package com.spdb.migration;

import java.time.LocalDateTime;
import java.util.List;

public final class MigrationMockData {

    private MigrationMockData() {}

    public static List<MigrationCommandRow> commandRows() {
        return List.of(
                new MigrationCommandRow(1L, "CREATED", 1719100000L, 1719186400L, 3600L, 2,
                        24L, 0L, 0L, 0L, 0L, 0L, "-",
                        LocalDateTime.of(2026, 6, 23, 15, 0), null, null, null, "首批迁移"),
                new MigrationCommandRow(2L, "RUNNING", 1719100000L, 1719186400L, 3600L, 4,
                        24L, 15L, 1L, 89300000L, 1200L, 350L, "30分20秒",
                        LocalDateTime.of(2026, 6, 23, 14, 30), LocalDateTime.of(2026, 6, 23, 14, 30), null, null, "并行4"),
                new MigrationCommandRow(3L, "COMPLETED", 1719000000L, 1719086400L, 3600L, 4,
                        24L, 24L, 0L, 120000000L, 5000L, 800L, "1时12分",
                        LocalDateTime.of(2026, 6, 22, 10, 0), LocalDateTime.of(2026, 6, 22, 10, 0),
                        LocalDateTime.of(2026, 6, 22, 11, 12), null, "完成"),
                new MigrationCommandRow(4L, "FAILED", 1718900000L, 1718986400L, 3600L, 2,
                        24L, 18L, 6L, 45000000L, 200L, 120L, "45分",
                        LocalDateTime.of(2026, 6, 21, 9, 0), LocalDateTime.of(2026, 6, 21, 9, 0),
                        LocalDateTime.of(2026, 6, 21, 9, 45), "3个分片执行超时", "失败批次"),
                new MigrationCommandRow(5L, "CANCELLED", 1718800000L, 1718886400L, 3600L, 2,
                        24L, 10L, 0L, 20000000L, 100L, 50L, "20分",
                        LocalDateTime.of(2026, 6, 20, 8, 0), LocalDateTime.of(2026, 6, 20, 8, 0),
                        LocalDateTime.of(2026, 6, 20, 8, 20), null, "用户取消")
        );
    }

    public static MigrationProgressRow progress(long commandId) {
        if (commandId == 2L) {
            return runningProgress();
        }
        if (commandId == 3L) {
            return completedProgress();
        }
        if (commandId == 4L) {
            return failedProgress();
        }
        if (commandId == 5L) {
            return cancelledProgress();
        }
        if (commandId == 1L) {
            return createdProgress();
        }
        return null;
    }

    private static MigrationProgressRow createdProgress() {
        return new MigrationProgressRow(1L, "CREATED", 1719100000L, 1719186400L, 3600L, 2,
                24L, 0L, 0L, 0L, 0L, 0L, null,
                null, null, null, List.of());
    }

    private static MigrationProgressRow runningProgress() {
        return new MigrationProgressRow(2L, "RUNNING", 1719100000L, 1719186400L, 3600L, 4,
                24L, 15L, 1L, 89300000L, 1200L, 350L, 1820L,
                LocalDateTime.of(2026, 6, 23, 14, 30), null, null, sampleShards());
    }

    private static MigrationProgressRow completedProgress() {
        return new MigrationProgressRow(3L, "COMPLETED", 1719000000L, 1719086400L, 3600L, 4,
                24L, 24L, 0L, 120000000L, 5000L, 800L, 4320L,
                LocalDateTime.of(2026, 6, 22, 10, 0), LocalDateTime.of(2026, 6, 22, 11, 12), null,
                sampleShards());
    }

    private static MigrationProgressRow failedProgress() {
        return new MigrationProgressRow(4L, "FAILED", 1718900000L, 1718986400L, 3600L, 2,
                24L, 18L, 6L, 45000000L, 200L, 120L, 2700L,
                LocalDateTime.of(2026, 6, 21, 9, 0), LocalDateTime.of(2026, 6, 21, 9, 45),
                "3个分片执行超时", sampleShards());
    }

    private static MigrationProgressRow cancelledProgress() {
        return new MigrationProgressRow(5L, "CANCELLED", 1718800000L, 1718886400L, 3600L, 2,
                24L, 10L, 0L, 20000000L, 100L, 50L, 1200L,
                LocalDateTime.of(2026, 6, 20, 8, 0), LocalDateTime.of(2026, 6, 20, 8, 20),
                null, sampleShards());
    }

    private static List<MigrationShardRow> sampleShards() {
        return List.of(
                new MigrationShardRow(0, 1719100000L, 1719103600L, "COMPLETED", 5000000L, 50L, 12L, 1, 75L, null),
                new MigrationShardRow(1, 1719103600L, 1719107200L, "COMPLETED", 4800000L, 30L, 8L, 1, 70L, null),
                new MigrationShardRow(2, 1719107200L, 1719110800L, "RUNNING", 0L, 0L, 0L, 1, 45L, null),
                new MigrationShardRow(3, 1719110800L, 1719114400L, "PENDING", 0L, 0L, 0L, 0, 0L, null),
                new MigrationShardRow(4, 1719114400L, 1719118000L, "FAILED", 0L, 0L, 0L, 2, 30L, "执行超时"),
                new MigrationShardRow(5, 1719118000L, 1719121600L, "SKIPPED", 0L, 0L, 0L, 1, 1L, null)
        );
    }
}
```

- [ ] **Step 8: 运行测试确认通过**

Run: `mvn -q -Dtest=MigrationMockDataTest test`
Expected: PASS（3 个测试全绿）

- [ ] **Step 9: 提交**

```bash
git add src/main/java/com/spdb/migration/ src/test/java/com/spdb/migration/
git commit -m "Add message flow migration mock data"
```

---

### Task 2: MigrationController（Mock 数据驱动）

**Files:**
- Create: `src/main/java/com/spdb/web/MigrationController.java`
- Test: `src/test/java/com/spdb/web/MigrationControllerTest.java`

**Interfaces:**
- Consumes: `MigrationMockData.commandRows()`, `MigrationMockData.progress(long)`, `MigrationCommandForm.empty()`
- Produces: GET `/migration/commands` → view `migration/commands` + model `commands`, `form`, `active`
- Produces: POST `/migration/commands` → redirect `/migration/commands/2`（Mock 阶段固定跳到 RUNNING 示例）
- Produces: GET `/migration/commands/{id}` → view `migration/progress` + model `progress`, `active`
- Produces: GET `/migration/commands/{id}/progress` → `MigrationProgressRow`（JSON，Spring 自动序列化）
- Produces: POST `/migration/commands/{id}/cancel` → redirect `/migration/commands/{id}`
- Produces: POST `/migration/commands/{id}/resume` → redirect `/migration/commands/{id}`

- [ ] **Step 1: 写失败测试 — 控制器**

```java
package com.spdb.web;

import com.spdb.migration.MigrationCommandForm;
import com.spdb.migration.MigrationMockData;
import com.spdb.migration.MigrationProgressRow;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationControllerTest {

    @Test
    void commandsPageAddsRowsAndFormToModel() {
        MigrationController controller = new MigrationController();
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.commandsPage(model);

        assertThat(view).isEqualTo("migration/commands");
        assertThat(model.getAttribute("active")).isEqualTo("migration");
        assertThat(model.getAttribute("commands")).isEqualTo(MigrationMockData.commandRows());
        assertThat(model.getAttribute("form")).isEqualTo(MigrationCommandForm.empty());
    }

    @Test
    void createCommandRedirectsToRunningExample() {
        MigrationController controller = new MigrationController();

        String view = controller.createCommand(MigrationCommandForm.empty());

        assertThat(view).isEqualTo("redirect:/migration/commands/2");
    }

    @Test
    void progressPageAddsProgressToModel() {
        MigrationController controller = new MigrationController();
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.progressPage(2L, model);

        assertThat(view).isEqualTo("migration/progress");
        assertThat(model.getAttribute("active")).isEqualTo("migration");
        assertThat(model.getAttribute("progress")).isEqualTo(MigrationMockData.progress(2L));
    }

    @Test
    void progressJsonReturnsRunningProgress() {
        MigrationController controller = new MigrationController();

        MigrationProgressRow result = controller.progressJson(2L);

        assertThat(result).isEqualTo(MigrationMockData.progress(2L));
        assertThat(result.status()).isEqualTo("RUNNING");
    }

    @Test
    void cancelRedirectsToProgressPage() {
        MigrationController controller = new MigrationController();

        String view = controller.cancel(2L);

        assertThat(view).isEqualTo("redirect:/migration/commands/2");
    }

    @Test
    void resumeRedirectsToProgressPage() {
        MigrationController controller = new MigrationController();

        String view = controller.resume(5L);

        assertThat(view).isEqualTo("redirect:/migration/commands/5");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=MigrationControllerTest test`
Expected: FAIL，编译错误（类不存在）

- [ ] **Step 3: 实现 MigrationController**

```java
package com.spdb.web;

import com.spdb.migration.MigrationCommandForm;
import com.spdb.migration.MigrationMockData;
import com.spdb.migration.MigrationProgressRow;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class MigrationController {

    @GetMapping("/migration/commands")
    public String commandsPage(Model model) {
        model.addAttribute("active", "migration");
        model.addAttribute("commands", MigrationMockData.commandRows());
        model.addAttribute("form", MigrationCommandForm.empty());
        return "migration/commands";
    }

    @PostMapping("/migration/commands")
    public String createCommand(@ModelAttribute MigrationCommandForm form) {
        return "redirect:/migration/commands/2";
    }

    @GetMapping("/migration/commands/{id}")
    public String progressPage(@PathVariable long id, Model model) {
        model.addAttribute("active", "migration");
        model.addAttribute("progress", MigrationMockData.progress(id));
        return "migration/progress";
    }

    @GetMapping("/migration/commands/{id}/progress")
    @ResponseBody
    public MigrationProgressRow progressJson(@PathVariable long id) {
        return MigrationMockData.progress(id);
    }

    @PostMapping("/migration/commands/{id}/cancel")
    public String cancel(@PathVariable long id) {
        return "redirect:/migration/commands/" + id;
    }

    @PostMapping("/migration/commands/{id}/resume")
    public String resume(@PathVariable long id) {
        return "redirect:/migration/commands/" + id;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=MigrationControllerTest test`
Expected: PASS（6 个测试全绿）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/spdb/web/MigrationController.java src/test/java/com/spdb/web/MigrationControllerTest.java
git commit -m "Add message flow migration controller with mock data"
```

---

### Task 3: 导航接入「数据迁移」菜单项

**Files:**
- Modify: `src/main/resources/templates/fragments/layout.html`（在 nav 内「报文录入」之后增加一项）
- Test: `src/test/java/com/spdb/web/LayoutTemplateTest.java`（补充断言）

**Interfaces:**
- Consumes: 现有 `topbar(active)` fragment
- Produces: nav 增加 `<a th:classappend="${active == 'migration'} ? 'active'" href="/migration/commands">数据迁移</a>`

- [ ] **Step 1: 写失败测试 — 补充 LayoutTemplateTest 断言**

在 `LayoutTemplateTest` 类中 `topbarKeepsFlatNavigationLinks` 方法末尾（`assertThat(html).contains("录制配置");` 之后）增加：

```java
        assertThat(html).contains("数据迁移");
        assertThat(html).contains("/migration/commands");
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=LayoutTemplateTest test`
Expected: FAIL，`期望包含 "数据迁移" 但未包含`

- [ ] **Step 3: 修改 layout.html 增加导航项**

在 `fragments/layout.html` 第 16 行（报文录入的 `<a>` 之后）插入新的一行：

```html
    <a th:classappend="${active == 'migration'} ? 'active'" href="/migration/commands">数据迁移</a>
```

修改后该区域为：
```html
    <a th:classappend="${active == 'message-flow-logs'} ? 'active'" href="/messages/flow-logs">报文查询</a>
    <a th:classappend="${active == 'message-flow-log-entry'} ? 'active'" href="/messages/flow-logs/new">报文录入</a>
    <a th:classappend="${active == 'migration'} ? 'active'" href="/migration/commands">数据迁移</a>
    <a th:classappend="${active == 'recording'} ? 'active'" href="/config/recording">录制配置</a>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=LayoutTemplateTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/templates/fragments/layout.html src/test/java/com/spdb/web/LayoutTemplateTest.java
git commit -m "Add migration nav entry"
```

---

### Task 4: 指令列表页模板 commands.html

**Files:**
- Create: `src/main/resources/templates/migration/commands.html`
- Test: `src/test/java/com/spdb/web/MigrationCommandsTemplateTest.java`

**Interfaces:**
- Consumes: model `commands` (List<MigrationCommandRow>), `form` (MigrationCommandForm), `active` (String)
- Produces: 包含创建表单 + 指令列表表格 + 操作链接（查看进度/取消/续传）

- [ ] **Step 1: 写失败测试 — 模板存在性与关键内容断言**

```java
package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationCommandsTemplateTest {

    @Test
    void commandsTemplateContainsFormAndTable() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/migration/commands.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("fragments/layout :: topbar");
        assertThat(html).contains("创建迁移指令");
        assertThat(html).contains("name=\"timeFrom\"");
        assertThat(html).contains("name=\"timeTo\"");
        assertThat(html).contains("name=\"windowSeconds\"");
        assertThat(html).contains("name=\"parallelism\"");
        assertThat(html).contains("action=\"/migration/commands\"");
        assertThat(html).contains("/migration/commands/");
        assertThat(html).contains("已迁移交易笔数");
        assertThat(html).contains("丢弃数");
        assertThat(html).contains("跳过数");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=MigrationCommandsTemplateTest test`
Expected: FAIL，资源不存在

- [ ] **Step 3: 实现 commands.html 模板**

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>数据迁移</title>
  <link rel="stylesheet" href="/css/app.css">
</head>
<body>
<main class="shell layout-frame">
  <div th:replace="~{fragments/layout :: topbar(${active})}"></div>
  <div class="page-head">
    <div>
      <div class="eyebrow">Message Flow Migration</div>
      <h1>数据迁移</h1>
      <p class="muted">将 bxds schema 的报文日志配对迁移至 tss schema，支持分片并行与进度跟踪。</p>
    </div>
  </div>

  <form class="panel form-grid" method="post" action="/migration/commands" th:object="${form}">
    <div class="section-title"><div><div class="eyebrow">Source</div><h2>创建迁移指令</h2></div></div>
    <div class="filter-grid">
      <div><label>源 schema</label><input value="bxds" readonly></div>
      <div><label>目标 schema</label><input value="tss" readonly></div>
      <div><label>响应时间起点</label><input name="timeFrom" type="number" th:value="*{timeFrom}" placeholder="1719100000" required></div>
      <div><label>响应时间终点</label><input name="timeTo" type="number" th:value="*{timeTo}" placeholder="1719186400" required></div>
      <div><label>窗口大小(秒)</label><input name="windowSeconds" type="number" th:value="*{windowSeconds}" min="60" max="86400" required></div>
      <div><label>并行度</label><input name="parallelism" type="number" th:value="*{parallelism}" min="1" max="8" required></div>
      <div class="span-2"><label>备注</label><input name="remark" th:value="*{remark}"></div>
      <div class="actions"><button class="btn primary" type="submit">创建迁移</button></div>
    </div>
  </form>

  <section class="panel table-wrap">
    <div class="section-title"><div><div class="eyebrow">History</div><h2>迁移指令列表</h2></div></div>
    <table class="command-table">
      <thead>
      <tr>
        <th>指令ID</th><th>状态</th><th>进度</th><th>已迁移交易笔数</th>
        <th>丢弃数</th><th>跳过数</th><th>耗时</th><th>创建时间</th><th>操作</th>
      </tr>
      </thead>
      <tbody>
      <tr th:each="row : ${commands}">
        <td th:text="${row.commandId}"></td>
        <td><span class="tag" th:classappend="${row.status == 'COMPLETED' or row.status == 'CANCELLED'} ? 'green' : (${row.status == 'FAILED' or row.status == 'RUNNING'} ? 'yellow' : '')" th:text="${row.status}"></span></td>
        <td><span th:text="${row.completedShardCount} + '/' + ${row.totalShardCount}"></span></td>
        <td th:text="${row.migratedRows}"></td>
        <td th:text="${row.droppedRows}"></td>
        <td th:text="${row.skippedRows}"></td>
        <td th:text="${row.durationText}"></td>
        <td th:text="${row.createdTime}"></td>
        <td>
          <a class="btn" th:href="@{/migration/commands/{id}(id=${row.commandId})}">进度</a>
        </td>
      </tr>
      </tbody>
    </table>
  </section>
</main>
</body>
</html>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=MigrationCommandsTemplateTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/templates/migration/commands.html src/test/java/com/spdb/web/MigrationCommandsTemplateTest.java
git commit -m "Add message flow migration command page"
```

---

### Task 5: 进度详情页模板 progress.html + JS 轮询

**Files:**
- Create: `src/main/resources/templates/migration/progress.html`
- Test: `src/test/java/com/spdb/web/MigrationProgressTemplateTest.java`

**Interfaces:**
- Consumes: model `progress` (MigrationProgressRow), `active` (String)
- Produces: 主任务概览卡片 + 进度条 + 分片明细表 + JS 轮询 `/migration/commands/{id}/progress`

- [ ] **Step 1: 写失败测试 — 模板关键内容断言**

```java
package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationProgressTemplateTest {

    @Test
    void progressTemplateContainsOverviewAndShardTable() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/migration/progress.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("fragments/layout :: topbar");
        assertThat(html).contains("迁移进度");
        assertThat(html).contains("progress-bar");
        assertThat(html).contains("已迁移交易笔数");
        assertThat(html).contains("丢弃交易数");
        assertThat(html).contains("失败分片数");
        assertThat(html).contains("分片明细");
        assertThat(html).contains("/progress");
        assertThat(html).contains("setTimeout(pollProgress");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=MigrationProgressTemplateTest test`
Expected: FAIL，资源不存在

- [ ] **Step 3: 实现 progress.html 模板**

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>迁移进度</title>
  <link rel="stylesheet" href="/css/app.css">
</head>
<body>
<main class="shell layout-frame">
  <div th:replace="~{fragments/layout :: topbar(${active})}"></div>
  <div class="page-head">
    <div>
      <div class="eyebrow">Migration Progress</div>
      <h1>迁移进度</h1>
      <p class="muted" th:if="${progress}" th:text="'指令 #' + ${progress.commandId} + ' / 状态：' + ${progress.status}"></p>
    </div>
    <div class="actions" th:if="${progress}">
      <form th:if="${progress.status == 'RUNNING'}" method="post" th:action="@{/migration/commands/{id}/cancel(id=${progress.commandId})}" style="display:inline">
        <button class="btn yellow" type="submit">取消</button>
      </form>
      <form th:if="${progress.status == 'CANCELLED' or progress.status == 'FAILED'}" method="post" th:action="@{/migration/commands/{id}/resume(id=${progress.commandId})}" style="display:inline">
        <button class="btn primary" type="submit">续传</button>
      </form>
      <a class="btn" href="/migration/commands">返回列表</a>
    </div>
  </div>

  <section class="panel" th:unless="${progress}">
    <p class="muted">未找到该迁移指令。</p>
  </section>

  <section th:if="${progress}">
    <div class="cards">
      <div class="card"><div class="label">状态</div><div class="value"><span class="tag" th:classappend="${progress.status == 'COMPLETED' or progress.status == 'CANCELLED'} ? 'green' : (${progress.status == 'FAILED' or progress.status == 'RUNNING'} ? 'yellow' : '')" th:text="${progress.status}"></span></div></div>
      <div class="card"><div class="label">进度</div><div class="value" th:text="${progress.completedShardCount} + '/' + ${progress.totalShardCount}"></div></div>
      <div class="card"><div class="label">已迁移交易笔数</div><div class="value" th:text="${progress.migratedRows}"></div></div>
      <div class="card"><div class="label">丢弃交易数</div><div class="value" th:text="${progress.droppedRows}"></div></div>
      <div class="card"><div class="label">跳过数</div><div class="value" th:text="${progress.skippedRows}"></div></div>
      <div class="card"><div class="label">失败分片数</div><div class="value" th:text="${progress.failedShardCount}"></div></div>
    </div>

    <div class="panel" style="margin-top:16px">
      <div class="section-title"><div><div class="eyebrow">Progress</div><h2>完成度</h2></div></div>
      <div class="progress-bar" style="background:rgba(97,128,165,.18);border-radius:6px;overflow:hidden;height:24px">
        <div id="progress-fill" style="height:100%;background:linear-gradient(90deg,#38bdf8,#4ade80);transition:width .6s" th:styleappend="${progress.totalShardCount == 0} ? 'width:0%' : 'width:' + ${progress.completedShardCount * 100 / progress.totalShardCount} + '%'"></div>
      </div>
    </div>

    <section class="panel table-wrap" style="margin-top:16px">
      <div class="section-title"><div><div class="eyebrow">Shards</div><h2>分片明细</h2></div></div>
      <table class="command-table">
        <thead>
        <tr>
          <th>序号</th><th>响应时间窗口</th><th>状态</th><th>迁移笔数</th>
          <th>丢弃数</th><th>跳过数</th><th>尝试次数</th><th>耗时(秒)</th><th>错误信息</th>
        </tr>
        </thead>
        <tbody>
        <tr th:each="shard : ${progress.shards}">
          <td th:text="${shard.shardSeq}"></td>
          <td th:text="${shard.timeFrom} + ' → ' + ${shard.timeTo}"></td>
          <td><span class="tag" th:classappend="${shard.status == 'COMPLETED' or shard.status == 'SKIPPED'} ? 'green' : (${shard.status == 'FAILED' or shard.status == 'RUNNING'} ? 'yellow' : '')" th:text="${shard.status}"></span></td>
          <td th:text="${shard.migratedRows}"></td>
          <td th:text="${shard.droppedRows}"></td>
          <td th:text="${shard.skippedRows}"></td>
          <td th:text="${shard.attempts}"></td>
          <td th:text="${shard.durationSeconds}"></td>
          <td th:text="${shard.errorMessage}"></td>
        </tr>
        </tbody>
      </table>
    </section>
  </section>
</main>
<script th:if="${progress and (progress.status == 'RUNNING' or progress.status == 'CANCEL_REQUESTED')}" th:inline="javascript">
  /*<![CDATA[*/
  const commandId = /*[[${progress.commandId}]]*/ 0;
  let timer = null;
  function pollProgress() {
    fetch('/migration/commands/' + commandId + '/progress')
      .then(r => r.json())
      .then(data => {
        if (!data) { return; }
        if (data.status !== 'RUNNING' && data.status !== 'CANCEL_REQUESTED') {
          location.reload();
          return;
        }
        timer = setTimeout(pollProgress, 3000);
      })
      .catch(() => { timer = setTimeout(pollProgress, 5000); });
  }
  timer = setTimeout(pollProgress, 3000);
  /*]]>*/
</script>
</body>
</html>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=MigrationProgressTemplateTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/templates/migration/progress.html src/test/java/com/spdb/web/MigrationProgressTemplateTest.java
git commit -m "Add message flow migration progress page"
```

---

### Task 6: 全量回归与阶段一收尾

**Files:**
- 无新建文件，运行全量测试验证阶段一无回归

- [ ] **Step 1: 运行全量测试**

Run: `mvn -q test`
Expected: 全部 PASS（含原有测试 + 阶段一新增测试）

- [ ] **Step 2: 若有失败，修复后重跑直至全绿**

- [ ] **Step 3: 阶段一完成提交（如尚有未提交改动）**

```bash
git status
# 若有未提交改动则提交，否则跳过
```

---

## 阶段二预告（本计划不实现，后续另立计划）

1. DDL：`db/ddl.sql` 追加 `ana_migration_command` / `ana_migration_shard` 表
2. `MigrationCommandService` / `MigrationShardRunner` / `MigrationBatchRunner` / `MigrationAsyncExecutor`
3. `migrationTaskExecutor` 线程池配置
4. 控制器替换 Mock 为真实数据库 + 异步派发
5. `db/seed.sql` 补充少量测试数据

## Self-Review

**1. Spec coverage：** 阶段一覆盖设计文档「实现顺序 → 阶段一」全部 5 项（两个模板、控制器 Mock、导航、JS 轮询、各状态静态数据）。阶段二明确标注另立计划，符合范围控制。

**2. Placeholder scan：** 无 TBD/TODO，每步含完整代码与命令。

**3. Type consistency：** record 字段名在各 Task 间一致（commandId/status/timeFrom/timeTo/windowSeconds/parallelism/totalShardCount/completedShardCount/failedShardCount/migratedRows/skippedRows/droppedRows/durationText/createdTime/startedTime/endedTime/errorMessage/remark；shardSeq/timeFrom/timeTo/status/migratedRows/skippedRows/droppedRows/attempts/durationSeconds/errorMessage）。控制器方法名与测试断言一致。
