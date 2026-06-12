package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SampleDetailTemplateTest {

    @Test
    void detailPageShowsSampleTransactionsAndFieldDetailActions() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/samples/details.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("<th>报文类型</th><th>流水号</th><th>字段数</th>");
        assertThat(html).contains("row.messageType()");
        assertThat(html).contains("row.fieldCount()");
        assertThat(html).contains("/samples/detail-fields/export");
    }

    @Test
    void detailPageShowsReturnCodeDescriptions() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/samples/details.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("<th>528响应码</th><th>528响应描述</th><th>CCBS响应码</th><th>CCBS响应描述</th>");
        assertThat(html).contains("row.origErrorDesc()");
        assertThat(html).contains("row.destErrorDesc()");
    }

    @Test
    void groupPageShowsSemanticFieldsAndStatuses() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/samples/groups.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("<th>业务日期</th><th>批次</th><th>类型</th><th>配置状态</th><th>映射状态</th>");
        assertThat(html).contains("row.semanticFieldNames()");
        assertThat(html).contains("row.messageTypes()");
        assertThat(html).contains("semanticFieldName");
    }
}
