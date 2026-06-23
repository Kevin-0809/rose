package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationProgressTemplateTest {

    @Test
    void progressTemplateContainsOverviewAndShardTable() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/migration/progress.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("fragments/layout :: topbar");
        assertThat(html).contains("迁移进度");
        assertThat(html).contains("progress-bar");
        assertThat(html).contains("已迁移交易笔数");
        assertThat(html).contains("丢弃交易数");
        assertThat(html).contains("失败分片数");
        assertThat(html).contains("分片明细");
        assertThat(html).contains("/progress");
        assertThat(html).contains("setTimeout(pollProgress");
    }
}
