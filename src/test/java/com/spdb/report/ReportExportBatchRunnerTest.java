package com.spdb.report;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportExportBatchRunnerTest {
    private JdbcTemplate jdbc;
    private ReportExportBatchRunner runner;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:report_export_runner_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        runner = new ReportExportBatchRunner(new NamedParameterJdbcTemplate(dataSource), new JdbcTransactionManager(dataSource));
        createSchema();
    }

    @Test
    void runLogsGenerationProgress() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            runner.run("BATCH-LOG", "20260729", LocalDateTime.of(2026, 7, 29, 10, 0));
        } finally {
            detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .contains("报表明细生成开始")
                    .contains("batchId=BATCH-LOG")
                    .contains("reportDate=20260729");
        });
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .contains("报表明细源数据加载完成")
                    .contains("catalogCount=")
                    .contains("transactionCount=");
        });
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .contains("报表明细生成完成")
                    .contains("batchId=BATCH-LOG")
                    .contains("elapsedMs=");
        });
    }

    @Test
    void materializesFieldMetadataAndMaskedDescriptionsAndTransactionIssueDetails() {
        jdbc.update("insert into ana_tran_catalog(tran_code, service_code, tran_name, module_name, owner) values ('T001', 'SVC1', '交易一', '模块一', '负责人')");
        jdbc.update("""
                insert into ana_field_mapping(tran_code, service_code, std_field_name, field_cn_name, sop_field_name, soap_field_name, bizjson_field_name)
                values ('T001', 'SVC1', 'amount', '金额', 'SOP_AMOUNT', 'Request.amount', 'biz.amount')
                """);
        jdbc.update("""
                insert into tss_field_comp values
                ('F1', '20260727', 1, 1, 1, 'SVC1&soap', 'Request.amount.extra', 'sensitive-source', 'ignored', ''),
                ('F2', '20260727', 1, 1, 2, 'SVC1&soap', 'unmapped', null, 'ignored', 'sensitive-destination')
                """);
        jdbc.update("""
                insert into tss_tran_comp values
                ('T1', '20260727', 1, 1, 'SVC1&soap', '1'),
                ('T2', '20260727', 1, 1, 'SVC1&soap', '2'),
                ('T3', '20260727', 1, 1, 'SVC1&soap', '3'),
                ('T8', '20260727', 1, 1, 'SVC1&soap', '8')
                """);
        jdbc.update("""
                insert into tss_retcode_comp values
                ('T1', '20260727', 'SVC1&soap', 'O1', 'orig one', 'D1', 'dest one'),
                ('T2', '20260727', 'SVC1&soap', 'O2', 'orig two', 'D2', 'dest two'),
                ('T3', '20260727', 'SVC1&soap', 'O3', 'orig three', 'D3', 'dest three'),
                ('T8', '20260727', 'SVC1&soap', 'O8', null, 'D8', null)
                """);

        runner.run("BATCH-1", "20260727", LocalDateTime.of(2026, 7, 27, 10, 0));

        List<Map<String, Object>> fields = jdbc.queryForList("""
                select sop_field_name, soap_field_name, bizjson_field_name, field_cn_name, problem_level, field_name, problem_description
                from ana_field_diff_tracking_export order by row_no
                """);
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0)).containsEntry("sop_field_name", "SOP_AMOUNT")
                .containsEntry("soap_field_name", "Request.amount")
                .containsEntry("bizjson_field_name", "biz.amount")
                .containsEntry("field_cn_name", "金额")
                .containsEntry("problem_level", "字段级")
                .containsEntry("field_name", "SOP_AMOUNT | Request.amount | biz.amount | 金额")
                .containsEntry("problem_description", "528：有值；CCBS：无值");
        assertThat(fields.get(1)).containsEntry("soap_field_name", "unmapped")
                .containsEntry("problem_level", "字段级")
                .containsEntry("field_name", "unmapped")
                .containsEntry("problem_description", "528：无值；CCBS：有值");
        assertThat(fields.get(0).get("problem_description").toString()).doesNotContain("sensitive-source");
        assertThat(fields.get(1).get("problem_description").toString()).doesNotContain("sensitive-destination");

        List<Map<String, Object>> transactions = jdbc.queryForList("""
                select field_name, problem_level, problem_description from ana_tran_diff_tracking_export order by row_no
                """);
        assertThat(transactions).extracting(row -> row.get("field_name"))
                .containsExactly("二者都失败响应码不一致", "二者都失败响应码不一致", "二者都失败响应码不一致", "二者都失败响应码不一致");
        assertThat(transactions).allSatisfy(row -> assertThat(row.get("problem_level")).isEqualTo("交易级"));
        assertThat(transactions).extracting(row -> row.get("problem_description")).containsExactly(
                "528错误码：O1；描述：orig one；CCBS错误码：D1；CCBS错误描述：dest one",
                "528错误码：O2；描述：orig two；CCBS错误码：D2；CCBS错误描述：dest two",
                "528错误码：O3；描述：orig three；CCBS错误码：D3；CCBS错误描述：dest three",
                "528错误码：O8；描述：；CCBS错误码：D8；CCBS错误描述：");
    }

    @Test
    void splitsBothFailedTransactionsByResponseCodeConsistencyInSummarySuccessRate() {
        jdbc.update("insert into ana_tran_catalog(tran_code, service_code, tran_name, module_name, owner) values ('T001', 'SVC1', '交易一', '支付', '负责人')");
        jdbc.update("""
                insert into tss_tran_comp values
                ('OK', '20260728', 1, 1, 'SVC1&soap', '4'),
                ('FAIL-SAME', '20260728', 2, 1, 'SVC1&soap', '3'),
                ('FAIL-DIFF', '20260728', 3, 1, 'SVC1&soap', '3'),
                ('ORIG-FAIL', '20260728', 4, 1, 'SVC1&soap', '1')
                """);
        jdbc.update("""
                insert into tss_retcode_comp values
                ('FAIL-SAME', '20260728', 'SVC1&soap', 'E1', 'orig failed', 'E1', 'dest failed'),
                ('FAIL-DIFF', '20260728', 'SVC1&soap', 'E2', 'orig failed', 'E3', 'dest failed'),
                ('ORIG-FAIL', '20260728', 'SVC1&soap', 'E4', 'orig failed', '000000000000', 'dest ok')
                """);

        runner.run("BATCH-SUMMARY-SPLIT", "20260728", LocalDateTime.of(2026, 7, 28, 10, 0));

        Map<String, Object> summary = jdbc.queryForMap("""
                select sent_transaction_count, comp_result_1_count, comp_result_3_count, comp_result_4_count,
                       comp_result_8_count, success_rate
                from ana_report_export_summary
                where batch_id = 'BATCH-SUMMARY-SPLIT' and module_name = '支付'
                """);
        assertThat(summary).containsEntry("sent_transaction_count", 4L)
                .containsEntry("comp_result_1_count", 1L)
                .containsEntry("comp_result_3_count", 1L)
                .containsEntry("comp_result_4_count", 1L)
                .containsEntry("comp_result_8_count", 1L);
        assertThat(summary.get("success_rate").toString()).isEqualTo("0.50000000");
    }

    @Test
    void persistsExtendedSummaryMetricsForFieldPassProblemsAndDuplicates() {
        jdbc.update("insert into ana_tran_catalog(tran_code, service_code, tran_name, module_name, owner) values ('T001', 'SVC1', '交易一', '支付', '负责人')");
        jdbc.update("""
                insert into tss_tran_comp values
                ('OK-FIELD-PASS', '20260728', 1, 1, 'SVC1&soap', '4'),
                ('OK-FIELD-DIFF', '20260728', 2, 1, 'SVC1&soap', '4'),
                ('FAIL-SAME', '20260728', 3, 1, 'SVC1&soap', '3'),
                ('FAIL-DIFF', '20260728', 4, 1, 'SVC1&soap', '3'),
                ('ORIG-FAIL', '20260728', 5, 1, 'SVC1&soap', '1')
                """);
        jdbc.update("""
                insert into tss_retcode_comp values
                ('FAIL-SAME', '20260728', 'SVC1&soap', 'E1', 'orig failed', 'E1', 'dest failed'),
                ('FAIL-DIFF', '20260728', 'SVC1&soap', 'E2', 'orig failed', 'E3', 'dest failed'),
                ('ORIG-FAIL', '20260728', 'SVC1&soap', 'E4', 'orig failed', '000000000000', 'dest ok')
                """);
        jdbc.update("""
                insert into tss_field_comp values
                ('OK-FIELD-DIFF', '20260728', 2, 1, 1, 'SVC1&soap', 'Request.amount', '100', 'ignored', '200')
                """);
        jdbc.update("""
                insert into ana_diff_issue(issue_key, issue_level, service_code, tran_code, tran_name, module_name,
                    transaction_owner, orig_error_code, dest_error_code, normalized_source_field_name,
                    problem_description, issue_status, first_seen_date, last_seen_date, first_seen_batch_id,
                    last_seen_batch_id, occurrence_batch_count)
                values
                ('TRAN|svc1|e4|000000000000', 'TRANSACTION', 'SVC1', 'T001', '交易一', '支付',
                    '负责人', 'E4', '000000000000', null, '历史交易问题', 'OPEN',
                    date '2026-07-20', date '2026-07-20', 'BATCH-OLD', 'BATCH-OLD', 1),
                ('FIELD|svc1|request.amount', 'FIELD', 'SVC1', 'T001', '交易一', '支付',
                    '负责人', null, null, 'request.amount', '历史字段问题', 'OPEN',
                    date '2026-07-20', date '2026-07-20', 'BATCH-OLD', 'BATCH-OLD', 1)
                """);

        runner.run("BATCH-EXTENDED-SUMMARY", "20260728", LocalDateTime.of(2026, 7, 28, 10, 0));

        Map<String, Object> summary = jdbc.queryForMap("""
                select sent_transaction_count, comp_result_1_count, comp_result_3_count, comp_result_4_count,
                       comp_result_8_count, field_pass_transaction_count, transaction_issue_count,
                       field_issue_count, issue_total_count, duplicate_issue_count, success_rate,
                       comparison_pass_rate
                from ana_report_export_summary
                where batch_id = 'BATCH-EXTENDED-SUMMARY' and module_name = '支付'
                """);
        assertThat(summary).containsEntry("sent_transaction_count", 5L)
                .containsEntry("comp_result_1_count", 1L)
                .containsEntry("comp_result_3_count", 1L)
                .containsEntry("comp_result_4_count", 2L)
                .containsEntry("comp_result_8_count", 1L)
                .containsEntry("field_pass_transaction_count", 1L)
                .containsEntry("transaction_issue_count", 2L)
                .containsEntry("field_issue_count", 1L)
                .containsEntry("issue_total_count", 3L)
                .containsEntry("duplicate_issue_count", 2L);
        assertThat(summary.get("success_rate").toString()).isEqualTo("0.60000000");
        assertThat(summary.get("comparison_pass_rate").toString()).isEqualTo("0.40000000");
        assertThat(jdbc.queryForObject("""
                select count(*)
                  from ana_diff_issue
                 where issue_key = 'TRAN|svc1|e1|e1'
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*)
                  from ana_tran_diff_tracking_export
                 where source_batch_id = 'BATCH-EXTENDED-SUMMARY'
                   and orig_error_code = 'E1'
                   and dest_error_code = 'E1'
                """, Long.class)).isZero();
    }

    @Test
    void classifiesTransactionStatesAndResponseCodes() {
        jdbc.update("""
                insert into tss_tran_comp values
                ('S0', '20260727', 1, 1, 'SVC0&soap', '0'),
                ('S5', '20260727', 1, 1, 'SVC5&soap', '5'),
                ('S6', '20260727', 1, 1, 'SVC6&soap', '6'),
                ('S7', '20260727', 1, 1, 'SVC7&soap', '7'),
                ('S4', '20260727', 1, 1, 'SVC4&soap', '4'),
                ('OK', '20260727', 1, 1, 'SVCOK&soap', '1'),
                ('OF', '20260727', 1, 1, 'SVC-OF&soap', '1'),
                ('FO', '20260727', 1, 1, 'SVC-FO&soap', '2'),
                ('FF-D', '20260727', 1, 1, 'SVC-FFD&soap', '3'),
                ('FF-S', '20260727', 1, 1, 'SVC-FFS&soap', '8'),
                ('MISSING', '20260727', 1, 1, 'SVC-MISSING&soap', '1'),
                ('EMPTY', '20260727', 1, 1, 'SVC-EMPTY&soap', '1'),
                ('NULL-EMPTY', '20260727', 1, 1, 'SVC-NULL-EMPTY&soap', '1')
                """);
        jdbc.update("""
                insert into tss_retcode_comp values
                ('OK', '20260727', 'SVCOK&soap', 'AAAAAAA', '528 ok', '000000000000', 'ccbs ok'),
                ('OF', '20260727', 'SVC-OF&soap', 'AAAAAAA', '528 ok', 'E1', 'ccbs failed'),
                ('FO', '20260727', 'SVC-FO&soap', 'E2', '528 failed', '000000000000', 'ccbs ok'),
                ('FF-D', '20260727', 'SVC-FFD&soap', 'E3', '528 failed', 'E4', 'ccbs failed'),
                ('FF-S', '20260727', 'SVC-FFS&soap', 'E5', '528 failed', 'E5', 'ccbs failed'),
                ('EMPTY', '20260727', 'SVC-EMPTY&soap', '', '528 empty', '', 'ccbs empty'),
                ('NULL-EMPTY', '20260727', 'SVC-NULL-EMPTY&soap', null, '528 null', '', 'ccbs empty')
                """);

        runner.run("BATCH-STATES", "20260727", LocalDateTime.of(2026, 7, 27, 10, 0));

        List<Map<String, Object>> transactions = jdbc.queryForList("""
                select tran_seq_no, orig_error_code, dest_error_code, orig_error_desc, dest_error_desc, field_name
                from ana_tran_diff_tracking_export order by row_no
                """);
        assertThat(transactions).hasSize(10);
        assertThat(transactions).extracting(row -> row.get("tran_seq_no")).doesNotContain("FF-S");
        List<Map<String, Object>> comparableTransactions = transactions.stream()
                .filter(row -> !"FF-S".equals(row.get("tran_seq_no")))
                .toList();
        assertThat(comparableTransactions).extracting(row -> row.get("tran_seq_no"), row -> row.get("orig_error_code"), row -> row.get("dest_error_code"),
                        row -> row.get("orig_error_desc"), row -> row.get("dest_error_desc"), row -> row.get("field_name"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("S0", "未比对", "未比对", "未比对", "未比对", "未比对"),
                        org.assertj.core.groups.Tuple.tuple("S5", "忽略比对", "忽略比对", "忽略比对", "忽略比对", "忽略比对"),
                        org.assertj.core.groups.Tuple.tuple("S6", "比对中", "比对中", "比对中", "比对中", "比对中"),
                        org.assertj.core.groups.Tuple.tuple("S7", "对比异常", "对比异常", "对比异常", "对比异常", "对比异常"),
                        org.assertj.core.groups.Tuple.tuple("OF", "AAAAAAA", "E1", "528 ok", "ccbs failed", "528成功ccbs失败"),
                        org.assertj.core.groups.Tuple.tuple("FO", "E2", "000000000000", "528 failed", "ccbs ok", "528失败ccbs成功"),
                        org.assertj.core.groups.Tuple.tuple("FF-D", "E3", "E4", "528 failed", "ccbs failed", "二者都失败响应码不一致"),
                        org.assertj.core.groups.Tuple.tuple("MISSING", null, null, null, null, "二者都失败响应码不一致"),
                        org.assertj.core.groups.Tuple.tuple("EMPTY", "", "", "528 empty", "ccbs empty", "二者都失败响应码不一致"),
                        org.assertj.core.groups.Tuple.tuple("NULL-EMPTY", null, "", "528 null", "ccbs empty", "二者都失败响应码不一致"));
    }

    @Test
    void keepsOneTransactionDetailWhenTheSameBatchRunsAgain() {
        jdbc.update("insert into tss_tran_comp values ('T1', '20260727', 1, 1, 'SVC1&soap', '1')");
        jdbc.update("insert into tss_retcode_comp values ('T1', '20260727', 'SVC1&soap', 'O1', 'orig', 'D1', 'dest')");

        runner.run("BATCH-IDEMPOTENT", "20260727", LocalDateTime.of(2026, 7, 27, 10, 0));
        runner.run("BATCH-IDEMPOTENT", "20260727", LocalDateTime.of(2026, 7, 27, 10, 0));

        assertThat(jdbc.queryForObject("""
                select count(*) from ana_tran_diff_tracking_export
                where source_batch_id = 'BATCH-IDEMPOTENT'
                  and service_code = 'SVC1'
                  and orig_error_code = 'O1'
                  and dest_error_code = 'D1'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from ana_report_export_summary where batch_id = 'BATCH-IDEMPOTENT'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void usesMergeForTransactionDetailInsert() throws Exception {
        var method = ReportExportBatchRunner.class.getDeclaredMethod("transactionDetailInsertSql");
        method.setAccessible(true);

        String sql = (String) method.invoke(runner);

        assertThat(sql).contains("merge into ana_tran_diff_tracking_export as target")
                .contains("using (")
                .contains("when not matched then")
                .doesNotContain("on conflict")
                .doesNotContain("where not exists");
    }

    @Test
    void keepsOneFieldDetailWhenTheSameBatchRunsAgain() {
        jdbc.update("insert into tss_field_comp values ('F1', '20260727', 1, 1, 1, 'SVC1&soap', 'Request.amount', 'source', 'ignored', 'destination')");

        runner.run("BATCH-FIELD-IDEMPOTENT", "20260727", LocalDateTime.of(2026, 7, 27, 10, 0));
        runner.run("BATCH-FIELD-IDEMPOTENT", "20260727", LocalDateTime.of(2026, 7, 27, 10, 0));

        assertThat(jdbc.queryForObject("""
                select count(*) from ana_field_diff_tracking_export
                where source_batch_id = 'BATCH-FIELD-IDEMPOTENT'
                  and service_code = 'SVC1'
                  and issue_key = 'FIELD|svc1|request.amount'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void snapshotsAffectedTransactionCountByDistinctMesgSeqPerIssueKey() {
        jdbc.update("insert into ana_tran_catalog(tran_code, service_code, tran_name, module_name, owner) values ('T001', 'SVC1', 'Transaction One', 'Payment', 'Owner')");
        jdbc.update("""
                insert into tss_tran_comp values
                ('TX-1', '20260727', 1, 1, 'SVC1&soap', '1'),
                ('TX-1', '20260727', 2, 1, 'SVC1&soap', '1'),
                ('TX-2', '20260727', 1, 1, 'SVC1&soap', '1'),
                ('TX-3', '20260727', 1, 1, 'SVC1&soap', '1')
                """);
        jdbc.update("""
                insert into tss_retcode_comp values
                ('TX-1', '20260727', 'SVC1&soap', 'E1', 'orig', 'E2', 'dest'),
                ('TX-2', '20260727', 'SVC1&soap', 'E1', 'orig', 'E2', 'dest'),
                ('TX-3', '20260727', 'SVC1&soap', 'E9', 'orig', 'E8', 'dest')
                """);
        jdbc.update("""
                insert into tss_field_comp values
                ('FX-1', '20260727', 1, 1, 1, 'SVC1&soap', 'Request.amount.extra', '100', 'ignored', '200'),
                ('FX-1', '20260727', 1, 1, 2, 'SVC1&soap', 'Request.amount.other', '101', 'ignored', '201'),
                ('FX-2', '20260727', 1, 1, 1, 'SVC1&soap', 'Request.amount.extra', '102', 'ignored', '202')
                """);

        runner.run("BATCH-AFFECTED", "20260727", LocalDateTime.of(2026, 7, 27, 10, 0));

        assertThat(jdbc.queryForObject("""
                select affected_tran_count
                  from ana_tran_diff_tracking_export
                 where source_batch_id = 'BATCH-AFFECTED'
                   and issue_key = 'TRAN|svc1|e1|e2'
                """, Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                select affected_tran_count
                  from ana_field_diff_tracking_export
                 where source_batch_id = 'BATCH-AFFECTED'
                   and issue_key = 'FIELD|svc1|request.amount'
                """, Long.class)).isEqualTo(2L);
    }

    @Test
    void trimsFieldSuffixAfterUnderscoreWithinNormalizedTwoPartFieldName() {
        jdbc.update("""
                insert into ana_field_mapping(tran_code, service_code, std_field_name, field_cn_name, sop_field_name, soap_field_name, bizjson_field_name)
                values ('T001', 'SVC1', 'amount', '金额', 'SOP_AMOUNT', 'Request.amount', 'biz.amount')
                """);
        jdbc.update("insert into tss_field_comp values ('F1', '20260727', 1, 1, 1, 'SVC1&soap', 'Request.amount_001.extra', 'source', 'ignored', 'destination')");

        runner.run("BATCH-FIELD-NORMALIZED", "20260727", LocalDateTime.of(2026, 7, 27, 10, 0));

        Map<String, Object> field = jdbc.queryForMap("""
                select soap_field_name, mapping_status, issue_key
                from ana_field_diff_tracking_export
                where source_batch_id = 'BATCH-FIELD-NORMALIZED'
                """);
        assertThat(field).containsEntry("soap_field_name", "Request.amount")
                .containsEntry("mapping_status", "MAPPED")
                .containsEntry("issue_key", "FIELD|svc1|request.amount");
    }

    @Test
    void usesMergeForFieldDetailInsert() throws Exception {
        var method = ReportExportBatchRunner.class.getDeclaredMethod("fieldDetailInsertSql");
        method.setAccessible(true);

        String sql = (String) method.invoke(runner);

        assertThat(sql).contains("merge into ana_field_diff_tracking_export as target")
                .contains("using (")
                .contains("when not matched then")
                .doesNotContain("on conflict")
                .doesNotContain("where not exists");
    }

    @Test
    void streamsTransactionDetailsOncePerNormalizedServiceCode() {
        jdbc.update("""
                insert into tss_tran_comp values
                ('A1', '20260727', 1, 1, 'SVC-A&soap', '1'),
                ('A2', '20260727', 2, 1, 'SVC-A&bizjson', '1'),
                ('A3', '20260727', 3, 1, 'SVC-A&missing-one', '1'),
                ('A4', '20260727', 4, 1, 'SVC-A&missing-two', '1'),
                ('B1', '20260727', 1, 1, 'SVC-B&soap', '2')
                """);
        jdbc.update("""
                insert into tss_retcode_comp values
                ('A1', '20260727', 'SVC-A&soap', 'OA1', 'orig', 'DA1', 'dest'),
                ('A2', '20260727', 'SVC-A&bizjson', 'OA2', 'orig', 'DA2', 'dest'),
                ('B1', '20260727', 'SVC-B&soap', 'OB1', 'orig', 'DB1', 'dest')
                """);
        AtomicInteger submittedTasks = new AtomicInteger();
        Executor countingExecutor = command -> {
            submittedTasks.incrementAndGet();
            command.run();
        };
        runner = new ReportExportBatchRunner(new NamedParameterJdbcTemplate(jdbc.getDataSource()),
                new JdbcTransactionManager(jdbc.getDataSource()), countingExecutor);

        runner.run("BATCH-STREAM", "20260727", LocalDateTime.of(2026, 7, 27, 10, 0));

        assertThat(submittedTasks).hasValue(2);
        assertThat(jdbc.queryForObject("select count(*) from ana_tran_diff_tracking_export where source_batch_id = 'BATCH-STREAM'", Long.class))
                .isEqualTo(4L);
        assertThat(jdbc.queryForObject("""
                select affected_tran_count
                  from ana_tran_diff_tracking_export
                 where source_batch_id = 'BATCH-STREAM'
                   and issue_key = 'TRAN|svc-a||'
                """, Long.class)).isEqualTo(2L);
    }

    @Test
    void cleansCurrentBatchArtifactsWhenTransactionStreamingFails() {
        jdbc.update("insert into ana_tran_catalog(tran_code, service_code, tran_name, module_name, owner) values ('T001', 'SVC1', '交易一', '模块一', '负责人')");
        jdbc.update("insert into tss_field_comp values ('F1', '20260727', 1, 1, 1, 'SVC1&soap', 'amount.extra', 'source', 'ignored', '')");
        jdbc.update("insert into tss_tran_comp values ('T1', '20260727', 1, 1, 'SVC1&soap', '1')");
        Executor failingExecutor = command -> {
            throw new IllegalStateException("stream failed");
        };
        runner = new ReportExportBatchRunner(new NamedParameterJdbcTemplate(jdbc.getDataSource()),
                new JdbcTransactionManager(jdbc.getDataSource()), failingExecutor);

        assertThatThrownBy(() -> runner.run("BATCH-FAIL", "20260727", LocalDateTime.of(2026, 7, 27, 10, 0)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("stream failed");

        assertThat(jdbc.queryForObject("select count(*) from ana_report_export_summary where batch_id = 'BATCH-FAIL'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ana_field_diff_tracking_export where source_batch_id = 'BATCH-FAIL'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ana_tran_diff_tracking_export where source_batch_id = 'BATCH-FAIL'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ana_diff_issue", Long.class)).isZero();
    }

    @Test
    void rollsBackLedgerUpdatesWhenSummaryIssueMetricUpdateFails() {
        jdbc.update("insert into ana_tran_catalog(tran_code, service_code, tran_name, module_name, owner) values ('T001', 'SVC1', '交易一', '支付', '负责人')");
        jdbc.update("insert into tss_tran_comp values ('ORIG-FAIL', '20260728', 1, 1, 'SVC1&soap', '1')");
        jdbc.update("insert into tss_retcode_comp values ('ORIG-FAIL', '20260728', 'SVC1&soap', 'E4', 'orig failed', '000000000000', 'dest ok')");
        jdbc.update("""
                insert into ana_diff_issue(issue_key, issue_level, service_code, tran_code, tran_name, module_name,
                    transaction_owner, orig_error_code, dest_error_code, normalized_source_field_name,
                    problem_description, issue_status, first_seen_date, last_seen_date, first_seen_batch_id,
                    last_seen_batch_id, occurrence_batch_count)
                values ('TRAN|svc1|e4|000000000000', 'TRANSACTION', 'SVC1', 'T001', '交易一', '支付',
                    '负责人', 'E4', '000000000000', null, '历史交易问题', 'OPEN',
                    date '2026-07-20', date '2026-07-20', 'BATCH-OLD', 'BATCH-OLD', 1)
                """);
        jdbc.execute("alter table ana_report_export_summary drop column duplicate_issue_count");

        assertThatThrownBy(() -> runner.run("BATCH-ROLLBACK", "20260728", LocalDateTime.of(2026, 7, 28, 10, 0)))
                .isInstanceOf(RuntimeException.class);

        Map<String, Object> issue = jdbc.queryForMap("""
                select occurrence_batch_count, last_seen_batch_id
                  from ana_diff_issue
                 where issue_key = 'TRAN|svc1|e4|000000000000'
                """);
        assertThat(issue).containsEntry("occurrence_batch_count", 1L)
                .containsEntry("last_seen_batch_id", "BATCH-OLD");
        assertThat(jdbc.queryForObject("select count(*) from ana_report_export_summary where batch_id = 'BATCH-ROLLBACK'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ana_tran_diff_tracking_export where source_batch_id = 'BATCH-ROLLBACK'", Long.class)).isZero();
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReportExportBatchRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(ReportExportBatchRunner.class);
        logger.detachAppender(appender);
    }

    private void createSchema() {
        jdbc.execute("create table ana_tran_catalog (catalog_id bigint generated by default as identity primary key, tran_code varchar(32), service_code varchar(200), tran_name varchar(200), module_name varchar(100), owner varchar(100))");
        jdbc.execute("create table ana_field_mapping (tran_code varchar(32), service_code varchar(200), std_field_name varchar(200), field_cn_name varchar(200), sop_field_name varchar(200), soap_field_name varchar(200), bizjson_field_name varchar(200))");
        jdbc.execute("create table tss_tran_comp (mesg_seq varchar(64), orig_cdate varchar(8), conv_index integer, conv_cindex integer, dest_trcd varchar(200), comp_result varchar(1))");
        jdbc.execute("create table tss_field_comp (mesg_seq varchar(64), orig_cdate varchar(8), conv_index integer, conv_cindex integer, field_index integer, dest_trcd varchar(200), orig_field_name varchar(200), orig_field_value varchar(2000), dest_field_name varchar(200), dest_field_value varchar(2000))");
        jdbc.execute("create table tss_retcode_comp (mesg_seq varchar(64), orig_cdate varchar(8), service_code varchar(200), orig_error_code varchar(64), orig_error_desc varchar(500), dest_error_code varchar(64), dest_error_desc varchar(500))");
        jdbc.execute("create table ana_report_export_summary (batch_id varchar(64), report_date varchar(8), module_name varchar(100), covered_528_interface_count bigint, sent_transaction_count bigint, comp_result_1_count bigint, comp_result_2_count bigint, comp_result_3_count bigint, comp_result_4_count bigint, comp_result_8_count bigint, success_rate decimal(12,8), diff_528_field_count bigint, field_pass_transaction_count bigint, comparison_pass_rate decimal(12,8), transaction_issue_count bigint, field_issue_count bigint, issue_total_count bigint, duplicate_issue_count bigint, constraint uk_ana_report_export_summary unique (batch_id, module_name))");
        jdbc.execute("create table ana_diff_issue (issue_id bigint generated by default as identity primary key, issue_key varchar(600) unique not null, issue_level varchar(16) not null, service_code varchar(200) not null, tran_code varchar(32), tran_name varchar(200), module_name varchar(100), transaction_owner varchar(100), orig_error_code varchar(64), dest_error_code varchar(64), normalized_source_field_name varchar(500), problem_type varchar(100), problem_description varchar(2000), preliminary_analysis varchar(2000), final_solution varchar(2000), issue_status varchar(16) not null, coordination_required varchar(100), resolver varchar(100), resolution_date date, defect_fix_date date, first_seen_date date not null, last_seen_date date not null, first_seen_batch_id varchar(64) not null, last_seen_batch_id varchar(64) not null, occurrence_batch_count bigint not null, created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp)");
        jdbc.execute("create table ana_tran_diff_tracking_export (export_timestamp timestamp, source_batch_id varchar(64), business_date varchar(8), row_no bigint, service_code varchar(200), orig_error_code varchar(64), dest_error_code varchar(64), tran_code varchar(32), tran_name varchar(200), module_name varchar(100), orig_error_desc varchar(500), dest_error_desc varchar(500), transaction_owner varchar(100), tran_seq_no varchar(64), problem_level varchar(100), registration_date varchar(8), field_name varchar(500), problem_description varchar(2000), issue_id bigint, issue_key varchar(600), affected_tran_count bigint default 0, historical_occurrence_count bigint default 0, first_seen_date date, previous_seen_date date, problem_type varchar(100), preliminary_analysis varchar(2000), final_solution varchar(2000), coordination_required varchar(100), resolver varchar(100), resolution_date varchar(8), defect_fix_date varchar(8), constraint uk_ana_tran_diff_tracking_export_batch_issue unique (source_batch_id, service_code, orig_error_code, dest_error_code))");
        jdbc.execute("create table ana_field_diff_tracking_export (export_timestamp timestamp, source_batch_id varchar(64), business_date varchar(8), row_no bigint, service_code varchar(200), tran_code varchar(32), tran_name varchar(200), module_name varchar(100), sop_field_name varchar(200), soap_field_name varchar(200), bizjson_field_name varchar(200), field_cn_name varchar(200), mapping_status varchar(32), orig_field_value varchar(2000), dest_field_value varchar(2000), transaction_owner varchar(100), tran_seq_no varchar(64), problem_level varchar(100), registration_date varchar(8), field_name varchar(500), problem_description varchar(2000), issue_id bigint, issue_key varchar(600), affected_tran_count bigint default 0, historical_occurrence_count bigint default 0, first_seen_date date, previous_seen_date date, problem_type varchar(100), preliminary_analysis varchar(2000), final_solution varchar(2000), coordination_required varchar(100), resolver varchar(100), resolution_date varchar(8), defect_fix_date varchar(8))");
    }

}
