package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SampleDetailTemplateTest {

    @Test
    void detailPageShowsMessageTypeBeforeFieldMappingColumns() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/samples/details.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("<th>报文类型</th><th>SOP字段名</th><th>SOAP字段名</th><th>BizJSON字段名</th>");
        assertThat(html).contains("row.messageType()");
        assertThat(html.indexOf("row.messageType()")).isLessThan(html.indexOf("row.sopFieldName()"));
    }

    @Test
    void detailPageShowsReturnCodeDescriptions() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/samples/details.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("<th>528响应码</th><th>528响应描述</th><th>CCBS响应码</th><th>CCBS响应描述</th>");
        assertThat(html).contains("row.origFieldDesc()");
        assertThat(html).contains("row.destFieldDesc()");
    }
}
