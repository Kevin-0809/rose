# Message Flow Log Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a `/messages/flow-logs/new` page that inserts one request row and an optional response row into the message flow log tables.

**Architecture:** Add a small form record in `com.spdb.message`, extend `MessageFlowLogService` with a transactional save method, and extend the existing message controller with GET/POST handlers. Add one Thymeleaf template and one topbar link, keeping the existing flat navigation and form styles.

**Tech Stack:** Java 17, Spring Boot MVC, `NamedParameterJdbcTemplate`, Thymeleaf, JUnit 5, AssertJ, Mockito, H2 for service tests.

---

## File Structure

- Create `src/main/java/com/spdb/message/MessageFlowLogEntryForm.java`: submitted form model and simple helper methods.
- Modify `src/main/java/com/spdb/message/MessageFlowLogService.java`: insert request and optional response rows.
- Modify `src/main/java/com/spdb/web/MessageFlowLogController.java`: render and submit the entry form.
- Create `src/main/resources/templates/messages/flow-log-entry.html`: operational data-entry page.
- Modify `src/main/resources/templates/fragments/layout.html`: add `报文录入` navigation link.
- Modify `src/test/java/com/spdb/message/MessageFlowLogServiceTest.java`: service save tests.
- Create `src/test/java/com/spdb/web/MessageFlowLogEntryControllerTest.java`: controller tests.
- Create `src/test/java/com/spdb/web/MessageFlowLogEntryTemplateTest.java`: template test.
- Modify `src/test/java/com/spdb/web/LayoutTemplateTest.java`: navigation test.

### Task 1: Service Save Behavior

**Files:**
- Create: `src/main/java/com/spdb/message/MessageFlowLogEntryForm.java`
- Modify: `src/main/java/com/spdb/message/MessageFlowLogService.java`
- Test: `src/test/java/com/spdb/message/MessageFlowLogServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Add these tests and helper field to `MessageFlowLogServiceTest`:

```java
private NamedParameterJdbcTemplate jdbc;
```

Assign the existing local `jdbc` in `setUp()` to the field:

```java
jdbc = new NamedParameterJdbcTemplate(dataSource);
```

Then add:

```java
@Test
void savesRequestAndResponseRowsFromEntryForm() {
    MessageFlowLogEntryForm form = new MessageFlowLogEntryForm(
            " 10.10.1.20 ",
            " 0200202606220001 ",
            " PAY001 ",
            "JSON",
            1782090000000L,
            " GLOBAL-1 ",
            " TELLER009 ",
            "{\"amount\":\"88.00\"}",
            1782090000123L,
            "000000",
            "交易成功",
            "{\"status\":\"OK\"}"
    );

    service.saveEntry(form);

    Map<String, Object> request = jdbc.getJdbcTemplate().queryForMap(
            "select * from msg_flow_log_request where trans_id = '0200202606220001'"
    );
    assertThat(request.get("source_ip")).isEqualTo("10.10.1.20");
    assertThat(request.get("txn_code")).isEqualTo("PAY001");
    assertThat(request.get("txn_time")).isEqualTo(1782090000000L);
    assertThat(request.get("message_type")).isEqualTo("JSON");
    assertThat(request.get("global_seq_no")).isEqualTo("GLOBAL-1");
    assertThat(request.get("tran_teller_no")).isEqualTo("TELLER009");
    assertThat(new String((byte[]) request.get("request_message"), StandardCharsets.UTF_8))
            .isEqualTo("{\"amount\":\"88.00\"}");

    Map<String, Object> response = jdbc.getJdbcTemplate().queryForMap(
            "select * from msg_flow_log_response where trans_id = '0200202606220001'"
    );
    assertThat(response.get("source_ip")).isEqualTo("10.10.1.20");
    assertThat(response.get("txn_code")).isEqualTo("PAY001");
    assertThat(response.get("response_time")).isEqualTo(1782090000123L);
    assertThat(response.get("message_type")).isEqualTo("JSON");
    assertThat(response.get("return_code")).isEqualTo("000000");
    assertThat(response.get("return_msg")).isEqualTo("交易成功");
    assertThat(new String((byte[]) response.get("response_message"), StandardCharsets.UTF_8))
            .isEqualTo("{\"status\":\"OK\"}");
}

@Test
void savesOnlyRequestWhenResponseFieldsAreBlank() {
    MessageFlowLogEntryForm form = new MessageFlowLogEntryForm(
            "10.10.1.21",
            "0200202606220002",
            "PAY002",
            "JSON",
            1782090100000L,
            "",
            "",
            "{\"amount\":\"99.00\"}",
            null,
            "",
            "",
            ""
    );

    service.saveEntry(form);

    Integer requestCount = jdbc.getJdbcTemplate().queryForObject(
            "select count(*) from msg_flow_log_request where trans_id = '0200202606220002'",
            Integer.class
    );
    Integer responseCount = jdbc.getJdbcTemplate().queryForObject(
            "select count(*) from msg_flow_log_response where trans_id = '0200202606220002'",
            Integer.class
    );

    assertThat(requestCount).isEqualTo(1);
    assertThat(responseCount).isZero();
}
```

- [ ] **Step 2: Run service tests to verify RED**

Run:

```bash
mvn -Dtest=MessageFlowLogServiceTest test
```

Expected: compilation fails because `MessageFlowLogEntryForm` and `saveEntry` do not exist.

- [ ] **Step 3: Add the form record**

Create `src/main/java/com/spdb/message/MessageFlowLogEntryForm.java`:

```java
package com.spdb.message;

import org.springframework.util.StringUtils;

public record MessageFlowLogEntryForm(
        String sourceIp,
        String transId,
        String txnCode,
        String messageType,
        Long txnTime,
        String globalSeqNo,
        String tranTellerNo,
        String requestMessage,
        Long responseTime,
        String returnCode,
        String returnMsg,
        String responseMessage
) {
    public String cleanSourceIp() {
        return clean(sourceIp);
    }

    public String cleanTransId() {
        return clean(transId);
    }

    public String cleanTxnCode() {
        return clean(txnCode);
    }

    public String cleanMessageType() {
        return clean(messageType);
    }

    public String cleanGlobalSeqNo() {
        return clean(globalSeqNo);
    }

    public String cleanTranTellerNo() {
        return clean(tranTellerNo);
    }

    public String cleanRequestMessage() {
        return clean(requestMessage);
    }

    public String cleanReturnCode() {
        return clean(returnCode);
    }

    public String cleanReturnMsg() {
        return clean(returnMsg);
    }

    public String cleanResponseMessage() {
        return clean(responseMessage);
    }

    public boolean hasRequiredRequestFields() {
        return StringUtils.hasText(sourceIp)
                && StringUtils.hasText(transId)
                && StringUtils.hasText(txnCode)
                && txnTime != null
                && StringUtils.hasText(requestMessage);
    }

    public boolean hasResponseFields() {
        return responseTime != null
                || StringUtils.hasText(returnCode)
                || StringUtils.hasText(returnMsg)
                || StringUtils.hasText(responseMessage);
    }

    public static MessageFlowLogEntryForm empty() {
        return new MessageFlowLogEntryForm("", "", "", "", null, "", "", "", null, "", "", "");
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
```

- [ ] **Step 4: Implement the service save method**

Modify `src/main/java/com/spdb/message/MessageFlowLogService.java`:

Add imports:

```java
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
```

Add this method inside the class:

```java
@Transactional
public void saveEntry(MessageFlowLogEntryForm form) {
    if (form == null || !form.hasRequiredRequestFields()) {
        throw new IllegalArgumentException("sourceIp, transId, txnCode, txnTime, and requestMessage are required");
    }
    Map<String, Object> params = Map.ofEntries(
            Map.entry("sourceIp", form.cleanSourceIp()),
            Map.entry("transId", form.cleanTransId()),
            Map.entry("txnCode", form.cleanTxnCode()),
            Map.entry("txnTime", form.txnTime()),
            Map.entry("messageType", form.cleanMessageType()),
            Map.entry("requestMessage", form.cleanRequestMessage().getBytes(StandardCharsets.UTF_8)),
            Map.entry("globalSeqNo", form.cleanGlobalSeqNo()),
            Map.entry("tranTellerNo", form.cleanTranTellerNo())
    );
    jdbc.update("""
            insert into msg_flow_log_request (
                source_ip, trans_id, txn_code, txn_time, message_type,
                request_message, global_seq_no, tran_teller_no
            ) values (
                :sourceIp, :transId, :txnCode, :txnTime, :messageType,
                :requestMessage, :globalSeqNo, :tranTellerNo
            )
            """, params);

    if (!form.hasResponseFields()) {
        return;
    }
    Map<String, Object> responseParams = Map.of(
            "sourceIp", form.cleanSourceIp(),
            "transId", form.cleanTransId(),
            "txnCode", form.cleanTxnCode(),
            "responseTime", form.responseTime(),
            "messageType", form.cleanMessageType(),
            "responseMessage", bytesOrNull(form.cleanResponseMessage()),
            "returnCode", form.cleanReturnCode(),
            "returnMsg", form.cleanReturnMsg()
    );
    jdbc.update("""
            insert into msg_flow_log_response (
                source_ip, trans_id, txn_code, response_time, message_type,
                response_message, return_code, return_msg
            ) values (
                :sourceIp, :transId, :txnCode, :responseTime, :messageType,
                :responseMessage, :returnCode, :returnMsg
            )
            """, responseParams);
}

private static byte[] bytesOrNull(String value) {
    return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
}
```

- [ ] **Step 5: Run service tests to verify GREEN**

Run:

```bash
mvn -Dtest=MessageFlowLogServiceTest test
```

Expected: all `MessageFlowLogServiceTest` tests pass.

- [ ] **Step 6: Commit Task 1**

```bash
git add src/main/java/com/spdb/message/MessageFlowLogEntryForm.java src/main/java/com/spdb/message/MessageFlowLogService.java src/test/java/com/spdb/message/MessageFlowLogServiceTest.java
git commit -m "Add message flow log entry persistence"
```

### Task 2: Controller Entry Workflow

**Files:**
- Modify: `src/main/java/com/spdb/web/MessageFlowLogController.java`
- Test: `src/test/java/com/spdb/web/MessageFlowLogEntryControllerTest.java`

- [ ] **Step 1: Write failing controller tests**

Create `src/test/java/com/spdb/web/MessageFlowLogEntryControllerTest.java`:

```java
package com.spdb.web;

import com.spdb.message.MessageFlowLogEntryForm;
import com.spdb.message.MessageFlowLogService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MessageFlowLogEntryControllerTest {

    @Test
    void newPageAddsEmptyFormToModel() {
        MessageFlowLogService service = mock(MessageFlowLogService.class);
        MessageFlowLogController controller = new MessageFlowLogController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.newEntryPage(model);

        assertThat(view).isEqualTo("messages/flow-log-entry");
        assertThat(model.getAttribute("active")).isEqualTo("message-flow-log-entry");
        assertThat(model.getAttribute("form")).isEqualTo(MessageFlowLogEntryForm.empty());
    }

    @Test
    void validPostSavesAndRedirectsToQueryPage() {
        MessageFlowLogService service = mock(MessageFlowLogService.class);
        MessageFlowLogController controller = new MessageFlowLogController(service);
        MessageFlowLogEntryForm form = new MessageFlowLogEntryForm(
                "10.10.1.20", "0200202606220001", "PAY001", "JSON",
                1782090000000L, "GLOBAL-1", "TELLER009", "{\"amount\":\"88.00\"}",
                null, "", "", ""
        );

        String view = controller.createEntry(form, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/messages/flow-logs?query=0200202606220001");
        verify(service).saveEntry(form);
    }

    @Test
    void invalidPostReturnsFormWithError() {
        MessageFlowLogService service = mock(MessageFlowLogService.class);
        MessageFlowLogController controller = new MessageFlowLogController(service);
        MessageFlowLogEntryForm form = MessageFlowLogEntryForm.empty();
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.createEntry(form, model);

        assertThat(view).isEqualTo("messages/flow-log-entry");
        assertThat(model.getAttribute("active")).isEqualTo("message-flow-log-entry");
        assertThat(model.getAttribute("form")).isEqualTo(form);
        assertThat(model.getAttribute("error")).isEqualTo("来源IP、流水号、交易码、请求时间、请求报文不能为空");
        verifyNoInteractions(service);
    }
}
```

- [ ] **Step 2: Run controller tests to verify RED**

Run:

```bash
mvn -Dtest=MessageFlowLogEntryControllerTest test
```

Expected: compilation fails because `newEntryPage` and `createEntry` do not exist.

- [ ] **Step 3: Implement controller handlers**

Modify `src/main/java/com/spdb/web/MessageFlowLogController.java`:

Add imports:

```java
import com.spdb.message.MessageFlowLogEntryForm;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
```

Add methods:

```java
@GetMapping("/messages/flow-logs/new")
public String newEntryPage(Model model) {
    model.addAttribute("active", "message-flow-log-entry");
    model.addAttribute("form", MessageFlowLogEntryForm.empty());
    return "messages/flow-log-entry";
}

@PostMapping("/messages/flow-logs/new")
public String createEntry(@ModelAttribute MessageFlowLogEntryForm form, Model model) {
    if (!form.hasRequiredRequestFields()) {
        model.addAttribute("active", "message-flow-log-entry");
        model.addAttribute("form", form);
        model.addAttribute("error", "来源IP、流水号、交易码、请求时间、请求报文不能为空");
        return "messages/flow-log-entry";
    }
    messageFlowLogService.saveEntry(form);
    String encoded = URLEncoder.encode(form.cleanTransId(), StandardCharsets.UTF_8).replace("+", "%20");
    return "redirect:/messages/flow-logs?query=" + encoded;
}
```

- [ ] **Step 4: Run controller tests to verify GREEN**

Run:

```bash
mvn -Dtest=MessageFlowLogEntryControllerTest test
```

Expected: all controller tests pass.

- [ ] **Step 5: Commit Task 2**

```bash
git add src/main/java/com/spdb/web/MessageFlowLogController.java src/test/java/com/spdb/web/MessageFlowLogEntryControllerTest.java
git commit -m "Add message flow entry controller"
```

### Task 3: Template And Navigation

**Files:**
- Create: `src/main/resources/templates/messages/flow-log-entry.html`
- Modify: `src/main/resources/templates/fragments/layout.html`
- Create: `src/test/java/com/spdb/web/MessageFlowLogEntryTemplateTest.java`
- Modify: `src/test/java/com/spdb/web/LayoutTemplateTest.java`

- [ ] **Step 1: Write failing template and navigation tests**

Create `src/test/java/com/spdb/web/MessageFlowLogEntryTemplateTest.java`:

```java
package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFlowLogEntryTemplateTest {

    @Test
    void entryTemplateContainsSingleFormForRequestAndOptionalResponse() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/messages/flow-log-entry.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("action=\"/messages/flow-logs/new\"");
        assertThat(html).contains("name=\"sourceIp\"");
        assertThat(html).contains("name=\"transId\"");
        assertThat(html).contains("name=\"txnCode\"");
        assertThat(html).contains("name=\"txnTime\"");
        assertThat(html).contains("name=\"requestMessage\"");
        assertThat(html).contains("name=\"responseTime\"");
        assertThat(html).contains("name=\"returnCode\"");
        assertThat(html).contains("name=\"returnMsg\"");
        assertThat(html).contains("name=\"responseMessage\"");
        assertThat(html).contains("th:if=\"${error}\"");
    }
}
```

Update `LayoutTemplateTest.topbarKeepsFlatNavigationLinks()`:

```java
assertThat(html).contains("报文录入");
assertThat(html).contains("/messages/flow-logs/new");
```

- [ ] **Step 2: Run template tests to verify RED**

Run:

```bash
mvn -Dtest=MessageFlowLogEntryTemplateTest,LayoutTemplateTest test
```

Expected: `MessageFlowLogEntryTemplateTest` fails because the template does not exist, and `LayoutTemplateTest` fails because navigation is missing.

- [ ] **Step 3: Create the entry template**

Create `src/main/resources/templates/messages/flow-log-entry.html`:

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>报文录入</title>
  <link rel="stylesheet" href="/css/app.css">
</head>
<body>
<main class="shell layout-frame">
  <div th:replace="~{fragments/layout :: topbar(${active})}"></div>
  <div class="page-head">
    <div>
      <div class="eyebrow">Message Flow Entry</div>
      <h1>报文录入</h1>
      <p class="muted">新增请求报文和可选响应报文。</p>
    </div>
  </div>

  <section class="panel" th:if="${error}">
    <p class="error-text" th:text="${error}">保存失败</p>
  </section>

  <form class="panel" method="post" action="/messages/flow-logs/new" th:object="${form}">
    <div class="section-title">
      <div>
        <div class="eyebrow">Common</div>
        <h2>公共信息</h2>
      </div>
    </div>
    <div class="filter-grid">
      <div>
        <label>来源IP</label>
        <input name="sourceIp" th:value="*{sourceIp}" required>
      </div>
      <div>
        <label>流水号</label>
        <input name="transId" th:value="*{transId}" required>
      </div>
      <div>
        <label>交易码</label>
        <input name="txnCode" th:value="*{txnCode}" required>
      </div>
      <div>
        <label>报文类型</label>
        <input name="messageType" th:value="*{messageType}" placeholder="JSON / XML">
      </div>
    </div>

    <div class="section-title form-section-title">
      <div>
        <div class="eyebrow">Request</div>
        <h2>请求报文</h2>
      </div>
    </div>
    <div class="filter-grid">
      <div>
        <label>请求时间</label>
        <input name="txnTime" type="number" th:value="*{txnTime}" required>
      </div>
      <div>
        <label>全局流水</label>
        <input name="globalSeqNo" th:value="*{globalSeqNo}">
      </div>
      <div>
        <label>柜员号</label>
        <input name="tranTellerNo" th:value="*{tranTellerNo}">
      </div>
      <div class="span-4">
        <label>请求报文</label>
        <textarea name="requestMessage" rows="10" th:text="*{requestMessage}" required></textarea>
      </div>
    </div>

    <div class="section-title form-section-title">
      <div>
        <div class="eyebrow">Response</div>
        <h2>响应报文</h2>
      </div>
    </div>
    <div class="filter-grid">
      <div>
        <label>响应时间</label>
        <input name="responseTime" type="number" th:value="*{responseTime}">
      </div>
      <div>
        <label>返回码</label>
        <input name="returnCode" th:value="*{returnCode}">
      </div>
      <div class="span-2">
        <label>返回信息</label>
        <input name="returnMsg" th:value="*{returnMsg}">
      </div>
      <div class="span-4">
        <label>响应报文</label>
        <textarea name="responseMessage" rows="10" th:text="*{responseMessage}"></textarea>
      </div>
      <div class="actions span-4">
        <button class="btn primary" type="submit">保存</button>
        <a class="btn" href="/messages/flow-logs">取消</a>
      </div>
    </div>
  </form>
</main>
</body>
</html>
```

- [ ] **Step 4: Update navigation**

Modify `src/main/resources/templates/fragments/layout.html` and insert this link next to `报文查询`:

```html
<a th:classappend="${active == 'message-flow-log-entry'} ? 'active'" href="/messages/flow-logs/new">报文录入</a>
```

- [ ] **Step 5: Run template tests to verify GREEN**

Run:

```bash
mvn -Dtest=MessageFlowLogEntryTemplateTest,LayoutTemplateTest test
```

Expected: both tests pass.

- [ ] **Step 6: Commit Task 3**

```bash
git add src/main/resources/templates/messages/flow-log-entry.html src/main/resources/templates/fragments/layout.html src/test/java/com/spdb/web/MessageFlowLogEntryTemplateTest.java src/test/java/com/spdb/web/LayoutTemplateTest.java
git commit -m "Add message flow entry page"
```

### Task 4: Full Verification

**Files:**
- Verify all changed files.

- [ ] **Step 1: Run full test suite**

Run:

```bash
mvn test
```

Expected: build success with all tests passing.

- [ ] **Step 2: Check repository status**

Run:

```bash
git status --short
```

Expected: only intentionally untracked artifacts remain, such as `rose-service-report-export-no-tests.patch`; no unstaged implementation changes.

- [ ] **Step 3: Confirm implementation commits are complete**

Run:

```bash
git status --short
```

Expected: no unstaged or staged implementation files remain. The previously generated `rose-service-report-export-no-tests.patch` may still appear as an intentionally untracked artifact.
