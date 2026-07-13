package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationCommandsTemplateTest {

    @Test
    void commandsTemplateContainsFormAndTable() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/migration/commands.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("class=\"app-shell\"");
        assertThat(html).contains("fragments/layout :: sidebar(${active})");
        assertThat(html).contains("fragments/layout :: workspaceBar");
        assertThat(html).contains("fragments/layout :: sidebarScript");
        assertThat(html).contains("创建迁移指令");
        assertThat(html).contains("/vendor/flatpickr/flatpickr.min.css");
        assertThat(html).contains("/vendor/flatpickr/flatpickr.min.js");
        assertThat(html).contains("/vendor/flatpickr/l10n/zh.js");
        assertThat(html).contains("name=\"timeFrom\"");
        assertThat(html).contains("name=\"timeTo\"");
        assertThat(html).contains("flatpickr(fromPicker");
        assertThat(html).contains("enableTime: true");
        assertThat(html).contains("enableSeconds: true");
        assertThat(html).contains("id=\"timeFromPicker\"");
        assertThat(html).contains("id=\"timeToPicker\"");
        assertThat(html).contains("id=\"timeFromMillis\"");
        assertThat(html).contains("id=\"timeToMillis\"");
        assertThat(html).contains("pattern=\"[0-9]{1,3}\"");
        assertThat(html).contains("maxlength=\"3\"");
        assertThat(html).contains("class=\"datetime-millis-field\"");
        assertThat(html).contains("normalizeMillis");
        assertThat(html).contains("toEpochMillis");
        assertThat(html).contains("date.getTime() - date.getMilliseconds()");
        assertThat(html).contains("type=\"hidden\" name=\"timeFrom\"");
        assertThat(html).contains("type=\"hidden\" name=\"timeTo\"");
        assertThat(html).contains("fromValue.value = toEpochMillis(fromInstance, fromMillis)");
        assertThat(html).contains("时间戳转换");
        assertThat(html).contains("id=\"timestampConverterPicker\"");
        assertThat(html).contains("id=\"timestampConverterMillis\"");
        assertThat(html).contains("id=\"dateToLongButton\"");
        assertThat(html).contains("id=\"dateToLongResult\"");
        assertThat(html).contains("id=\"longToDateInput\"");
        assertThat(html).contains("id=\"longToDateButton\"");
        assertThat(html).contains("id=\"longToDateResult\"");
        assertThat(html).contains("formatDateTimeWithMillis");
        assertThat(html).contains("dateToLongResult.value = String(toEpochMillis(converterInstance, converterMillis))");
        assertThat(html).contains("longToDateResult.value = formatDateTimeWithMillis(epochMillis)");
        assertThat(html).doesNotContain("https://");
        assertThat(html).doesNotContain("cdn");
        assertThat(html).contains("name=\"windowSeconds\"");
        assertThat(html).contains("name=\"parallelism\"");
        assertThat(html).contains("action=\"/migration/commands\"");
        assertThat(html).contains("/migration/commands/");
        assertThat(html).contains("<th>起始时间</th><th>结束时间</th>");
        assertThat(html).contains("th:text=\"${row.timeFrom}\"");
        assertThat(html).contains("th:text=\"${row.timeTo}\"");
        assertThat(html).contains("已迁移交易笔数");
        assertThat(html).contains("丢弃数");
        assertThat(html).contains("跳过数");
        assertThat(html).contains("progressText()");
        assertThat(html).contains("${result.rows()}");
        assertThat(html).contains("fragments/layout :: pager(${result})");
        assertThat(html).contains("源数据源");
        assertThat(html).contains("目标数据源");
        assertThat(html).contains("${sourceDataSource}");
        assertThat(html).contains("${targetDataSource}");
        assertThat(html).contains("th:text=\"'将 ' + ${sourceDataSource}");
        assertThat(html).doesNotContain("${commands}");
        assertThat(html).doesNotContain("value=\"bxds\"");
        assertThat(html).doesNotContain("value=\"tss\"");
        assertThat(html).doesNotContain("将 bxds schema");
        assertThat(html).doesNotContain("至 tss schema");
        assertThat(html).doesNotContain("targetSchema");
        assertThat(html).doesNotContain("目标 schema");
    }

    @Test
    void sqlCommandsTemplateContainsIndependentSqlMigrationForm() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/migration/sql-commands.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("SQL迁移");
        assertThat(html).contains("action=\"/migration/sql-commands\"");
        assertThat(html).contains("name=\"responseSql\"");
        assertThat(html).contains("msg_flow_log_response");
        assertThat(html).contains("source_ip, trans_id");
        assertThat(html).contains("后台按 source_ip + trans_id 回查请求报文");
        assertThat(html).contains("/migration/commands/");
        assertThat(html).doesNotContain("/vendor/flatpickr");
        assertThat(html).doesNotContain("name=\"timeFrom\"");
        assertThat(html).doesNotContain("name=\"timeTo\"");
        assertThat(html).doesNotContain("name=\"requestSql\"");
    }
}
