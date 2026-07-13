# Rose 左侧二级菜单布局重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Rose 的顶部平铺导航替换为按工作流程分类、自动收起的左侧二级导航，并为全部页面提供统一响应式工作台框架。

**Architecture:** 保持控制器和路由不变，继续由每个模板传入的 `active` 标识当前页面。`fragments/layout.html` 改为提供页面壳片段和导航片段；全局 CSS 提供工作台框架和各断点规则，客户端小脚本只负责移动端抽屉及桌面端一级菜单互斥展开。

**Tech Stack:** Spring Boot 3、Thymeleaf、原生 CSS、原生 JavaScript、JUnit 5、AssertJ、Maven。

---

## 文件结构

- 修改：`src/main/resources/templates/fragments/layout.html`：提供侧栏、菜单分组、顶部工具栏和脚本。
- 修改：`src/main/resources/static/css/app.css`：替换全局顶栏布局规则，新增工作台、导航状态、内容区和移动端抽屉规则；保留业务页面专用选择器。
- 修改：`src/main/resources/templates/home.html`、`config/*.html`、`sampling/*.html`、`samples/*.html`、`messages/*.html`、`migration/*.html`：将原有顶栏插槽替换为统一页面壳的开始/结束片段，业务内容不改。
- 修改：`src/test/java/com/spdb/web/LayoutTemplateTest.java`：验证完整菜单映射和模板壳使用。
- 修改：`src/test/java/com/spdb/web/AppCssStyleTest.java`：验证新增框架 CSS 与响应式规则。

### Task 1: 为导航结构建立失败测试

**Files:**
- Modify: `src/test/java/com/spdb/web/LayoutTemplateTest.java`

- [ ] **Step 1: 将扁平导航断言替换为侧栏结构断言**

将 `topbarKeepsFlatNavigationLinks` 重命名为 `sidebarGroupsEveryExistingRoute`，并以如下断言替换原有的 `doesNotContain("nav-group")` 与菜单文本断言：

```java
assertThat(html).contains("class=\"app-sidebar\"");
assertThat(html).contains("class=\"nav-group\"");
assertThat(html).contains("数据准备");
assertThat(html).contains("执行分析");
assertThat(html).contains("运维工具");
assertThat(html).contains("/config/import");
assertThat(html).contains("/config/trans");
assertThat(html).contains("/config/fields");
assertThat(html).contains("/config/recording");
assertThat(html).contains("/sampling/commands");
assertThat(html).contains("/sampling/summaries");
assertThat(html).contains("/samples/transaction-diffs");
assertThat(html).contains("/samples/field-diffs");
assertThat(html).contains("/messages/flow-logs");
assertThat(html).contains("/messages/flow-logs/new");
assertThat(html).contains("/migration/commands");
assertThat(html).contains("/migration/sql-commands");
assertThat(html).doesNotContain("class=\"nav\"");
```

- [ ] **Step 2: 运行测试确认失败**

运行：`mvn -Dtest=LayoutTemplateTest test`

预期：失败，错误信息包含 `class="app-sidebar"`，因为当前片段仍为顶部 `nav`。

- [ ] **Step 3: 提交测试基线**

运行：

```powershell
git add src/test/java/com/spdb/web/LayoutTemplateTest.java
git commit -m "test: define sidebar navigation structure"
```

### Task 2: 实现全局页面壳与流程型二级菜单

**Files:**
- Modify: `src/main/resources/templates/fragments/layout.html`

- [ ] **Step 1: 用工作台片段替换 `topbar` 片段**

将原 `topbar(active)` 片段替换为 `shellStart(active)`，包含下列关键结构；每个二级链接继续使用原有 `active` 值进行高亮：

```html
<div th:fragment="shellStart(active)" class="app-shell" th:attr="data-active=${active}">
  <aside class="app-sidebar" aria-label="主导航">
    <a class="sidebar-brand" href="/"><span class="brand-mark">R</span><span>Rose</span></a>
    <a class="sidebar-home" th:classappend="${active == 'home'} ? 'active'" href="/">概览</a>
    <section class="nav-group" data-group="setup">
      <button class="nav-group-toggle" type="button" aria-expanded="false">数据准备<span aria-hidden="true">⌄</span></button>
      <div class="nav-group-links">
        <a th:classappend="${active == 'configImport'} ? 'active'" href="/config/import">交易导入</a>
        <a th:classappend="${active == 'trans'} ? 'active'" href="/config/trans">交易配置</a>
        <a th:classappend="${active == 'fields'} ? 'active'" href="/config/fields">字段映射</a>
        <a th:classappend="${active == 'recording'} ? 'active'" href="/config/recording">录制配置</a>
      </div>
    </section>
```

在该片段后续添加同结构的 `analysis` 组（`sampling-commands`、`sampling-summaries`、`transaction-diffs`、`field-diffs`）及 `operations` 组（`message-flow-logs`、`message-flow-log-entry`、`migration`、`migration-sql`），然后添加：

```html
  </aside>
  <div class="app-workspace">
    <header class="workspace-bar"><span>Rose Diff Studio</span><span>交易回放差异采样管理</span></header>
    <button class="sidebar-toggle" type="button" aria-expanded="false" aria-controls="app-sidebar">菜单</button>
    <main class="shell layout-frame">
```

为侧栏添加 `id="app-sidebar"`。新增 `shellEnd` 片段：

```html
<th:block th:fragment="shellEnd">
    </main>
  </div>
</div>
</th:block>
```

- [ ] **Step 2: 添加互斥展开与移动抽屉脚本**

在 `layout.html` 底部添加 `sidebarScript` 片段。脚本读取 `.app-shell.dataset.active`，将 `configImport`、`trans`、`fields`、`recording` 映射至 `setup`，采样和差异页映射至 `analysis`，消息和迁移页映射至 `operations`。加载时只给对应 `.nav-group` 添加 `open` 类并将其按钮 `aria-expanded` 设为 `true`；按钮点击时移除其他组的 `open` 并关闭其他按钮。

```javascript
document.querySelectorAll('.nav-group-toggle').forEach((toggle) => {
  toggle.addEventListener('click', () => {
    const selected = toggle.closest('.nav-group');
    document.querySelectorAll('.nav-group').forEach((group) => {
      const open = group === selected && !group.classList.contains('open');
      group.classList.toggle('open', open);
      group.querySelector('.nav-group-toggle').setAttribute('aria-expanded', String(open));
    });
  });
});
```

移动菜单按钮只切换 `.app-shell` 的 `sidebar-open` 类，并同步自身 `aria-expanded`。

- [ ] **Step 3: 运行导航测试确认通过**

运行：`mvn -Dtest=LayoutTemplateTest test`

预期：`BUILD SUCCESS`。

- [ ] **Step 4: 提交全局导航实现**

运行：

```powershell
git add src/main/resources/templates/fragments/layout.html src/test/java/com/spdb/web/LayoutTemplateTest.java
git commit -m "feat: add workflow sidebar navigation"
```

### Task 3: 将所有业务模板接入统一页面壳

**Files:**
- Modify: `src/main/resources/templates/home.html`
- Modify: `src/main/resources/templates/config/import.html`
- Modify: `src/main/resources/templates/config/import-list-progress.html`
- Modify: `src/main/resources/templates/config/trans.html`
- Modify: `src/main/resources/templates/config/fields.html`
- Modify: `src/main/resources/templates/config/recording.html`
- Modify: `src/main/resources/templates/sampling/commands.html`
- Modify: `src/main/resources/templates/sampling/summaries.html`
- Modify: `src/main/resources/templates/samples/transaction-diffs.html`
- Modify: `src/main/resources/templates/samples/field-diffs.html`
- Modify: `src/main/resources/templates/messages/flow-logs.html`
- Modify: `src/main/resources/templates/messages/flow-log-entry.html`
- Modify: `src/main/resources/templates/migration/commands.html`
- Modify: `src/main/resources/templates/migration/sql-commands.html`
- Modify: `src/main/resources/templates/migration/progress.html`

- [ ] **Step 1: 将每个页面的外壳调用改为开始/结束片段**

在每个模板中，将：

```html
<main class="shell layout-frame">
  <div th:replace="~{fragments/layout :: topbar(${active})}"></div>
```

替换为：

```html
<div th:replace="~{fragments/layout :: shellStart(${active})}"></div>
```

并将原模板末尾的 `</main>` 替换为：

```html
<div th:replace="~{fragments/layout :: shellEnd}"></div>
<div th:replace="~{fragments/layout :: sidebarScript}"></div>
```

页面已有的业务 `script` 标签保留在 `sidebarScript` 后，避免影响 ECharts、日期选择器、详情弹窗和进度轮询的加载顺序。

- [ ] **Step 2: 扩展模板测试，覆盖所有页面壳调用**

在 `LayoutTemplateTest` 添加：

```java
@Test
void allPagesUseTheSidebarShell() throws Exception {
    List<String> templates = List.of(
            "/templates/home.html", "/templates/config/import.html", "/templates/config/import-list-progress.html",
            "/templates/config/trans.html", "/templates/config/fields.html", "/templates/config/recording.html",
            "/templates/sampling/commands.html", "/templates/sampling/summaries.html",
            "/templates/samples/transaction-diffs.html", "/templates/samples/field-diffs.html",
            "/templates/messages/flow-logs.html", "/templates/messages/flow-log-entry.html",
            "/templates/migration/commands.html", "/templates/migration/sql-commands.html", "/templates/migration/progress.html"
    );
    for (String template : templates) {
        String html = new String(getClass().getResourceAsStream(template).readAllBytes(), StandardCharsets.UTF_8);
        assertThat(html).as(template).contains("fragments/layout :: shellStart").contains("fragments/layout :: shellEnd");
    }
}
```

- [ ] **Step 3: 运行模板相关测试确认通过**

运行：`mvn -Dtest=LayoutTemplateTest,HomeTemplateTest,SamplingCommandTemplateTest,SamplingSummaryTemplateTest,MigrationCommandsTemplateTest,MigrationProgressTemplateTest test`

预期：`BUILD SUCCESS`。

- [ ] **Step 4: 提交模板接入**

运行：

```powershell
git add src/main/resources/templates src/test/java/com/spdb/web/LayoutTemplateTest.java
git commit -m "refactor: wrap pages in application shell"
```

### Task 4: 实现侧栏工作台视觉与响应式规则

**Files:**
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/test/java/com/spdb/web/AppCssStyleTest.java`

- [ ] **Step 1: 为工作台样式写失败断言**

在 `usesMinimalTechAdminTokens` 末尾添加：

```java
assertThat(css).contains(".app-shell");
assertThat(css).contains(".app-sidebar");
assertThat(css).contains(".nav-group.open .nav-group-links");
assertThat(css).contains(".app-workspace");
assertThat(css).contains(".workspace-bar");
assertThat(css).contains(".sidebar-toggle");
assertThat(css).contains("@media (max-width: 720px)");
assertThat(css).contains(".app-shell.sidebar-open .app-sidebar");
```

- [ ] **Step 2: 运行 CSS 测试确认失败**

运行：`mvn -Dtest=AppCssStyleTest test`

预期：失败，错误信息包含 `.app-sidebar`。

- [ ] **Step 3: 替换顶栏布局并添加工作台样式**

保留既有变量和业务专用选择器，将 `.shell` 从全局宽度容器调整为内容区容器，并加入以下核心规则：

```css
.app-shell { min-height: 100vh; display: grid; grid-template-columns: 244px minmax(0, 1fr); background: var(--bg-page); }
.app-sidebar { position: sticky; top: 0; height: 100vh; overflow-y: auto; padding: 20px 12px; background: #123241; border-right: 1px solid rgba(183, 215, 217, .18); }
.app-workspace { min-width: 0; }
.workspace-bar { min-height: 44px; display: flex; align-items: center; justify-content: space-between; padding: 0 28px; border-bottom: 1px solid var(--border); color: var(--text-secondary); }
.shell { width: min(1500px, calc(100% - 56px)); margin: 0 auto; padding: 28px 0 40px; }
.nav-group-links { display: none; }
.nav-group.open .nav-group-links { display: grid; }
```

导航以深青侧栏、白灰文本、橙色当前链接左边线和青绿色分组按钮状态呈现；所有圆角不超过 `6px`。通过 `@media (max-width: 720px)` 使侧栏改为屏幕外抽屉，默认使用 `transform: translateX(-100%)`，仅 `.app-shell.sidebar-open .app-sidebar` 恢复 `translateX(0)`；内容区占满宽度，`.sidebar-toggle` 显示。

- [ ] **Step 4: 运行 CSS 测试确认通过**

运行：`mvn -Dtest=AppCssStyleTest test`

预期：`BUILD SUCCESS`。

- [ ] **Step 5: 提交样式重构**

运行：

```powershell
git add src/main/resources/static/css/app.css src/test/java/com/spdb/web/AppCssStyleTest.java
git commit -m "feat: style responsive sidebar workspace"
```

### Task 5: 集成验证与视觉验收

**Files:**
- Modify: `src/test/java/com/spdb/web/LayoutTemplateTest.java`（仅当全量测试揭示模板断言与最终片段不一致时）

- [ ] **Step 1: 运行完整测试套件**

运行：`mvn test`

预期：`BUILD SUCCESS`，所有现有控制器、模板、导出和服务测试通过。

- [ ] **Step 2: 本地启动应用**

运行：`mvn spring-boot:run`

预期：日志包含 `Started RoseApplication`，默认在 `http://localhost:8080` 提供页面。

- [ ] **Step 3: 使用浏览器验证关键导航状态**

依次打开 `/`、`/config/trans`、`/sampling/commands`、`/messages/flow-logs`。确认首页没有功能组展开；后三页分别只展开“数据准备”“执行分析”“运维工具”；点击另一个一级组后先前组收起；点击二级菜单能进入原路由。

- [ ] **Step 4: 使用 1280px 与 390px 宽度检查布局**

在 1280px 下确认侧栏固定、标题和表格没有重叠；在 390px 下确认菜单按钮可打开/关闭抽屉、主内容不会被侧栏遮挡、筛选表单和卡片变为单列、宽表仍能横向滚动。

- [ ] **Step 5: 提交任何仅为修复验证发现的问题所作的最小调整**

运行：

```powershell
git add src/main/resources/templates src/main/resources/static/css/app.css src/test/java/com/spdb/web
git commit -m "fix: polish sidebar layout verification findings"
```

仅在步骤 1-4 产生实际修复时执行此提交；若没有变更则不创建空提交。
