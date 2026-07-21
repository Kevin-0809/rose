package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SampleDetailTemplateTest {

    @Test
    void transactionDiffPageShowsReturnCodeDetailsAndExportAction() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/samples/transaction-diffs.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("/samples/transaction-diffs/export");
        assertThat(html).doesNotContain("formaction=\"/samples/transaction-diffs/export\">瀵煎嚭Excel");
        assertThat(html).contains("row.origErrorCode()");
        assertThat(html).contains("row.destErrorCode()");
        assertThat(html).doesNotContain("name=\"sampleType\"");
        assertThat(html).contains("row.compResult()");
        assertThat(html).contains("row.origErrorDesc()");
        assertThat(html).contains("row.destErrorDesc()");
    }

    @Test
    void fieldDiffPageShowsMappedFieldNamesAndSingleCombinedExportAction() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/samples/field-diffs.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("/samples/field-diffs/export");
        assertThat(html).contains("导出ZIP");
        assertThat(html).doesNotContain("/samples/detail-fields/export");
        assertThat(html).contains("table-wrap table-wrap-wide");
        assertThat(html).contains("class=\"table-compact field-diff-table\"");
        assertThat(html).contains("data-field-diff-table");
        assertThat(html).contains("class=\"field-diff-row\"");
        assertThat(html).contains("data-detail-label=\"业务日期\"");
        assertThat(html).contains("data-detail-label=\"CCBS值\"");
        assertThat(html).contains("id=\"fieldDiffDialog\"");
        assertThat(html).contains("id=\"fieldDiffDetailList\"");
        assertThat(html).contains("addEventListener('dblclick'");
        assertThat(html).contains("class=\"col-batch\"");
        assertThat(html).contains("class=\"col-service\"");
        assertThat(html).contains("class=\"col-field-list\"");
        assertThat(html).contains("th:title=\"${row.sopFieldName()}\"");
        assertThat(html).contains("th:title=\"${row.soapFieldName()}\"");
        assertThat(html).contains("th:title=\"${row.bizjsonFieldName()}\"");
        assertThat(html).contains("th:title=\"${row.fieldCnName()}\"");
        assertThat(html).contains("class=\"cell-clip\" th:text=\"${row.sopFieldName()}\"");
        assertThat(html).contains("class=\"cell-clip\" th:text=\"${row.destFieldValue()}\"");
        assertThat(html).doesNotContain("name=\"sampleType\"");
        assertThat(html).contains("row.sopFieldName()");
        assertThat(html).contains("row.soapFieldName()");
        assertThat(html).contains("row.bizjsonFieldName()");
        assertThat(html).contains("row.fieldCnName()");
    }

    @Test
    void legacySampleGroupAndDetailTemplatesAreRemoved() {
        assertThat(Files.exists(Path.of("src/main/resources/templates/samples/groups.html"))).isFalse();
        assertThat(Files.exists(Path.of("src/main/resources/templates/samples/details.html"))).isFalse();
    }
}
