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
    }

    @Test
    void detailTemplateContainsPagedDetailSections() throws Exception {
        String html = new String(getClass().getResourceAsStream("/templates/config/replay-volume-check-detail.html").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(html).contains("无交易量").contains("待清理").contains("未映射")
                .contains("migrationCommandId").contains("tranName").contains("mappedTranCodes")
                .contains("actualCleanupRowCount").contains("fragments/layout :: pager");
    }
}
