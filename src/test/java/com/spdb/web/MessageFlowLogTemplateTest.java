package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFlowLogTemplateTest {

    @Test
    void messageFlowLogPageShowsQueryFormAndRequestResponsePanels() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/messages/flow-logs.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("报文查询");
        assertThat(html).contains("name=\"query\"");
        assertThat(html).contains("请求报文");
        assertThat(html).contains("响应报文");
        assertThat(html).contains("data-format-message");
        assertThat(html).contains("formatJson");
        assertThat(html).contains("formatXml");
        assertThat(html).contains("row.requestMessage()");
        assertThat(html).contains("row.responseMessage()");
        assertThat(html).doesNotContain("导出");
        assertThat(html).doesNotContain("新增");
    }
}
