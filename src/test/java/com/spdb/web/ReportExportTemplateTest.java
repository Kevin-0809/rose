package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ReportExportTemplateTest {
    @Test
    void detailShowsIssueHistoryAndLinksToLedger() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/report-exports/detail.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html)
                .contains("历史出现次数")
                .contains("首次出现日期")
                .contains("上次出现日期")
                .contains("row.historicalOccurrenceCount()")
                .contains("row.firstSeenDate()")
                .contains("row.previousSeenDate()")
                .contains("@{/diff-issues/{id}(id=${row.issueId()})}")
                .contains("导出日报")
                .contains("导出日报（未脱敏）")
                .contains("@{/report-exports/{batchId}/daily(batchId=${command.batchId()})}")
                .contains("@{/report-exports/{batchId}/daily-raw(batchId=${command.batchId()})}");
    }

    @Test
    void commandsShowsDailyAndWeeklyExportActions() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/report-exports/commands.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html)
                .contains(">查看</a>")
                .contains("更多操作")
                .contains("class=\"action-menu\"")
                .contains("class=\"action-menu-list\"")
                .contains("导出日报")
                .contains("导出日报（未脱敏）")
                .contains("导出周报")
                .contains("@{/report-exports/{batchId}/daily(batchId=${row.batchId()})}")
                .contains("@{/report-exports/{batchId}/daily-raw(batchId=${row.batchId()})}")
                .contains("@{/report-exports/{batchId}/weekly(batchId=${row.batchId()})}")
                .doesNotContain("导出日报</a><a class=\"btn\"")
                .doesNotContain("业务日期")
                .doesNotContain("创建时间")
                .doesNotContain("row.reportDate()")
                .doesNotContain("row.createdTime()")
                .contains("colspan=\"7\"");
    }
}
