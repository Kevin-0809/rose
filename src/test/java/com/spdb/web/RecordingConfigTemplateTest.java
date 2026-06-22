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
        assertThat(html).contains("recording.global_switch");
        assertThat(html).contains("value=\"true\"");
        assertThat(html).contains("value=\"false\"");
        assertThat(html).doesNotContain("global_recording_switch");
        String globalSwitchForm = html.substring(
                html.indexOf("<form class=\"panel form-grid\" method=\"post\" action=\"/config/recording/global\""),
                html.indexOf("</form>", html.indexOf("<form class=\"panel form-grid\" method=\"post\" action=\"/config/recording/global\""))
        );
        assertThat(globalSwitchForm).doesNotContain("value=\"1\"");
        assertThat(globalSwitchForm).doesNotContain("value=\"0\"");
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
