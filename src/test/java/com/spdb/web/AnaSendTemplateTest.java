package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AnaSendTemplateTest {

    @Test
    void anaSendPageShowsTpsCardAndPollingScript() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/messages/ana-send.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("实时 TPS");
        assertThat(html).contains("id=\"ana-tps\"");
        assertThat(html).contains("id=\"ana-pending\"");
        assertThat(html).contains("id=\"ana-sending\"");
        assertThat(html).contains("id=\"ana-success\"");
        assertThat(html).contains("id=\"ana-failed\"");
        assertThat(html).contains("/messages/ana-send/status");
        assertThat(html).contains("setInterval");
    }
}
