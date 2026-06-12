package com.spdb.sampling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SamplingBatchRunnerTest {
    private JdbcTemplate jdbc;
    private SamplingBatchRunner runner;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:sampling_batch_runner;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        jdbc = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
        runner = new SamplingBatchRunner(named, new JdbcTransactionManager(dataSource));
        createSchema();
        seedData();
    }

    @Test
    void semanticFieldDiffGroupsBizjsonAndSopSamplesTogether() {
        runner.run(command());

        Map<String, Object> summary = jdbc.queryForMap("select * from ana_sampling_summary where batch_id = 'BATCH_A825'");
        assertThat(summary.get("total_tran_count")).isEqualTo(4L);
        assertThat(summary.get("comp_result_1_count")).isEqualTo(1L);
        assertThat(summary.get("comp_result_2_count")).isEqualTo(1L);
        assertThat(summary.get("comp_result_4_count")).isEqualTo(2L);

        List<Map<String, Object>> fieldGroups = jdbc.queryForList("""
                select *
                from ana_sample_group
                where batch_id = 'BATCH_A825'
                  and sample_type = 'FIELD_DIFF'
                """);
        assertThat(fieldGroups).hasSize(1);
        Map<String, Object> fieldGroup = fieldGroups.get(0);
        assertThat(fieldGroup.get("semantic_field_names")).isEqualTo("currency_id,link_info");
        assertThat(fieldGroup.get("message_types")).isEqualTo("bizjson,sop");
        assertThat(fieldGroup.get("affected_tran_count")).isEqualTo(2L);
        assertThat(fieldGroup.get("affected_field_count")).isEqualTo(4L);

        List<String> detailSeqs = jdbc.queryForList("""
                select tran_seq_no
                from ana_sample_detail
                where group_id = ?
                order by sample_seq_no
                """, String.class, fieldGroup.get("group_id"));
        assertThat(detailSeqs).containsExactly("11111111111", "11111111114");

        List<String> rawFieldNames = jdbc.queryForList("""
                select raw_field_name
                from ana_sample_detail_field
                where group_id = ?
                order by mesg_seq, field_index
                """, String.class, fieldGroup.get("group_id"));
        assertThat(rawFieldNames).containsExactly("CurrencyId", "FcyCollCrspBnkLkg", "HUOBDH", "FAB251");

        Long tranResultGroups = jdbc.queryForObject("""
                select count(*)
                from ana_sample_group
                where batch_id = 'BATCH_A825'
                  and sample_type = 'TRAN_RESULT'
                """, Long.class);
        assertThat(tranResultGroups).isEqualTo(2L);
    }

    @Test
    void extractsGeneratedIdentityWhenDriverReturnsWholeInsertedRow() {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder(List.of(Map.of(
                "group_id", 9296274L,
                "batch_id", "SMP20260611-152859-6621",
                "sample_type", "FIELD_DIFF"
        )));

        Long key = SamplingBatchRunner.generatedLongKey(keyHolder, "group_id");

        assertThat(key).isEqualTo(9296274L);
    }

    private SamplingCommandRow command() {
        return new SamplingCommandRow(
                1L,
                "BATCH_A825",
                "20260611",
                null,
                null,
                null,
                "RUNNING",
                null,
                "0秒",
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                null,
                null,
                LocalDateTime.now(),
                null,
                null
        );
    }

    private void createSchema() {
        jdbc.execute("drop table if exists ana_sampling_summary");
        jdbc.execute("drop table if exists ana_sample_detail_field");
        jdbc.execute("drop table if exists ana_sample_detail");
        jdbc.execute("drop table if exists ana_sample_group");
        jdbc.execute("drop table if exists tss_retcode_comp");
        jdbc.execute("drop table if exists tss_field_comp");
        jdbc.execute("drop table if exists tss_tran_comp");
        jdbc.execute("drop table if exists ana_field_mapping");
        jdbc.execute("drop table if exists ana_tran_catalog");
        jdbc.execute("create table ana_tran_catalog (tran_code varchar(32), service_code varchar(200), tran_name varchar(200), module_name varchar(100), owner varchar(100))");
        jdbc.execute("create table ana_field_mapping (mapping_id bigint generated by default as identity primary key, tran_code varchar(32), service_code varchar(200), std_field_name varchar(200), field_cn_name varchar(200), sop_field_name varchar(200), soap_field_name varchar(200), bizjson_field_name varchar(200))");
        jdbc.execute("create table tss_tran_comp (mesg_seq varchar(64), orig_cdate varchar(8), conv_index integer, conv_cindex integer, comp_date varchar(8), dest_trcd varchar(200), orig_tran_res varchar(32), dest_tran_res varchar(32), comp_result varchar(1))");
        jdbc.execute("create table tss_field_comp (mesg_seq varchar(64), orig_cdate varchar(8), dest_trcd varchar(200), conv_index integer, conv_cindex integer, redo_index integer, field_index integer, field_file_flag varchar(32), orig_field_name varchar(200), orig_field_value varchar(2000), dest_field_name varchar(200), dest_field_value varchar(2000), comp_result varchar(1))");
        jdbc.execute("create table tss_retcode_comp (mesg_seq varchar(64), service_code varchar(200), orig_cdate varchar(8), orig_error_code varchar(64), orig_error_desc varchar(500), dest_error_code varchar(64), dest_error_desc varchar(500))");
        jdbc.execute("""
                create table ana_sample_group (
                    group_id bigint generated by default as identity primary key,
                    batch_id varchar(64), orig_cdate varchar(8), sample_type varchar(32),
                    group_key varchar(500), group_hash varchar(32), config_status varchar(32),
                    mapping_status varchar(32), semantic_signature varchar(2000),
                    semantic_signature_hash varchar(32), semantic_field_names varchar(1000),
                    message_types varchar(200), dest_trcd varchar(200), service_code varchar(200),
                    message_type varchar(32), tran_code varchar(32), comp_result varchar(1),
                    sop_field_name varchar(200), soap_field_name varchar(200), bizjson_field_name varchar(200),
                    field_cn_name varchar(200), orig_field_value varchar(2000), dest_field_value varchar(2000),
                    owner varchar(100), affected_count bigint, affected_tran_count bigint,
                    affected_field_count bigint, sample_count integer, reason varchar(1000)
                )
                """);
        jdbc.execute("""
                create table ana_sample_detail (
                    sample_id bigint generated by default as identity primary key,
                    group_id bigint, batch_id varchar(64), orig_cdate varchar(8), sample_type varchar(32),
                    sample_seq_no integer, config_status varchar(32), dest_trcd varchar(200),
                    service_code varchar(200), message_type varchar(32), tran_code varchar(32),
                    comp_result varchar(1), sop_field_name varchar(200), soap_field_name varchar(200),
                    bizjson_field_name varchar(200), field_cn_name varchar(200), orig_field_value varchar(2000),
                    dest_field_value varchar(2000), tran_seq_no varchar(64), owner varchar(100),
                    affected_count bigint, field_count integer, orig_error_code varchar(64),
                    orig_error_desc varchar(500), dest_error_code varchar(64), dest_error_desc varchar(500),
                    reason varchar(1000), source_table varchar(64), source_pk varchar(300)
                )
                """);
        jdbc.execute("""
                create table ana_sample_detail_field (
                    field_detail_id bigint generated by default as identity primary key,
                    sample_id bigint, group_id bigint, batch_id varchar(64), mesg_seq varchar(64),
                    message_type varchar(32), raw_field_name varchar(200), std_field_name varchar(200),
                    field_cn_name varchar(200), orig_field_value varchar(2000), dest_field_value varchar(2000),
                    mapping_status varchar(32), field_index integer
                )
                """);
        jdbc.execute("""
                create table ana_sampling_summary (
                    summary_id bigint generated by default as identity primary key,
                    batch_id varchar(64), orig_cdate varchar(8), total_tran_count bigint,
                    comp_result_1_count bigint, comp_result_2_count bigint, comp_result_3_count bigint,
                    comp_result_4_count bigint, comp_result_8_count bigint, pass_tran_count bigint,
                    tran_issue_count bigint, return_code_issue_count bigint, issue_field_count bigint,
                    field_diff_tran_count bigint, fully_matched_count bigint,
                    unconfigured_service_count bigint, unmapped_field_count bigint,
                    sample_group_count bigint, sample_detail_count bigint
                )
                """);
    }

    private void seedData() {
        jdbc.update("insert into ana_tran_catalog values ('A825', 'S030030014FcyCollCrspBnkLkgQry', '外币托收代理行联动查询', 'loan', '张伟')");
        jdbc.update("""
                insert into ana_field_mapping
                (tran_code, service_code, std_field_name, field_cn_name, sop_field_name, soap_field_name, bizjson_field_name)
                values
                ('A825', 'S030030014FcyCollCrspBnkLkgQry', 'currency_id', '币种', 'HUOBDH', 'CurrencyId', 'CurrencyId'),
                ('A825', 'S030030014FcyCollCrspBnkLkgQry', 'link_info', '联动信息', 'FAB251', 'FcyCollCrspBnkLkg', 'FcyCollCrspBnkLkg')
                """);
        jdbc.update("""
                insert into tss_tran_comp
                (mesg_seq, orig_cdate, conv_index, conv_cindex, comp_date, dest_trcd, orig_tran_res, dest_tran_res, comp_result)
                values
                ('11111111111', '20260611', 1, 1, '20260611', 'S030030014FcyCollCrspBnkLkgQry&bizjson', '2', '2', '4'),
                ('11111111114', '20260611', 1, 1, '20260611', 'S030030014FcyCollCrspBnkLkgQry&sop', '2', '2', '4'),
                ('22222222222', '20260611', 1, 1, '20260611', 'S030030014FcyCollCrspBnkLkgQry&bizjson', '2', '2', '1'),
                ('22222222223', '20260611', 1, 1, '20260611', 'S030030014FcyCollCrspBnkLkgQry&bizjson', '2', '2', '2')
                """);
        jdbc.update("""
                insert into tss_field_comp
                (mesg_seq, orig_cdate, dest_trcd, conv_index, conv_cindex, redo_index, field_index,
                 field_file_flag, orig_field_name, orig_field_value, dest_field_name, dest_field_value, comp_result)
                values
                ('11111111111', '20260611', 'S030030014FcyCollCrspBnkLkgQry&bizjson', 1, 1, null, 1, null, 'CurrencyId', '111', 'CurrencyId', '222', '0'),
                ('11111111111', '20260611', 'S030030014FcyCollCrspBnkLkgQry&bizjson', 1, 1, null, 2, null, 'FcyCollCrspBnkLkg', 'A1/B1', 'FcyCollCrspBnkLkg', 'A/B', '0'),
                ('11111111114', '20260611', 'S030030014FcyCollCrspBnkLkgQry&sop', 1, 1, null, 1, null, 'HUOBDH', '111', 'HUOBDH', '222', '0'),
                ('11111111114', '20260611', 'S030030014FcyCollCrspBnkLkgQry&sop', 1, 1, null, 2, null, 'FAB251', 'A1/B1', 'FAB251', 'A/B', '0')
                """);
        jdbc.update("""
                insert into tss_retcode_comp
                (mesg_seq, service_code, orig_cdate, orig_error_code, orig_error_desc, dest_error_code, dest_error_desc)
                values
                ('22222222222', 'S030030014FcyCollCrspBnkLkgQry&bizjson', '20260611', 'E0000', '账号不存在', '000000000000', '交易成功')
                """);
    }
}
