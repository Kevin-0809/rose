# 交易码报文迁移实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持多个交易码并行迁移最近五天内的三类完整报文对，每一类最多 N 笔。

**Architecture:** 为现有迁移命令增加 `TRAN_CODE` 类型。一个交易码对应一个分片；批处理器沿用现有并发、取消和续传机制，分片运行器从主库读取服务码，并从源库读取成对请求/响应后事务性写入目标库。

**Tech Stack:** Java 17、Spring Boot、Spring JDBC、Thymeleaf、PostgreSQL/openGauss、JUnit 5、AssertJ。

---

### Task 1: 扩展持久化模型

**Files:**
- Modify: `db/ddl.sql`
- Modify: `src/main/java/com/spdb/migration/MigrationCommandRow.java`
- Modify: `src/main/java/com/spdb/migration/MigrationShardRow.java`
- Modify: `src/main/java/com/spdb/migration/MigrationProgressRow.java`
- Test: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] **Step 1: 写入失败的 DDL 测试**

断言 DDL 包含：

```java
assertThat(ddl).contains("tran_codes text");
assertThat(ddl).contains("sample_size integer");
assertThat(ddl).contains("tran_code varchar(32)");
assertThat(ddl).contains("'TIME_RANGE','SQL','TRAN_CODE'");
```

- [ ] **Step 2: 运行失败测试**

运行 `mvn -Dtest=DatabaseScriptLayoutTest test`，预期缺少新字段或新命令类型而失败。

- [ ] **Step 3: 最小实现**

在 `ana_migration_command` 及其兼容性 `alter table` 中加入 `tran_codes text`、`sample_size integer`，将类型约束扩展为：

```sql
check (command_type in ('TIME_RANGE','SQL','TRAN_CODE'))
```

在分片表及兼容性 `alter table` 中加入 `tran_code varchar(32)`。扩展 Java record 和映射方法读取这些字段，并同步已有构造点。

- [ ] **Step 4: 验证通过并提交**

运行 `mvn -Dtest=DatabaseScriptLayoutTest test`；通过后提交此任务涉及的文件。

### Task 2: 创建交易码命令

**Files:**
- Create: `src/main/java/com/spdb/migration/MigrationTranCodeCommandForm.java`
- Modify: `src/main/java/com/spdb/migration/MigrationCommandService.java`
- Test: `src/test/java/com/spdb/migration/MigrationCommandServiceTest.java`

- [ ] **Step 1: 写入失败的服务测试**

提交 `"A001, B002, A001"`、N=3、并行度=2，断言生成 `TRAN_CODE` 命令，保存的交易码为 `A001,B002`，N 为 3，并按顺序创建两个带交易码的分片。分别测试空交易码、N 小于 1、并行度大于 8 被拒绝。

- [ ] **Step 2: 运行失败测试**

运行 `mvn -Dtest=MigrationCommandServiceTest test`，预期没有表单或创建方法而失败。

- [ ] **Step 3: 最小实现**

创建：

```java
public record MigrationTranCodeCommandForm(String tranCodes, int sampleSize, int parallelism, String remark) {
    public static MigrationTranCodeCommandForm empty() {
        return new MigrationTranCodeCommandForm("", 1, 2, "");
    }
}
```

`createTranCodeCommand` 以 `split(",")`、`trim`、`LinkedHashSet` 解析交易码，在同一事务中插入命令和每个 `PENDING` 分片，提交后调用已有 `launch(commandId)`。

- [ ] **Step 4: 验证通过并提交**

运行 `mvn -Dtest=MigrationCommandServiceTest test`；通过后提交此任务涉及的文件。

### Task 3: 按交易码迁移完整请求/响应对

**Files:**
- Modify: `src/main/java/com/spdb/migration/MigrationShardRunner.java`
- Test: `src/test/java/com/spdb/migration/MigrationShardRunnerTest.java`

- [ ] **Step 1: 写入失败的分片测试**

在主库测试数据源建立 `tp_online_service_in(tran_code, esf_service_code)`，并以固定时钟写入当天、前一天和五天外的源数据。调用：

```java
MigrationShardResult result = runner.runTranCode(7L, "A001", 2);
assertThat(result.migratedRows()).isEqualTo(6L);
assertThat(targetCount("msg_flow_log_request")).isEqualTo(6L);
assertThat(targetCount("msg_flow_log_response")).isEqualTo(6L);
```

三种类型均验证当天优先、当天不足才查前一天、每种最多两笔、五天外不迁移。另测无服务码、无完整配对时返回全零。

- [ ] **Step 2: 运行失败测试**

运行 `mvn -Dtest=MigrationShardRunnerTest test`，预期没有 `runTranCode` 而失败。

- [ ] **Step 3: 最小实现**

新增可替换的 `Clock`，使用 `ZoneId.of("Asia/Shanghai")`。`runTranCode(long shardId, String tranCode, int sampleSize)` 从目标库读取服务码并去点，分别拼接 `&bzjson`、`&sop`、`&soap`；从当天到前四天逐日查询源库，按 `response_time desc, source_ip, trans_id` 选取完整报文对直到当前类型达到 N。候选沿用既有 `flushBatch`，从而在一个目标事务内同时插入请求表和响应表。

- [ ] **Step 4: 验证通过并提交**

运行 `mvn -Dtest=MigrationShardRunnerTest test`；通过后提交此任务涉及的文件。

### Task 4: 接入调度、跳过和续传

**Files:**
- Modify: `src/main/java/com/spdb/migration/MigrationBatchRunner.java`
- Modify: `src/main/java/com/spdb/migration/MigrationCommandService.java`
- Test: `src/test/java/com/spdb/migration/MigrationBatchRunnerTest.java`

- [ ] **Step 1: 写入失败的调度测试**

创建两个交易码分片，断言 `TRAN_CODE` 命令调用 `runTranCode(shardId, tranCode, sampleSize)`；全零结果将分片标记 `SKIPPED`，失败分片续传时会重跑而 `SKIPPED` 不会重跑。

- [ ] **Step 2: 运行失败测试**

运行 `mvn -Dtest=MigrationBatchRunnerTest test`，预期未识别 `TRAN_CODE` 或未写入 `SKIPPED` 而失败。

- [ ] **Step 3: 最小实现**

在 `runShard` 新增：

```java
if ("TRAN_CODE".equals(command.commandType())) {
    result = shardRunner.runTranCode(shardId, shard.tranCode(), command.sampleSize());
}
```

增加 `markShardSkipped`；仅当迁移、跳过、丢弃均为零时调用。`runnableShardIds` 继续只选择 `PENDING`、`FAILED`，使跳过项天然不参加续传。

- [ ] **Step 4: 验证通过并提交**

运行 `mvn -Dtest=MigrationBatchRunnerTest test`；通过后提交此任务涉及的文件。

### Task 5: 页面、控制器和导航

**Files:**
- Modify: `src/main/java/com/spdb/web/MigrationController.java`
- Create: `src/main/resources/templates/migration/tran-code-commands.html`
- Modify: `src/main/resources/templates/migration/progress.html`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Test: `src/test/java/com/spdb/web/MigrationControllerTest.java`
- Test: `src/test/java/com/spdb/web/MigrationCommandsTemplateTest.java`
- Test: `src/test/java/com/spdb/web/LayoutTemplateTest.java`

- [ ] **Step 1: 写入失败的 Web 测试**

断言 GET/POST `/migration/tran-code-commands` 使用新模板和表单；模板包含 `tranCodes`、`sampleSize`、`parallelism` 和表单 action；导航包含交易码迁移入口，进度页能显示交易码和 N。

- [ ] **Step 2: 运行失败测试**

运行 `mvn -Dtest=MigrationControllerTest,MigrationCommandsTemplateTest,LayoutTemplateTest test`，预期路由和模板不存在而失败。

- [ ] **Step 3: 最小实现**

控制器新增 GET/POST 路由，调用 `searchByType(..., "TRAN_CODE")` 和 `createTranCodeCommand`。新页面使用逗号分隔交易码、全局 N、并行度和备注输入；导航新增“交易码迁移”。进度页在命令类型为 `TRAN_CODE` 时显示交易码集合、N 和每个分片交易码，其余命令保留原有时间窗口。

- [ ] **Step 4: 验证通过并提交**

运行 `mvn -Dtest=MigrationControllerTest,MigrationCommandsTemplateTest,LayoutTemplateTest test`；通过后提交此任务涉及的文件。

### Task 6: 全量验证

**Files:**
- Modify: 上述实现与测试文件

- [ ] **Step 1: 运行迁移功能回归**

运行：

```powershell
mvn -Dtest=DatabaseScriptLayoutTest,MigrationCommandServiceTest,MigrationShardRunnerTest,MigrationBatchRunnerTest,MigrationControllerTest,MigrationCommandsTemplateTest,LayoutTemplateTest test
```

- [ ] **Step 2: 运行全量测试并审查**

运行 `mvn test`、`git diff --check` 和 `git status --short`。仅暂存本功能涉及的文件，保留现有无关改动不变，然后提交：

```powershell
git commit -m "feat: add tran code migration"
```
