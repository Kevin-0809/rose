package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigImportTemplateTest {

    @Test
    void configImportPageContainsUploadAndConfirmControlsWithoutPreview() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/config/import.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("交易配置导入");
        assertThat(html).contains("multipart/form-data");
        assertThat(html).contains("name=\"file\"");
        assertThat(html).contains("multiple");
        assertThat(html).contains("name=\"serviceCode\"");
        assertThat(html).doesNotContain("/config/import/preview");
        assertThat(html).doesNotContain("预览");
        assertThat(html).doesNotContain("name=\"serviceCode\" required");
        assertThat(html).doesNotContain("文件名中含领域");
        assertThat(html).doesNotContain("文件名中含负责人");
        assertThat(html).doesNotContain("工作表名称");
        assertThat(html).contains("服务名称编号和服务操作名称拼接");
        assertThat(html).contains("确认导入");
        assertThat(html).contains("/config/import/list");
        assertThat(html).contains("name=\"listFile\"");
        assertThat(html).contains("清单导入");
        assertThat(html).contains("金融业务交易信息登记表");
        assertThat(html).contains("防腐528交易码");
    }
}
