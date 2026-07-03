package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SampleDetailTemplateTest {

    @Test
    void transactionDiffPageShowsReturnCodeDetailsAndExportAction() throws IOException {
        String html = template("/templates/samples/transaction-diffs.html");

        assertThat(html).contains("/samples/transaction-diffs/export");
        assertThat(html).contains("row.compResult()");
        assertThat(html).contains("row.origErrorDesc()");
        assertThat(html).contains("row.destErrorDesc()");
        assertThat(html).doesNotContain("name=\"sampleType\"");
    }

    @Test
    void fieldDiffPageShowsCompactSummaryAndDetailEntry() throws IOException {
        String html = template("/templates/samples/field-diffs.html");

        assertThat(html).contains("/samples/field-diffs/export");
        assertThat(html).doesNotContain("/samples/detail-fields/export");
        assertThat(html).contains("table-wrap table-wrap-wide");
        assertThat(html).contains("class=\"table-compact field-diff-table\"");
        assertThat(html).contains("ondblclick=\"location.href='/samples/field-diffs/' + this.dataset.resultId\"");
        assertThat(html).contains("th:attr=\"data-result-id=${row.resultId()}\"");
        assertThat(html).contains("class=\"col-batch\"");
        assertThat(html).contains("class=\"col-service\"");
        assertThat(html).contains("class=\"col-soap-field\"");
        assertThat(html).contains("class=\"col-action\"");
        assertThat(html).contains("th:href=\"@{/samples/field-diffs/{resultId}(resultId=${row.resultId()})}\"");
        assertThat(html).doesNotContain("class=\"col-date\"");
        assertThat(html).doesNotContain("class=\"col-status\"");
        assertThat(html).doesNotContain("row.mappingStatus()");
        assertThat(html).doesNotContain("row.sopFieldName()");
        assertThat(html).doesNotContain("row.bizjsonFieldName()");
        assertThat(html).contains("th:title=\"${row.soapFieldName()}\"");
        assertThat(html).contains("th:title=\"${row.fieldCnName()}\"");
        assertThat(html).doesNotContain("name=\"sampleType\"");
        assertThat(html).contains("row.soapFieldName()");
        assertThat(html).contains("row.fieldCnName()");
    }

    @Test
    void fieldDiffDetailPageShowsCompleteFieldDiffData() throws IOException {
        String html = template("/templates/samples/field-diff-detail.html");

        assertThat(html).contains("row.origCdate()");
        assertThat(html).contains("row.batchId()");
        assertThat(html).contains("row.tranCode()");
        assertThat(html).contains("row.serviceCode()");
        assertThat(html).contains("row.messageType()");
        assertThat(html).contains("row.sopFieldName()");
        assertThat(html).contains("row.soapFieldName()");
        assertThat(html).contains("row.bizjsonFieldName()");
        assertThat(html).contains("row.fieldCnName()");
        assertThat(html).contains("row.mappingStatus()");
        assertThat(html).contains("row.sampleTranSeqNo()");
        assertThat(html).contains("row.origFieldValue()");
        assertThat(html).contains("row.destFieldValue()");
        assertThat(html).contains("/samples/field-diffs");
    }

    @Test
    void detailPageShowsSampleTransactionsAndFieldDetailActions() throws IOException {
        String html = template("/templates/samples/details.html");

        assertThat(html).contains("row.messageType()");
        assertThat(html).contains("row.fieldCount()");
        assertThat(html).contains("/samples/detail-fields/export");
    }

    @Test
    void detailPageShowsReturnCodeDescriptions() throws IOException {
        String html = template("/templates/samples/details.html");

        assertThat(html).contains("row.origErrorDesc()");
        assertThat(html).contains("row.destErrorDesc()");
    }

    @Test
    void groupPageShowsSemanticFieldsAndStatuses() throws IOException {
        String html = template("/templates/samples/groups.html");

        assertThat(html).contains("row.semanticFieldNames()");
        assertThat(html).contains("row.messageTypes()");
        assertThat(html).contains("semanticFieldName");
    }

    private String template(String path) throws IOException {
        return new String(getClass().getResourceAsStream(path).readAllBytes(), StandardCharsets.UTF_8);
    }
}
