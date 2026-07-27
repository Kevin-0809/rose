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
                .contains("@{/diff-issues/{id}(id=${row.issueId()})}");
    }
}
