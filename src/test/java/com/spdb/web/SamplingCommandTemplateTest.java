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
        assertThat(html).contains("class=\"command-table\"");
        assertThat(html).contains("class=\"col-batch\"");
        assertThat(html).contains("class=\"col-error\"");
        assertThat(html).contains("class=\"col-actions\"");
        assertThat(html).contains("command-actions");
        assertThat(html).contains("class=\"cell-clip\"");
        assertThat(html).doesNotContain("执行ID");
        assertThat(html).doesNotContain("jobExecutionId()");
        assertThat(html).doesNotContain("name=\"sampleType\"");
        assertThat(html).doesNotContain("name=\"tranCode\"");
        assertThat(html).doesNotContain("name=\"serviceCode\"");
        assertThat(html).doesNotContain("name=\"partitionCount\"");
        assertThat(html).doesNotContain("分区数");
        assertThat(html).doesNotContain("orig_cdate");
        assertThat(html).doesNotContain("交易问题");
        assertThat(html).doesNotContain("响应码问题");
        assertThat(html).doesNotContain("字段差异流水");
        assertThat(html).doesNotContain("未配置服务");
        assertThat(html).doesNotContain("未映射字段");
        assertThat(html).doesNotContain("tranIssueCount()");
        assertThat(html).doesNotContain("returnCodeIssueCount()");
        assertThat(html).doesNotContain("fieldDiffTranCount()");
        assertThat(html).doesNotContain("unconfiguredServiceCount()");
        assertThat(html).doesNotContain("unmappedFieldCount()");
    }
}
