package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LayoutTemplateTest {

    @Test
    void topbarKeepsFlatNavigationLinks() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/fragments/layout.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).doesNotContain("nav-group");
        assertThat(html).doesNotContain("nav-menu");
        assertThat(html).contains("首页");
        assertThat(html).contains("采样指令");
        assertThat(html).contains("统计历史");
        assertThat(html).contains("交易级差异");
        assertThat(html).contains("字段级差异");
        assertThat(html).contains("/samples/transaction-diffs");
        assertThat(html).contains("/samples/field-diffs");
        assertThat(html).contains("交易配置");
        assertThat(html).contains("字段映射");
        assertThat(html).contains("交易导入");
        assertThat(html).contains("录制配置");
    }
}
