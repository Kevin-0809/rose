package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigImportTemplateTest {

    @Test
    void configImportPageContainsUploadPreviewAndConfirmControls() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/config/import.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("交易配置导入");
        assertThat(html).contains("multipart/form-data");
        assertThat(html).contains("name=\"file\"");
        assertThat(html).contains("multiple");
        assertThat(html).contains("name=\"serviceCode\"");
        assertThat(html).contains("预览");
        assertThat(html).contains("确认导入");
    }
}
