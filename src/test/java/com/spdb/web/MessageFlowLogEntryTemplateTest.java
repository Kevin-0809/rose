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
