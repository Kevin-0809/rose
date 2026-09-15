package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationTranCodeCommandsTemplateTest {

    @Test
    void tranCodeCommandsTemplateContainsFormAndHistoryTable() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/migration/tran-code-commands.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("action=\"/migration/tran-code-commands\"");
        assertThat(html).contains("name=\"tranCodes\"");
        assertThat(html).contains("name=\"sampleSize\"");
        assertThat(html).contains("name=\"parallelism\"");
        assertThat(html).contains("name=\"collectionType\"");
        assertThat(html).contains("<select id=\"collectionType\"");
        assertThat(html).contains("required");
        assertThat(html).contains("账务");
        assertThat(html).contains("查询");
        assertThat(html).contains("name=\"baseDate\"");
        assertThat(html).doesNotContain("name=\"nearbyCollection\"");
        assertThat(html).doesNotContain("name=\"lookbackDays\"");
        assertThat(html).doesNotContain("回溯自然日数");
        assertThat(html).doesNotContain("value=\"10000\"");
        assertThat(html).contains("max=\"16\"");
        assertThat(html).contains("min=\"1\"");
        assertThat(html).contains("name=\"remark\"");
        assertThat(html).contains("<th>备注</th>");
        assertThat(html).contains("${row.remark}");
        assertThat(html).doesNotContain("${row.tranCodes}");
        assertThat(html).contains("${row.sampleSize}");
        assertThat(html).contains("${result.rows()}");
        assertThat(html).contains("fragments/layout :: pager(${result})");
    }
}
