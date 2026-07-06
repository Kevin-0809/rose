package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SampleDetailTemplateTest {

    @Test
    void transactionDiffPageShowsReturnCodeDetailsAndExportAction() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/samples/transaction-diffs.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("交易级差异");
        assertThat(html).contains("/samples/transaction-diffs/export");
        assertThat(html).contains("<th>交易结果</th>");
        assertThat(html).contains("<th>528响应码</th><th>528响应描述</th><th>CCBS响应码</th><th>CCBS响应描述</th>");
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

        assertThat(html).contains("字段级差异");
        assertThat(html).contains("/samples/field-diffs/export");
        assertThat(html).doesNotContain("/samples/detail-fields/export");
        assertThat(html).contains("table-wrap table-wrap-wide");
        assertThat(html).contains("class=\"table-compact field-diff-table\"");
        assertThat(html).contains("class=\"col-batch\"");
        assertThat(html).contains("class=\"col-service\"");
        assertThat(html).contains("class=\"col-field-list\"");
        assertThat(html).contains("th:title=\"${row.sopFieldName()}\"");
        assertThat(html).contains("th:title=\"${row.soapFieldName()}\"");
        assertThat(html).contains("th:title=\"${row.bizjsonFieldName()}\"");
        assertThat(html).contains("th:title=\"${row.fieldCnName()}\"");
        assertThat(html).contains("SOP字段名");
        assertThat(html).contains("SOAP字段名");
        assertThat(html).contains("BizJSON字段名");
        assertThat(html).contains("字段中文名");
        assertThat(html).doesNotContain("name=\"sampleType\"");
        assertThat(html).contains("row.sopFieldName()");
        assertThat(html).contains("row.soapFieldName()");
        assertThat(html).contains("row.bizjsonFieldName()");
        assertThat(html).contains("row.fieldCnName()");
    }

    @Test
    void detailPageShowsSampleTransactionsAndFieldDetailActions() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/samples/details.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("<th>报文类型</th><th>流水号</th><th>字段数</th>");
        assertThat(html).contains("row.messageType()");
        assertThat(html).contains("row.fieldCount()");
        assertThat(html).contains("/samples/detail-fields/export");
    }

    @Test
    void detailPageShowsReturnCodeDescriptions() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/samples/details.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("<th>528响应码</th><th>528响应描述</th><th>CCBS响应码</th><th>CCBS响应描述</th>");
        assertThat(html).contains("row.origErrorDesc()");
        assertThat(html).contains("row.destErrorDesc()");
    }

    @Test
    void groupPageShowsSemanticFieldsAndStatuses() throws IOException {
        String html = new String(
                getClass().getResourceAsStream("/templates/samples/groups.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("<th>业务日期</th><th>批次</th><th>类型</th><th>配置状态</th><th>映射状态</th>");
        assertThat(html).contains("row.semanticFieldNames()");
        assertThat(html).contains("row.messageTypes()");
        assertThat(html).contains("semanticFieldName");
    }
}
