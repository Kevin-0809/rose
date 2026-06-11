package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TransTemplateTest {

    @Test
    void transactionRowsLinkToFilteredFieldMappings() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/config/trans.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("字段映射");
        assertThat(html).contains("@{/config/fields(tranCode=${row.tranCode},serviceCode=${row.serviceCode})}");
    }
}
