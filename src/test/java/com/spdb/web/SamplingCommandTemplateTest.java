package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SamplingCommandTemplateTest {

    @Test
    void commandPageUsesBusinessDateAndDoesNotExposeSamplingFilters() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/sampling/commands.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("业务日期");
        assertThat(html).contains("耗时");
        assertThat(html).contains("durationText()");
        assertThat(html).doesNotContain("执行ID");
        assertThat(html).doesNotContain("jobExecutionId()");
        assertThat(html).doesNotContain("name=\"sampleType\"");
        assertThat(html).doesNotContain("name=\"tranCode\"");
        assertThat(html).doesNotContain("name=\"serviceCode\"");
        assertThat(html).doesNotContain("name=\"partitionCount\"");
        assertThat(html).doesNotContain("分区数");
        assertThat(html).doesNotContain("orig_cdate");
        assertThat(html).doesNotContain("发起交易");
        assertThat(html).doesNotContain("原失败新成功");
        assertThat(html).doesNotContain("原成功新失败");
        assertThat(html).doesNotContain("都失败");
        assertThat(html).doesNotContain("都成功");
        assertThat(html).doesNotContain("响应码不一致");
        assertThat(html).doesNotContain("通过交易");
        assertThat(html).doesNotContain("问题字段");
        assertThat(html).doesNotContain("完全匹配");
        assertThat(html).doesNotContain("分组数");
        assertThat(html).doesNotContain("明细数");
    }
}
