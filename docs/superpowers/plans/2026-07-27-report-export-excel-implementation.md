# 报表明细导出 Excel 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为已完成的报表导出批次提供按领域分 Sheet 的流式 Excel 下载。

**Architecture:** 跑批阶段将字段映射的四个名称固化进 `ana_field_diff_tracking_export`，使下载只读取批次结果表。下载服务以 JDBC 回调按领域顺序读取记录，使用 `SXSSFWorkbook` 逐行写入 HTTP 输出流，控制内存占用。

**Tech Stack:** Java 17、Spring MVC/JDBC、PostgreSQL、Apache POI SXSSF、JUnit 5、H2、MockMvc/MockHttpServletResponse。

---

## 文件结构

- 新建 `src/main/java/com/spdb/report/ReportExportExcelService.java`：流式生成工作簿、样式、领域 Sheet 与行映射。
- 新建 `src/test/java/com/spdb/report/ReportExportExcelServiceTest.java`：校验工作簿结构、映射和脱敏文本。
- 修改 `src/main/java/com/spdb/report/ReportExportBatchRunner.java`：查询字段映射并将 SOP、SOAP、BizJSON、中文名写入字段导出表。
- 修改 `src/test/java/com/spdb/report/ReportExportBatchRunnerTest.java`：校验字段映射落库和交易问题类别/描述。
- 修改 `src/main/java/com/spdb/web/ReportExportController.java`：增加受状态保护的下载端点。
- 修改 `src/main/resources/templates/report-exports/commands.html`：仅为已完成批次添加“导出 Excel”操作。
- 修改 `src/test/java/com/spdb/web/ReportExportControllerTest.java`：校验下载委派、状态拒绝和列表模型。

### Task 1: 固化字段映射和交易问题元数据

**Files:**
- Modify: `src/main/java/com/spdb/report/ReportExportBatchRunner.java`
- Modify: `src/test/java/com/spdb/report/ReportExportBatchRunnerTest.java`

- [ ] **Step 1: 写入失败测试，定义落库口径**

在跑批测试中准备一条 `ana_field_mapping` 与一条 `tss_field_comp`，断言字段导出行包含四个名称且不保存明文字段描述；准备 `comp_result` 为 `1`、`2`、`3`、`8` 的交易，断言 `field_name` 为业务类别且 `problem_description` 同时含双方错误码和描述。

```java
assertThat(row.sopFieldName()).isEqualTo("Request.AccountNo");
assertThat(row.soapFieldName()).isEqualTo("AccountNo");
assertThat(row.bizjsonFieldName()).isEqualTo("accountNo");
assertThat(row.fieldCnName()).isEqualTo("账号");
assertThat(row.problemDescription()).isEqualTo("528：有值；CCBS：无值");
assertThat(transaction.fieldName()).isEqualTo("528失败/CCBS成功");
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn "-Dtest=ReportExportBatchRunnerTest" test`

Expected: FAIL，因为当前跑批只写 `soap_field_name`，交易字段名仍是原始 `comp_result`，字段描述泄露原始值。

- [ ] **Step 3: 最小化实现映射固化**

在 `runInTransaction` 读取 `ana_field_mapping`：

```java
Map<String, FieldMapping> mappings = fieldMappings();
insertFieldDetails(batchId, reportDate, exportTime, fields, catalogs, mappings);
```

以 `key(service) + "|" + key(normalizedField(origFieldName))` 定位映射；无映射时保留归一后的 SOAP 字段名。将四个字段名写进 `ana_field_diff_tracking_export`；`problem_description` 改为：

```java
private static String presence(String value) {
    return value == null || value.isEmpty() ? "无值" : "有值";
}

private static String fieldDescription(Field field) {
    return "528：" + presence(field.origValue()) + "；CCBS：" + presence(field.destValue());
}
```

交易类别使用 `switch (tran.compResult())`，将 `1` 映射为“528失败/CCBS成功”、`2` 映射为“528成功/CCBS失败”、`3` 与 `8` 映射为“528失败/CCBS失败”；描述统一为双方错误码与描述的拼接。

- [ ] **Step 4: 运行测试并确认通过**

Run: `mvn "-Dtest=ReportExportBatchRunnerTest" test`

Expected: PASS，包含字段名称、四种有值/无值组合和交易错误描述断言。

- [ ] **Step 5: 提交跑批元数据改动**

```powershell
git add src/main/java/com/spdb/report/ReportExportBatchRunner.java src/test/java/com/spdb/report/ReportExportBatchRunnerTest.java
git commit -m "feat: materialize report export detail metadata"
```

### Task 2: 实现 SXSSF 流式 Excel 服务

**Files:**
- Create: `src/main/java/com/spdb/report/ReportExportExcelService.java`
- Create: `src/test/java/com/spdb/report/ReportExportExcelServiceTest.java`

- [ ] **Step 1: 写入失败测试，定义工作簿和文本结果**

在 H2 创建报表命令、汇总、交易明细和字段明细表。调用 `stream(batchId, outputStream)` 后用 `WorkbookFactory.create` 读取字节，断言：第一个 Sheet 为“汇总信息”；存在“支付” Sheet；汇总表头有“发送统计”合并区域；问题明细首行有筛选和冻结；交易和字段行的第 6 列分别为“交易级”“字段级”；字段值没有出现在工作簿文本中。

```java
assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("汇总信息");
assertThat(workbook.getSheet("支付").getPaneInformation().isFreezePane()).isTrue();
assertThat(sheet.getRow(1).getCell(5).getStringCellValue()).isEqualTo("交易级");
assertThat(sheet.getRow(2).getCell(8).getStringCellValue()).isEqualTo("528：有值；CCBS：无值");
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn "-Dtest=ReportExportExcelServiceTest" test`

Expected: FAIL，因为 `ReportExportExcelService` 尚不存在。

- [ ] **Step 3: 实现顺序读取和工作簿写入**

创建服务并注入 `NamedParameterJdbcTemplate`。使用：

```java
try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
    workbook.setCompressTempFiles(true);
    writeSummarySheet(workbook, batchId);
    for (String module : findModules(batchId)) {
        SXSSFSheet sheet = workbook.createSheet(uniqueSheetName(workbook, module));
        int[] rowIndex = {writeDetailHeader(sheet)};
        streamTransactionRows(batchId, module, rs -> writeTransactionRow(sheet, rowIndex[0]++, rs));
        streamFieldRows(batchId, module, rs -> writeFieldRow(sheet, rowIndex[0]++, rs));
    }
    workbook.write(outputStream);
} finally {
    workbook.dispose();
}
```

每个 `stream*Rows` 通过 `JdbcTemplate.query(PreparedStatementCreator, RowCallbackHandler)` 设置 `fetchSize=500` 并直接写行；查询按 `module_name, row_no` 排序。使用固定列宽、换行、边框、蓝色明细表头、绿色双层汇总表头；表头设置 `setAutoFilter` 和 `createFreezePane(0, 1)`。所有文本经 `empty(value)` 处理，禁止输出 `null`。

- [ ] **Step 4: 运行测试并确认通过**

Run: `mvn "-Dtest=ReportExportExcelServiceTest" test`

Expected: PASS，覆盖 Sheet、样式结构、交易类别、字段脱敏和流式查询回调。

- [ ] **Step 5: 提交 Excel 服务**

```powershell
git add src/main/java/com/spdb/report/ReportExportExcelService.java src/test/java/com/spdb/report/ReportExportExcelServiceTest.java
git commit -m "feat: stream report export excel workbook"
```

### Task 3: 添加下载端点和列表操作

**Files:**
- Modify: `src/main/java/com/spdb/web/ReportExportController.java`
- Modify: `src/main/resources/templates/report-exports/commands.html`
- Modify: `src/test/java/com/spdb/web/ReportExportControllerTest.java`

- [ ] **Step 1: 写入失败测试，定义 HTTP 合约**

构造状态为 `SUCCEEDED` 的命令和 mock Excel 服务，调用控制器下载方法，断言响应 `Content-Type` 为 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，`Content-Disposition` 含 UTF-8 文件名，且委派了正确 `batchId`。为 `RUNNING` 命令断言抛出 `ResponseStatusException(HttpStatus.CONFLICT, ...)`。

```java
verify(excelService).stream("RPT1", response.getOutputStream());
assertThat(response.getHeader("Content-Disposition")).contains("RPT1.xlsx");
assertThatThrownBy(() -> controller.download("RPT1", response))
        .isInstanceOf(ResponseStatusException.class);
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn "-Dtest=ReportExportControllerTest" test`

Expected: FAIL，因为控制器构造器与下载方法尚未提供。

- [ ] **Step 3: 实现受保护下载和入口**

控制器注入 `ReportExportExcelService`，新增：

```java
@GetMapping("/report-exports/{batchId}/excel")
public void download(@PathVariable String batchId, HttpServletResponse response) throws IOException {
    ReportExportCommandRow command = reportExportCommandService.findByBatchId(batchId);
    if (command == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到导出批次");
    if (!"SUCCEEDED".equals(command.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "批次尚未完成");
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition", ContentDisposition.attachment()
            .filename("报表明细导出-" + batchId + ".xlsx", StandardCharsets.UTF_8).build().toString());
    excelService.stream(batchId, response.getOutputStream());
}
```

在 `commands.html` 的操作单元格保留“查看结果”，并在 `row.status() == 'SUCCEEDED'` 时添加：

```html
<a class="btn" th:if="${row.status() == 'SUCCEEDED'}"
   th:href="@{/report-exports/{batchId}/excel(batchId=${row.batchId()})}">导出 Excel</a>
```

- [ ] **Step 4: 运行测试并确认通过**

Run: `mvn "-Dtest=ReportExportControllerTest" test`

Expected: PASS，已完成批次可下载，未完成和不存在批次被拒绝。

- [ ] **Step 5: 提交 Web 入口**

```powershell
git add src/main/java/com/spdb/web/ReportExportController.java src/main/resources/templates/report-exports/commands.html src/test/java/com/spdb/web/ReportExportControllerTest.java
git commit -m "feat: add report export excel download"
```

### Task 4: 完整回归与人工验证

**Files:**
- Verify: `src/main/java/com/spdb/report/ReportExportExcelService.java`
- Verify: `src/main/resources/templates/report-exports/commands.html`

- [ ] **Step 1: 运行全量测试**

Run: `$env:JAVA_HOME='C:\Users\Kevin\.jdks\temurin-17\jdk-17.0.19+10'; $env:Path=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:Path; mvn test`

Expected: BUILD SUCCESS，所有测试无失败和错误。

- [ ] **Step 2: 检查变更质量和提交范围**

Run: `git diff --check; git status --short`

Expected: 无空白错误；不暂存 `.superpowers/`。

- [ ] **Step 3: 人工下载检查**

启动应用，创建或选择已完成批次，在列表点击“导出 Excel”，打开文件验证：汇总信息 Sheet 为首个、每个领域一个 Sheet、交易与字段混排、字段值未明文出现、冻结首行和筛选可用。
