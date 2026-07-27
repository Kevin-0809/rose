package com.spdb.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
                .containsExactly("528失败/CCBS成功", "528成功/CCBS失败", "528失败/CCBS失败", "528失败/CCBS失败");
        assertThat(transactions).allSatisfy(row -> assertThat(row.get("problem_level")).isEqualTo("交易级"));
        assertThat(transactions).extracting(row -> row.get("problem_description")).containsExactly(
                "528错误码：O1；描述：orig one；CCBS错误码：D1；CCBS错误描述：dest one",
                "528错误码：O2；描述：orig two；CCBS错误码：D2；CCBS错误描述：dest two",
                "528错误码：O3；描述：orig three；CCBS错误码：D3；CCBS错误描述：dest three",
                "528错误码：O8；描述：；CCBS错误码：D8；CCBS错误描述：");
    }

    private void createSchema() {
        jdbc.execute("create table ana_tran_catalog (catalog_id bigint generated by default as identity primary key, tran_code varchar(32), service_code varchar(200), tran_name varchar(200), module_name varchar(100), owner varchar(100))");
        jdbc.execute("create table ana_field_mapping (tran_code varchar(32), service_code varchar(200), std_field_name varchar(200), field_cn_name varchar(200), sop_field_name varchar(200), soap_field_name varchar(200), bizjson_field_name varchar(200))");
        jdbc.execute("create table tss_tran_comp (mesg_seq varchar(64), orig_cdate varchar(8), conv_index integer, conv_cindex integer, dest_trcd varchar(200), comp_result varchar(1))");
        jdbc.execute("create table tss_field_comp (mesg_seq varchar(64), orig_cdate varchar(8), conv_index integer, conv_cindex integer, field_index integer, dest_trcd varchar(200), orig_field_name varchar(200), orig_field_value varchar(2000), dest_field_name varchar(200), dest_field_value varchar(2000))");
        jdbc.execute("create table tss_retcode_comp (mesg_seq varchar(64), orig_cdate varchar(8), service_code varchar(200), orig_error_code varchar(64), orig_error_desc varchar(500), dest_error_code varchar(64), dest_error_desc varchar(500))");
        jdbc.execute("create table ana_report_export_summary (batch_id varchar(64), report_date varchar(8), module_name varchar(100), covered_528_interface_count bigint, sent_transaction_count bigint, comp_result_1_count bigint, comp_result_2_count bigint, comp_result_3_count bigint, comp_result_4_count bigint, comp_result_8_count bigint, success_rate decimal(12,8), diff_528_field_count bigint)");
        jdbc.execute("create table ana_tran_diff_tracking_export (export_timestamp timestamp, source_batch_id varchar(64), business_date varchar(8), row_no bigint, service_code varchar(200), orig_error_code varchar(64), dest_error_code varchar(64), tran_code varchar(32), tran_name varchar(200), module_name varchar(100), orig_error_desc varchar(500), dest_error_desc varchar(500), transaction_owner varchar(100), tran_seq_no varchar(64), problem_level varchar(100), registration_date varchar(8), field_name varchar(500), problem_description varchar(2000))");
        jdbc.execute("create table ana_field_diff_tracking_export (export_timestamp timestamp, source_batch_id varchar(64), business_date varchar(8), row_no bigint, service_code varchar(200), tran_code varchar(32), tran_name varchar(200), module_name varchar(100), sop_field_name varchar(200), soap_field_name varchar(200), bizjson_field_name varchar(200), field_cn_name varchar(200), mapping_status varchar(32), orig_field_value varchar(2000), dest_field_value varchar(2000), transaction_owner varchar(100), tran_seq_no varchar(64), problem_level varchar(100), registration_date varchar(8), field_name varchar(500), problem_description varchar(2000))");
    }
}
