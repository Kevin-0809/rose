# 全量回放交易清单与交易量检查实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 新增全量回放交易清单 CRUD/Excel 全量导入，以及基于该清单的手工交易量检查、主库清理和交易码迁移联动。

**Architecture:** 第一阶段新增独立清单表、Spring JDBC 查询服务、Thymeleaf 列表/编辑/导入页面，并在“数据准备”导航增加入口。第二阶段新增检查批次与明细持久化、只读检查服务和确认执行服务；检查映射按 `tp_online_service_in` 优先、`ana_tran_code_service_mapping` 回退，执行时清理 `primary` 清单外流水并复用现有交易码迁移命令。

**Tech Stack:** Java 17、Spring Boot、Spring JDBC、JPA（仅清单实体可沿用现有风格）、Thymeleaf、PostgreSQL、JUnit 5、AssertJ、Apache POI。

---

### Task 1: 清单表与领域模型

**Files:**
- Modify: `db/ddl.sql`
- Create: `src/main/java/com/spdb/replay/ReplayTransactionCatalogRow.java`
- Create: `src/main/java/com/spdb/replay/ReplayTransactionCatalogForm.java`
- Create: `src/main/java/com/spdb/replay/ReplayTransactionCatalogSearch.java`
- Test: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] **Step 1: 写失败的 DDL 结构测试**

断言 DDL 包含 `ana_replay_transaction_catalog`、`tran_code varchar(200) primary key`、9 个 `varchar(200)` 业务列、`created_at`、`updated_at` 及查询索引。

- [ ] **Step 2: 运行测试确认失败**

运行 `mvn -Dtest=DatabaseScriptLayoutTest test`，预期因表和索引不存在失败。

- [ ] **Step 3: 添加 DDL 和不可变表单类型**

新增表字段：`tran_code`、`tran_name`、`business_domain`、`batch_type`、`new_core_tran_code`、`new_tran_name`、`replay_required`、`original_service_scene_code`、`new_service_scene_code`、`latest_transaction_date`、`created_at`、`updated_at`。添加 `tran_code`、名称、领域、批次和回放字段索引。表单包含新增/编辑共用字段和 `catalogSnapshotTime` 只读校验所需值。

- [ ] **Step 4: 运行 DDL 测试并提交**

运行 `mvn -Dtest=DatabaseScriptLayoutTest test`，预期 PASS；提交 `git add db/ddl.sql src/main/java/com/spdb/replay src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java && git commit -m "feat: add replay transaction catalog table"`。

### Task 2: 清单 CRUD 查询服务与页面

**Files:**
- Create: `src/main/java/com/spdb/replay/ReplayTransactionCatalogService.java`
- Create: `src/main/java/com/spdb/web/ReplayTransactionCatalogController.java`
- Create: `src/main/resources/templates/config/replay-catalog.html`
- Create: `src/main/resources/templates/config/replay-catalog-edit.html`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Test: `src/test/java/com/spdb/replay/ReplayTransactionCatalogServiceTest.java`
- Test: `src/test/java/com/spdb/web/ReplayTransactionCatalogControllerTest.java`
- Test: `src/test/java/com/spdb/web/LayoutTemplateTest.java`

- [ ] **Step 1: 写失败的服务测试**

覆盖：新增保存、按 `tran_code` 查询、精简条件查询、编辑禁止修改主键、删除、`replay_required` 只允许空/是/否、`batch_type` 只允许空/查询/动账、日期只允许合法 `yyyyMMdd`。

- [ ] **Step 2: 运行测试确认失败**

运行 `mvn -Dtest=ReplayTransactionCatalogServiceTest test`，预期服务类和表不存在而失败。

- [ ] **Step 3: 实现服务和控制器**

使用 `NamedParameterJdbcTemplate` 实现分页查询、保存、删除；新增时校验必填主键和业务值，编辑时以路径主键更新除 `tran_code` 外字段，并在更新前比较原主键。控制器提供：`GET/POST /config/replay-catalog`、`GET /config/replay-catalog/new`、`GET /config/replay-catalog/{tranCode}/edit`、`POST /config/replay-catalog/{tranCode}/delete`。

- [ ] **Step 4: 实现 Thymeleaf 页面和导航**

列表页使用现有 `layout.html`、`PageRequestParams`、pager 和 app.css 风格，包含五个确认过的筛选条件、10 个业务列、分页和编辑/删除；编辑页将主键置为 readonly。导航在“数据准备”下增加“全量回放交易清单”。

- [ ] **Step 5: 运行 Web 测试并提交**

运行 `mvn -Dtest=ReplayTransactionCatalogServiceTest,ReplayTransactionCatalogControllerTest,LayoutTemplateTest test`；通过后提交 `git commit -m "feat: add replay transaction catalog maintenance"`。

### Task 3: 11 列 Excel 全量导入

**Files:**
- Create: `src/main/java/com/spdb/replay/ReplayTransactionCatalogWorkbookParser.java`
- Create: `src/main/java/com/spdb/replay/ReplayTransactionCatalogImportService.java`
- Modify: `src/main/java/com/spdb/replay/ReplayTransactionCatalogController.java`
- Modify: `src/main/resources/templates/config/replay-catalog.html`
- Test: `src/test/java/com/spdb/replay/ReplayTransactionCatalogWorkbookParserTest.java`
- Test: `src/test/java/com/spdb/replay/ReplayTransactionCatalogImportServiceTest.java`

- [ ] **Step 1: 写失败的解析测试**

构造 11 列工作簿，断言直接使用 A 列交易码（忽略 B 列后缀）、批次前两字符归类、日期合法性、回放字段枚举；覆盖空数据行、重复交易码、错误表头、错误日期和非法回放值。

- [ ] **Step 2: 运行测试确认失败**

运行 `mvn -Dtest=ReplayTransactionCatalogWorkbookParserTest,ReplayTransactionCatalogImportServiceTest test`，预期解析器不存在而失败。

- [ ] **Step 3: 实现解析和原子替换**

按固定 11 列表头读取，A 列非空，B 列后缀忽略；批次只接受前缀 `查询` 或 `动账`；最近交易日期使用严格 `DateTimeFormatter` 校验真实日期。先完整解析和校验，拒绝空数据、重复交易码和任意行错误；校验成功后在一个事务中 `delete from ana_replay_transaction_catalog` 并批量插入新数据。

- [ ] **Step 4: 接入上传反馈并提交**

控制器使用 multipart 上传，错误响应展示行号和原因；成功展示写入条数和时间。运行两个导入测试类后提交 `git commit -m "feat: import replay catalog workbook"`。

### Task 4: 检查批次表、明细表与状态模型

**Files:**
- Modify: `db/ddl.sql`
- Create: `src/main/java/com/spdb/replay/ReplayVolumeCheckBatch.java`
- Create: `src/main/java/com/spdb/replay/ReplayVolumeCheckDetail.java`
- Create: `src/main/java/com/spdb/replay/ReplayVolumeCleanupDetail.java`
- Test: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] **Step 1: 写失败的 DDL 测试**

断言三张 `ana_` 表的主键、外键/批次索引、状态约束、N 默认值 100、lookback 默认值 30 以及清理和迁移计数字段。

- [ ] **Step 2: 运行测试确认失败**

运行 `mvn -Dtest=DatabaseScriptLayoutTest test`，预期新表结构断言失败。

- [ ] **Step 3: 添加表结构和状态类型**

批次表保存状态、清单快照时间、N、30 天范围、摘要计数、迁移命令 ID、时间和错误；交易明细按清单交易码保存映射数量、完整流水量、检查/迁移状态；清理明细按基础服务码保存映射交易码集合、待删/实际删除数和状态。

- [ ] **Step 4: 运行测试并提交**

运行 DDL 测试后提交 `git commit -m "feat: add replay volume check persistence"`。

### Task 5: 只读检查服务与映射统计

**Files:**
- Create: `src/main/java/com/spdb/replay/ReplayVolumeCheckService.java`
- Create: `src/main/java/com/spdb/replay/ReplayVolumeCheckResult.java`
- Test: `src/test/java/com/spdb/replay/ReplayVolumeCheckServiceTest.java`

- [ ] **Step 1: 写失败的检查测试**

测试 `txn_code` 拆分三种报文类型；按 `(source_ip, trans_id)` 只统计完整 request/response；验证 `tp_online_service_in` 按去点后的 `esf_service_code` 优先，`ana_tran_code_service_mapping` 回退；验证多交易码任一命中清单则保留、双表未命中待清理、无交易量清单交易被识别。

- [ ] **Step 2: 运行测试确认失败**

运行 `mvn -Dtest=ReplayVolumeCheckServiceTest test`，预期检查服务不存在而失败。

- [ ] **Step 3: 实现检查查询和批次落库**

读取清单并保存快照时间；从 `primary` 读取所有历史 request/response 基础服务码，限定 `bzjson/sop/soap`，使用 CTE 或临时聚合按流水键配对；按确认映射规则归属 528 交易码，汇总完整流水量；生成批次、交易码明细和清理明细，结束时状态置 `WAITING_CONFIRM`。

- [ ] **Step 4: 运行检查测试并提交**

运行检查服务测试，确认只读阶段不删除、不创建迁移命令；提交 `git commit -m "feat: check replay transaction volume"`。

### Task 6: 清理主库并联动现有迁移命令

**Files:**
- Modify: `src/main/java/com/spdb/replay/ReplayVolumeCheckService.java`
- Modify: `src/main/java/com/spdb/migration/MigrationCommandService.java`（仅复用现有公开创建入口，不改变原迁移映射优先级）
- Test: `src/test/java/com/spdb/replay/ReplayVolumeCheckServiceTest.java`

- [ ] **Step 1: 写失败的执行测试**

创建待执行批次，断言清单外/双表未映射服务码会删除 `primary` response 后 request 的完整流水，并保留任一映射交易码命中清单的服务码；无交易量交易码创建现有 `TRAN_CODE` 迁移命令，N 默认 100、lookback 30；重复确认和清单快照变化被拒绝。

- [ ] **Step 2: 运行测试确认失败**

运行 `mvn -Dtest=ReplayVolumeCheckServiceTest test`，预期执行入口不存在而失败。

- [ ] **Step 3: 实现确认执行**

校验批次为 `WAITING_CONFIRM` 且清单快照未变化；进入 `EXECUTING`。在 `primary` 事务中按 `(source_ip, trans_id)` 删除待清理服务码的 response 和 request，保存实际删除数；提交后调用现有交易码迁移创建入口，传入无交易量交易码、N 和 30 天，并保存迁移命令 ID。迁移或清理异常时保存已完成计数和错误，状态置 `FAILED`，禁止重复确认。

- [ ] **Step 4: 运行执行测试并提交**

运行检查服务测试，确认源库 `bxds` 不被删除；提交 `git commit -m "feat: clean stale replay flows and launch migration"`。

### Task 7: 检查页面、历史分页与导航

**Files:**
- Create: `src/main/java/com/spdb/web/ReplayVolumeCheckController.java`
- Create: `src/main/resources/templates/config/replay-volume-check.html`
- Create: `src/main/resources/templates/config/replay-volume-check-detail.html`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Test: `src/test/java/com/spdb/web/ReplayVolumeCheckControllerTest.java`
- Test: `src/test/java/com/spdb/web/ReplayVolumeCheckTemplateTest.java`

- [ ] **Step 1: 写失败的 Web 测试**

断言 GET 页面、POST 开始检查、POST 确认执行、历史批次分页和详情分页路由；模板包含 N=100、30 天提示、当前批次摘要、无交易量列表、待清理列表、未映射列表、状态和迁移命令 ID。

- [ ] **Step 2: 运行测试确认失败**

运行 `mvn -Dtest=ReplayVolumeCheckControllerTest,ReplayVolumeCheckTemplateTest,LayoutTemplateTest test`，预期路由和模板不存在而失败。

- [ ] **Step 3: 实现控制器和页面**

列表页显示当前批次摘要、操作按钮和历史批次分页；详情页按明细类型分页展示无交易量交易、清理服务码/流水和未找到映射。`确认执行` 仅在待确认状态且快照未变化时可用。导航增加“回放交易量检查”。

- [ ] **Step 4: 运行 Web 测试并提交**

运行三个 Web 测试类后提交 `git commit -m "feat: add replay volume check page"`。

### Task 8: 全量验证

**Files:** Task 1 至 Task 7 列出的全部新增和修改文件

- [ ] **Step 1: 运行专项回归**

运行 `mvn -Dtest=DatabaseScriptLayoutTest,ReplayTransactionCatalogServiceTest,ReplayTransactionCatalogControllerTest,ReplayTransactionCatalogWorkbookParserTest,ReplayTransactionCatalogImportServiceTest,ReplayVolumeCheckServiceTest,ReplayVolumeCheckControllerTest,ReplayVolumeCheckTemplateTest,LayoutTemplateTest test`，预期全部 PASS。

- [ ] **Step 2: 运行全量测试和静态检查**

运行 `mvn test`、`git diff --check`，确认现有迁移、报文查询和导航测试不回归。

- [ ] **Step 3: 审查工作区并提交集成变更**

运行 `git status --short`，只暂存本功能文件，保留用户已有未跟踪文件；提交 `git commit -m "feat: add replay catalog and volume check"`。
