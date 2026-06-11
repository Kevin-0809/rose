package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingConfigTemplateTest {

    @Test
    void recordingConfigPageMaintainsGlobalSwitchAndTransactionRatio() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/config/recording.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("录制配置");
        assertThat(html).contains("全局录制开关");
        assertThat(html).contains("system_config");
        assertThat(html).contains("recording_config");
        assertThat(html).contains("txnCode");
        assertThat(html).contains("txnSwitch");
        assertThat(html).contains("recordRatio");
        assertThat(html).contains("录制比例");
        assertThat(html).contains("交易代码");
        assertThat(html).contains("启用");
        assertThat(html).contains("关闭");
        assertThat(html).contains("fragments/layout :: pager");
        assertThat(html).contains("/config/recording");
    }
}
