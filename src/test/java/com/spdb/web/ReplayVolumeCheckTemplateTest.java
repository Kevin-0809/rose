package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayVolumeCheckTemplateTest {
    @Test
    void listTemplateContainsVolumeCheckSummaryAndActions() throws Exception {
        String html = new String(getClass().getResourceAsStream("/templates/config/replay-volume-check.html").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(html).contains("N").contains("100").contains("无交易量")
                .contains("有交易量").contains("待清理").contains("未映射").contains("状态").contains("migrationCommandId")
                .contains("确认执行").contains("history.rows()").contains("fragments/layout :: pager");
        assertThat(html).contains("createdTime").contains("cleanupServiceCount").contains("详情链接").contains("pendingCleanupRowCount");
        assertThat(html).contains("name=\"baseDate\"");
        assertThat(html).contains("name=\"collectionType\"");
        assertThat(html).contains("id=\"collection-type\"");
        assertThat(html).contains("账务").contains("查询");
        assertThat(html).contains("id=\"base-date\"");
        assertThat(html).contains("placeholder=\"yyyy-MM-dd\"");
        assertThat(html).contains("base-date-error");
        assertThat(html).contains("isValidDate");
        assertThat(html).contains("请填写铺底日期");
        assertThat(html).doesNotContain("type=\"date\"");
    }

    @Test
    void detailTemplateContainsPagedDetailSections() throws Exception {
        String html = new String(getClass().getResourceAsStream("/templates/config/replay-volume-check-detail.html").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(html).contains("无交易量").contains("待清理").contains("未映射")
                .contains("migrationCommandId").contains("tranName").contains("mappedTranCodes")
                .contains("actualCleanupRowCount").contains("fragments/layout :: pager")
                .contains("collectionType");
    }
}
