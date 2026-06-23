package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
        assertThat(html).contains("报文查询");
        assertThat(html).contains("报文录入");
        assertThat(html).contains("/samples/transaction-diffs");
        assertThat(html).contains("/samples/field-diffs");
        assertThat(html).contains("/messages/flow-logs");
        assertThat(html).contains("/messages/flow-logs/new");
        assertThat(html).contains("交易配置");
        assertThat(html).contains("字段映射");
        assertThat(html).contains("交易导入");
        assertThat(html).contains("录制配置");
        assertThat(html).contains("数据迁移");
        assertThat(html).contains("/migration/commands");
    }

    @Test
    void pagedTemplatesDoNotSubmitDuplicatePageParameters() throws Exception {
        List<String> templates = List.of(
                "/templates/config/fields.html",
                "/templates/config/recording.html",
                "/templates/config/trans.html",
                "/templates/samples/details.html",
                "/templates/samples/field-diffs.html",
                "/templates/samples/groups.html",
                "/templates/samples/transaction-diffs.html",
                "/templates/sampling/commands.html",
                "/templates/sampling/summaries.html"
        );

        for (String template : templates) {
            String html = new String(getClass().getResourceAsStream(template).readAllBytes(), StandardCharsets.UTF_8);

            assertThat(html)
                    .as(template)
                    .contains("fragments/layout :: pager")
                    .doesNotContain("name=\"page\" value=\"1\"");
        }
    }
}
