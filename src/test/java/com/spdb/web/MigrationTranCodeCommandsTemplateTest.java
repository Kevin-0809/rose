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
        assertThat(html).contains("name=\"lookbackDays\"");
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
