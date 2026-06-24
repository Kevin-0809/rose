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
        assertThat(html).contains("type=\"datetime-local\"");
        assertThat(html).contains("id=\"timeFromPicker\"");
        assertThat(html).contains("id=\"timeToPicker\"");
        assertThat(html).contains("type=\"hidden\" name=\"timeFrom\"");
        assertThat(html).contains("type=\"hidden\" name=\"timeTo\"");
        assertThat(html).contains("Math.floor(new Date");
        assertThat(html).contains("name=\"windowSeconds\"");
        assertThat(html).contains("name=\"parallelism\"");
        assertThat(html).contains("action=\"/migration/commands\"");
        assertThat(html).contains("/migration/commands/");
        assertThat(html).contains("已迁移交易笔数");
        assertThat(html).contains("丢弃数");
        assertThat(html).contains("跳过数");
        assertThat(html).contains("progressText()");
        assertThat(html).contains("${result.rows()}");
        assertThat(html).contains("fragments/layout :: pager(${result})");
        assertThat(html).contains("源数据源");
        assertThat(html).contains("${sourceLabel}");
        assertThat(html).contains("${targetSchema}");
        assertThat(html).contains("th:text=\"'将 ' + ${sourceLabel}");
        assertThat(html).doesNotContain("${commands}");
        assertThat(html).doesNotContain("value=\"bxds\"");
        assertThat(html).doesNotContain("value=\"tss\"");
        assertThat(html).doesNotContain("将 bxds schema");
        assertThat(html).doesNotContain("至 tss schema");
    }
}
