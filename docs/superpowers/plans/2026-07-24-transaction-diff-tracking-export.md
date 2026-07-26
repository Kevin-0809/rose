# 交易级差异问题跟踪文本导出实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从交易级差异页面按源批次导出归一后的 `!^` 分隔 TXT，并将每条输出行 1:1 保存到带整改预留字段的流水表。

**Architecture:** 新增 `TransactionDiffTrackingExportService`，在一个事务中读取 `ana_tran_diff_result`、按服务码和双方响应码归一、写入 `ana_tran_diff_tracking_export`，并在事务提交后输出完整文本。控制器负责批次校验和下载响应；既有 ZIP 导出保持不变。

**Tech Stack:** Java 17、Spring MVC、Spring JDBC、Spring Transaction、PostgreSQL、JUnit 5、AssertJ、H2 PostgreSQL mode。

---

## 文件结构

- Create: `src/main/java/com/spdb/sample/TransactionDiffTrackingExportRow.java`
- Create: `src/main/java/com/spdb/sample/TransactionDiffTrackingExportService.java`
- Create: `src/test/java/com/spdb/sample/TransactionDiffTrackingExportServiceTest.java`
- Modify: `db/ddl.sql`
- Modify: `src/main/java/com/spdb/web/SampleController.java`
- Modify: `src/main/resources/templates/samples/transaction-diffs.html`
- Modify: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`
- Modify: `src/test/java/com/spdb/web/SampleControllerExportTest.java`
- Modify: `src/test/java/com/spdb/web/SampleDetailTemplateTest.java`

### Task 1: 新增导出流水表

**Files:** `db/ddl.sql`、`src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] 写入失败测试，断言 DDL 包含 `ana_tran_diff_tracking_export`、`source_batch_id varchar(64) not null`、`export_timestamp timestamp not null`、`business_date varchar(8) not null`、全部整改字段和两个索引。
- [ ] Run: `mvn "-Dtest=DatabaseScriptLayoutTest#ddlContainsTransactionDiffTrackingExportTableAndRemediationColumns" test`
  Expected: FAIL，缺少新表。
- [ ] 在 `ana_tran_diff_result` 后增加表：`export_id` 主键、导出时间、源批次、业务日期、行号、服务码、528/CCBS 码及描述、交易码/名称/领域、负责人、流水号；整改字段为 `problem_level`、`registration_date`、`field_name`、`problem_description`、`problem_type`、`preliminary_analysis`、`final_solution`、`resolution_date`、`coordination_required`、`resolver`、`defect_fix_date`，均允许空值；添加中文注释。
- [ ] 增加 `idx_ana_tran_diff_tracking_export_source(source_batch_id, service_code, orig_error_code, dest_error_code)` 和 `idx_ana_tran_diff_tracking_export_time(export_timestamp desc)`。
- [ ] Run: `mvn "-Dtest=DatabaseScriptLayoutTest#ddlContainsTransactionDiffTrackingExportTableAndRemediationColumns" test`
  Expected: PASS。
- [ ] Commit: `git add db/ddl.sql src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java` 后执行 `git commit -m "feat: add tracking export ledger schema"`。

### Task 2: 归一查询、文本生成和事务落库

**Files:** `TransactionDiffTrackingExportRow.java`、`TransactionDiffTrackingExportService.java`、`TransactionDiffTrackingExportServiceTest.java`

- [ ] 用 H2 创建源表、交易目录和流水表。写失败测试：同一批次插入两条相同 `(service_code, orig_error_code, dest_error_code)`、不同流水号的行和一个不同批次干扰行；调用 `service.export("BATCH_01", output)` 后只输出和落库一行，使用第一条流水号和其 `owner`，且不含干扰批次。
- [ ] 在测试中断言文本以 `业务日期!^序号!^批次!^交易码!` 开头；数据行按 `!^` 分割为 18 列；整改列为空。另插入含 `!^`、CR/LF 的响应描述，断言输出清理后仍保持 18 列。
- [ ] Run: `mvn "-Dtest=TransactionDiffTrackingExportServiceTest" test`
  Expected: FAIL，服务不存在。
- [ ] 定义记录：`TransactionDiffTrackingExportRow(sourceBatchId, serviceCode, origErrorCode, destErrorCode, tranCode, tranName, moduleName, origErrorDesc, destErrorDesc, transactionOwner, tranSeqNo)`。
- [ ] 实现 `export(String sourceBatchId, OutputStream outputStream)`：空批次抛出 `IllegalArgumentException("请选择批次后导出")`；查询唯一条件为 `d.batch_id = :batchId`；使用 `row_number() over (partition by d.service_code, d.orig_error_code, d.dest_error_code order by d.result_id)` 并取首行；左连接 `ana_tran_catalog` 的交易码和服务码；按服务码、双方响应码排序。
- [ ] 在 `TransactionTemplate.executeWithoutResult` 中生成一次当前时间，业务日期格式为 `yyyyMMdd`；每条归一记录插入流水表，并写入缓冲文本。事务完成后才把缓冲字节写入调用方流。文本使用 UTF-8 和 LF；`clean` 将 CR、LF、`!^` 替换为空格。
- [ ] 问题描述固定为 `528响应码：{码}；528响应描述：{描述}；CCBS响应码：{码}；CCBS响应描述：{描述}`；18 列顺序与设计文档一致，问题级别、登记日、字段名、问题类型和全部整改列都输出空值。
- [ ] Run: `mvn "-Dtest=TransactionDiffTrackingExportServiceTest" test`
  Expected: PASS。
- [ ] Commit: `git add src/main/java/com/spdb/sample/TransactionDiffTrackingExportRow.java src/main/java/com/spdb/sample/TransactionDiffTrackingExportService.java src/test/java/com/spdb/sample/TransactionDiffTrackingExportServiceTest.java` 后执行 `git commit -m "feat: export normalized transaction tracking rows"`。

### Task 3: 独立下载接口

**Files:** `SampleController.java`、`SampleControllerExportTest.java`

- [ ] 写失败测试：`exportTransactionDiffTracking(null, response)` 抛出 `IllegalArgumentException("请选择批次后导出")`；传入 `BATCH_01` 后响应为 `text/plain;charset=UTF-8`，文件名匹配 `trandiff_hf_\\d{14}\\.txt`，替身服务收到 `BATCH_01`。
- [ ] Run: `mvn "-Dtest=SampleControllerExportTest" test`
  Expected: FAIL，端点方法不存在。
- [ ] 注入服务并实现 `GET /samples/transaction-diffs/tracking-export`，仅接收 `batchId`；使用 `prepareText` 设置 UTF-8 的 Content-Disposition 和 `text/plain;charset=UTF-8`；文件名为 `trandiff_hf_{yyyyMMddHHmmss}.txt`。
- [ ] Run: `mvn "-Dtest=SampleControllerExportTest" test`
  Expected: PASS。
- [ ] Commit: `git add src/main/java/com/spdb/web/SampleController.java src/test/java/com/spdb/web/SampleControllerExportTest.java` 后执行 `git commit -m "feat: add transaction tracking export endpoint"`。

### Task 4: 页面按钮和批次前置校验

**Files:** `transaction-diffs.html`、`SampleDetailTemplateTest.java`

- [ ] 写失败测试，断言模板包含 `/samples/transaction-diffs/tracking-export`、`导出问题跟踪表` 和 `data-tracking-export`。
- [ ] Run: `mvn "-Dtest=SampleDetailTemplateTest#transactionDiffPageShowsReturnCodeDetailsAndExportAction" test`
  Expected: FAIL，按钮尚不存在。
- [ ] 在既有筛选动作区增加带 `data-tracking-export` 的提交按钮，`formaction` 指向新接口；增加局部脚本读取同表单 `input[name='batchId']`，为空则阻止提交并显示 `window.alert('请选择批次后导出')`。不改原查询和 ZIP 按钮。
- [ ] Run: `mvn "-Dtest=SampleDetailTemplateTest" test`
  Expected: PASS。
- [ ] Commit: `git add src/main/resources/templates/samples/transaction-diffs.html src/test/java/com/spdb/web/SampleDetailTemplateTest.java` 后执行 `git commit -m "feat: add tracking export action"`。

### Task 5: 全量验证

- [ ] Run: `mvn "-Dtest=TransactionDiffTrackingExportServiceTest,SampleControllerExportTest,SampleDetailTemplateTest,DatabaseScriptLayoutTest" test`
  Expected: `Failures: 0, Errors: 0`。
- [ ] Run: `mvn test`
  Expected: `BUILD SUCCESS`。
- [ ] Run: `git status --short; git diff --check`
  Expected: 无空白错误，并保留执行前已有的用户改动。
