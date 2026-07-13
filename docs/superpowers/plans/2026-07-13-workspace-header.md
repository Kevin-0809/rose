# Rose 工作区头部 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为保留左侧菜单的全部业务页面增加统一的右侧状态栏和工作区头部。

**Architecture:** `layout.html` 提供带当前路径的共享头部片段，页面继续自行拥有业务 `main`。现有 `workspace-bar` 承担状态信息，新增 `workspace-header` 承担页面标题与说明，CSS 只扩展全局工作区层次和窄屏折叠规则。

**Tech Stack:** Thymeleaf、原生 CSS、JUnit 5、AssertJ、Maven。

---

### Task 1: 以测试定义共享头部契约

**Files:**
- Modify: `src/test/java/com/spdb/web/LayoutTemplateTest.java`

- [ ] **Step 1: 新增失败断言**

在 `allPagesUseTheSidebarShell` 中，对每个模板增加：

```java
.contains("fragments/layout :: workspaceHeader")
```

并在导航结构测试中增加：

```java
assertThat(html).contains("th:fragment=\"workspaceHeader");
assertThat(html).contains("class=\"workspace-header\"");
assertThat(html).contains("class=\"workspace-status\"");
```

- [ ] **Step 2: 运行 RED 测试**

运行：`mvn -Dtest=LayoutTemplateTest test`

预期：失败，当前模板不存在 `workspaceHeader`。

### Task 2: 接入状态栏和页面头部

**Files:**
- Modify: `src/main/resources/templates/fragments/layout.html`
- Modify: `src/main/resources/templates/home.html`
- Modify: `src/main/resources/templates/config/*.html`
- Modify: `src/main/resources/templates/sampling/*.html`
- Modify: `src/main/resources/templates/samples/*.html`
- Modify: `src/main/resources/templates/messages/*.html`
- Modify: `src/main/resources/templates/migration/*.html`

- [ ] **Step 1: 扩展共享片段**

将 `workspaceBar` 扩展为含产品名、服务正常状态和当前日期的状态栏，并新增：

```html
<section th:fragment="workspaceHeader(title, eyebrow, description)" class="workspace-header">
  <div>
    <div class="workspace-eyebrow" th:text="${eyebrow}"></div>
    <h1 th:text="${title}"></h1>
    <p th:if="${description}" th:text="${description}"></p>
  </div>
  <div class="workspace-status"><span class="status-dot"></span>服务正常</div>
</section>
```

- [ ] **Step 2: 每页调用共享头部**

在每个模板的 `workspaceBar` 后、`main` 前插入 `workspaceHeader`。使用每页已有 `h1` 和 `eyebrow` 的等价中文内容；首页使用“交易回放差异采样管理”和现有说明，并删除原有重复 `page-head` 标题区。

- [ ] **Step 3: 运行 GREEN 测试**

运行：`mvn -Dtest=LayoutTemplateTest test`

预期：`BUILD SUCCESS`。

### Task 3: 统一深青色层次与移动端规则

**Files:**
- Modify: `src/main/resources/static/css/app.css`
- Modify: `src/test/java/com/spdb/web/AppCssStyleTest.java`

- [ ] **Step 1: 增加失败 CSS 断言**

```java
assertThat(css).contains(".workspace-header");
assertThat(css).contains(".workspace-status");
assertThat(css).contains(".status-dot");
```

- [ ] **Step 2: 添加最小样式**

为状态栏、头部、状态点及其 `max-width: 720px` 规则添加纯色背景、细边界、深青色层次和可换行标题规则；不新增渐变、阴影或大圆角。

- [ ] **Step 3: 验证 CSS 测试**

运行：`mvn -Dtest=AppCssStyleTest test`

预期：`BUILD SUCCESS`。

### Task 4: 浏览器验收

**Files:**
- No source changes required

- [ ] **Step 1: 运行界面回归测试**

运行：`mvn -Dtest=LayoutTemplateTest,AppCssStyleTest,HomeTemplateTest test`

预期：`BUILD SUCCESS`。

- [ ] **Step 2: 验证桌面和窄屏**

启动应用并在 1920px 与 390px 宽度检查首页和采样页：左侧菜单存在，状态栏和头部不留空白，窄屏头部无重叠。
