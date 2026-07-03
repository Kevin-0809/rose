# 字段差异列表与明细页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 将字段差异列表改成紧凑摘要表，并增加单行明细页�?
**Architecture:** 复用现有 Spring MVC + Thymeleaf + JDBC 查询结构。`SampleQueryService` 负责�?`result_id` 查询单条字段差异，`SampleController` 增加明细路由，列表模板和 CSS 只做字段差异页面的局部调整�?
**Tech Stack:** Java 17, Spring Boot MVC, Spring JDBC, Thymeleaf, JUnit 5, AssertJ, Mockito, H2.

---

### Task 1: 字段差异明细查询

**Files:**
- Modify: `src/main/java/com/spdb/sample/SampleFieldDiffRow.java`
- Modify: `src/main/java/com/spdb/sample/SampleQueryService.java`
- Test: `src/test/java/com/spdb/sample/SampleQueryServiceTest.java`

- [x] **Step 1: Write the failing test**

Add a test that calls `service.fieldDiff(501L)` and verifies all detail fields are returned. Also verify a missing ID returns empty.

- [x] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=SampleQueryServiceTest#findsFieldDiffDetailByResultId test`
Expected: compilation failure or assertion failure because `fieldDiff(Long)` does not exist.

- [x] **Step 3: Write minimal implementation**

Add `Long resultId` to `SampleFieldDiffRow`, select `r.result_id`, map it, and implement `public Optional<SampleFieldDiffRow> fieldDiff(Long resultId)`.

- [x] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=SampleQueryServiceTest#findsFieldDiffDetailByResultId test`
Expected: PASS.

### Task 2: 列表与明细模�?
**Files:**
- Modify: `src/main/resources/templates/samples/field-diffs.html`
- Create: `src/main/resources/templates/samples/field-diff-detail.html`
- Test: `src/test/java/com/spdb/web/SampleDetailTemplateTest.java`

- [x] **Step 1: Write failing template tests**

Update tests so the list template must include SOAP field, “查看�?link, double-click navigation, and must not include business date or mapping status columns. Add a test for the detail template containing full field/value/status sections.

- [x] **Step 2: Run tests to verify failure**

Run: `mvn -Dtest=SampleDetailTemplateTest test`
Expected: FAIL because the current list still contains old columns and the detail template does not exist.

- [x] **Step 3: Update templates**

Render only the compact list columns: batch, transaction code, service code, message type, SOAP field, Chinese field name, sample sequence, 528 value, CCBS value, owner, affected count, and action. Add `ondblclick` navigation per row. Add detail template with complete fields.

- [x] **Step 4: Run template tests**

Run: `mvn -Dtest=SampleDetailTemplateTest test`
Expected: PASS.

### Task 3: Controller route and CSS

**Files:**
- Modify: `src/main/java/com/spdb/web/SampleController.java`
- Modify: `src/main/resources/static/css/app.css`
- Test: `src/test/java/com/spdb/web/SampleControllerTest.java`
- Test: `src/test/java/com/spdb/web/AppCssStyleTest.java`

- [x] **Step 1: Write failing tests**

Add controller test for `fieldDiffDetail(501L, model)` returning `samples/field-diff-detail`. Update CSS test to require compact field-diff table width behavior and reject `min-width: 2200px`.

- [x] **Step 2: Run tests to verify failure**

Run: `mvn -Dtest=SampleControllerTest,AppCssStyleTest test`
Expected: FAIL because controller method and CSS changes are absent.

- [x] **Step 3: Implement controller and CSS**

Add `GET /samples/field-diffs/{resultId}` with `@PathVariable`. Add compact field-diff CSS: table width `100%`, no 2200px min-width, semantic widths for remaining columns, and single-line ellipsis.

- [x] **Step 4: Run targeted tests**

Run: `mvn -Dtest=SampleQueryServiceTest#findsFieldDiffDetailByResultId,SampleDetailTemplateTest,SampleControllerTest,AppCssStyleTest test`
Expected: PASS, except environment-level Mockito attach failures must be reported if present.

### Task 4: Review and verification

**Files:**
- Review all modified files.

- [x] **Step 1: Run relevant non-Mockito tests**

Run focused tests that do not depend on Mockito if the local JDK blocks inline mocking.

- [x] **Step 2: Run full test suite**

Run: `mvn test`
Expected: PASS, or document existing Mockito/Byte Buddy attach failure if it recurs.

- [x] **Step 3: Code review**

Review diff for regressions: missing row IDs, broken export behavior, accidental removal of filters, unclear navigation, and CSS impact outside field-diff table.
