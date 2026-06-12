package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HomeTemplateTest {

    @Test
    void homePageShowsSamplingSummaryMetrics() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/home.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("发起交易");
        assertThat(html).contains("原失败新成功");
        assertThat(html).contains("原成功新失败");
        assertThat(html).contains("都失败");
        assertThat(html).contains("都成功");
        assertThat(html).contains("响应码不一致");
        assertThat(html).contains("通过交易");
        assertThat(html).contains("交易问题");
        assertThat(html).contains("响应码问题");
        assertThat(html).contains("问题字段");
        assertThat(html).contains("字段差异流水");
        assertThat(html).contains("未配置服务");
        assertThat(html).contains("未映射字段");
        assertThat(html).contains("完全匹配");
        assertThat(html).contains("分组数");
        assertThat(html).contains("明细数");
        assertThat(html).contains("echarts");
        assertThat(html).contains("/vendor/echarts.min.js");
        assertThat(html).doesNotContain("cdn.jsdelivr");
        assertThat(html).contains("summary-history-chart");
        assertThat(html).contains("replay-flow-chart");
        assertThat(html).contains("replay-flow-canvas");
        assertThat(html).contains("flow-track production-track");
        assertThat(html).contains("recording-branch");
        assertThat(html).contains("recording-branch-pulse");
        assertThat(html).contains("flow-track record-track");
        assertThat(html).contains("flow-track ccbs-track");
        assertThat(html).contains("flow-track legacy-track");
        assertThat(html).contains("track-pulse");
        assertThat(html).contains("生产网络");
        assertThat(html).contains("回放网络");
        assertThat(html).doesNotContain("name: '生产网络', x:");
        assertThat(html).doesNotContain("name: '回放网络', x:");
        assertThat(html).contains("渠道");
        assertThat(html).contains("生产 ESB");
        assertThat(html).doesNotContain("flow-branch-origin");
        assertThat(html).doesNotContain("ESB旁路流量");
        assertThat(html).contains("生产流量发往 528");
        assertThat(html).contains("生产 ESB 旁路流量发往回放网络录制程序并落库");
        assertThat(html).contains("录制程序");
        assertThat(html).contains("DB");
        assertThat(html).contains("回放工具");
        assertThat(html).contains("ESF");
        assertThat(html).contains("CCBS");
        assertThat(html).contains("528");
        assertThat(html).doesNotContain("echarts.init(replayFlowEl)");
        assertThat(html).doesNotContain("effectScatter");
    }
}
