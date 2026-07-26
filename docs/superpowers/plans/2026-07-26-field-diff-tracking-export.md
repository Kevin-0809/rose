# 字段级差异问题跟踪文本导出实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为字段级差异页面提供按批次的独立问题跟踪 TXT 导出，并将归一结果写入字段级导出流水表。

**Architecture:** 新增字段级导出服务，沿用交易级的流式读取、事务落库、临时文件和提交后输出模式；字段归一及文本列值严格使用确认的 SQL 口径。控制器和模板仅处理批次校验及下载入口。

**Tech Stack:** Java 17、Spring MVC/JDBC/Transaction、PostgreSQL/GaussDB、JUnit 5、AssertJ、H2 PostgreSQL mode。

---

### Task 1: 字段级导出流水表和发布脚本

**Files:** `db/ddl.sql`、`db/manual-create-ana-field-diff-tracking-export.sql`、`src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] 写失败测试，要求 `ana_field_diff_tracking_export` 包含导出时间、源批次、业务日期、行号、服务码、交易元数据、四种字段路径、映射状态、双方字段值、负责人、样例流水号、问题跟踪字段与全部整改字段，以及源批次字段组合和导出时间索引。
- [ ] Run: `mvn "-Dtest=DatabaseScriptLayoutTest#ddlContainsFieldDiffTrackingExportTable" test`
  Expected: FAIL，缺少字段级流水表。
- [ ] 在 `db/ddl.sql` 添加字段级流水表、中文注释和索引；在手工脚本添加相同的可重复执行 GaussDB DDL。
- [ ] Run: `mvn "-Dtest=DatabaseScriptLayoutTest#ddlContainsFieldDiffTrackingExportTable" test`
  Expected: PASS。

### Task 2: 字段级流式导出服务

**Files:** `src/main/java/com/spdb/sample/FieldDiffTrackingExportRow.java`、`src/main/java/com/spdb/sample/FieldDiffTrackingExportService.java`、`src/test/java/com/spdb/sample/FieldDiffTrackingExportServiceTest.java`

- [ ] 写失败 H2 集成测试：同批次按 `service_code + soap_field_name` 随机保留一行；目录按服务码取最小 `catalog_id`；字段名拼接 SOP、SOAP、BizJSON、中文字段；输出和落库均为 19 列 SQL 口径；空批次、特殊字符、回滚无输出、提交后输出和 `fetchSize` 均受覆盖。
- [ ] Run: `mvn "-Dtest=FieldDiffTrackingExportServiceTest" test`
  Expected: FAIL，服务类不存在。
- [ ] 实现记录类型和服务：在 `TransactionTemplate` 内使用前向只读 `PreparedStatement`、`fetchSize=1000`、`row_number() over (partition by service_code, soap_field_name order by random())`，写入临时 UTF-8 文件与字段级流水；提交后复制至调用流并删除临时文件。
- [ ] Run: `mvn "-Dtest=FieldDiffTrackingExportServiceTest" test`
  Expected: PASS。

### Task 3: 控制器和页面入口

**Files:** `src/main/java/com/spdb/web/SampleController.java`、`src/main/resources/templates/samples/field-diffs.html`、`src/test/java/com/spdb/web/SampleControllerExportTest.java`、`src/test/java/com/spdb/web/SampleDetailTemplateTest.java`

- [ ] 写失败测试：空批次拒绝调用字段级服务；有效批次返回 `text/plain;charset=UTF-8` 和 `fielddiff_hf_yyyyMMddHHmmss.txt`；模板包含字段级跟踪导出按钮及批次校验标识。
- [ ] Run: `mvn "-Dtest=SampleControllerExportTest,SampleDetailTemplateTest" test`
  Expected: FAIL，字段级端点和模板入口不存在。
- [ ] 实现 `GET /samples/field-diffs/tracking-export`，按钮和前端批次必填校验；保持既有字段级 ZIP 导出不变。
- [ ] Run: `mvn "-Dtest=SampleControllerExportTest,SampleDetailTemplateTest" test`
  Expected: PASS。

### Task 4: 验证与提交

- [ ] Run: `mvn "-Dtest=FieldDiffTrackingExportServiceTest,SampleControllerExportTest,SampleDetailTemplateTest" test`
  Expected: `Failures: 0, Errors: 0`。
- [ ] Run: `git diff --check`
  Expected: 无空白错误。
- [ ] 提交字段级 DDL、服务、控制器、模板和测试：`git commit -m "feat: export field diff tracking text"`。
