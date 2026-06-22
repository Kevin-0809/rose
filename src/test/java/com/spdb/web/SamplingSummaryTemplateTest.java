package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SamplingSummaryTemplateTest {

    @Test
    void summaryHistoryPageShowsChartAndPagedTable() throws IOException {
        String html = new String(
                java.nio.file.Files.readAllBytes(
                        java.nio.file.Path.of("src/main/resources/templates/sampling/summaries.html")
                ),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("采样统计历史");
        assertThat(html).contains("summary-history-chart");
        assertThat(html).contains("echarts");
        assertThat(html).contains("/vendor/echarts.min.js");
        assertThat(html).doesNotContain("cdn.jsdelivr");
        assertThat(html).contains("批次号");
        assertThat(html).contains("业务日期");
        assertThat(html).contains("交易问题");
        assertThat(html).contains("响应码问题");
        assertThat(html).contains("问题字段");
        assertThat(html).contains("字段差异流水");
        assertThat(html).contains("未配置服务");
        assertThat(html).contains("未映射字段");
        assertThat(html).contains("完全匹配");
        assertThat(html).contains("分组数");
        assertThat(html).contains("明细数");
        assertThat(html).contains("pager");
        assertThat(html).contains("/sampling/summaries/report/export");
        assertThat(html).contains("summary-actions");
        assertThat(html).contains("导出汇总Excel");
        assertThat(html).doesNotContain("导出领导汇报Excel");
    }
}
