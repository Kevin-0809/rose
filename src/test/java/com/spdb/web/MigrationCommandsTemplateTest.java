package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationCommandsTemplateTest {

    @Test
    void commandsTemplateContainsFormAndTable() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/migration/commands.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("fragments/layout :: topbar");
        assertThat(html).contains("创建迁移指令");
        assertThat(html).contains("name=\"timeFrom\"");
        assertThat(html).contains("name=\"timeTo\"");
        assertThat(html).contains("name=\"windowSeconds\"");
        assertThat(html).contains("name=\"parallelism\"");
        assertThat(html).contains("action=\"/migration/commands\"");
        assertThat(html).contains("/migration/commands/");
        assertThat(html).contains("已迁移交易笔数");
        assertThat(html).contains("丢弃数");
        assertThat(html).contains("跳过数");
    }
}
