package com.spdb.sample;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.spdb.web.PageRequestParams;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SampleQueryServiceTest {

    @Test
    void queriesFieldDiffsByTransactionAndFieldDimension() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:sample_query_service;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSemanticSampleTables(jdbc);
        seedSemanticSampleRows(jdbc);
        SampleQueryService service = new SampleQueryService(new NamedParameterJdbcTemplate(dataSource));

        SampleSearchCriteria criteria = new SampleSearchCriteria(
                "BATCH_A825",
                "20260611",
                "FIELD_DIFF",
                "A825",
                "S030030014FcyCollCrspBnkLkgQry",
                "bizjson",
                "CONFIGURED",
                "MAPPED",
                "CurrencyId",
                null,
                null
        );

        var result = service.fieldDiffs(criteria, PageRequestParams.of(1, 20));
        assertThat(result.total()).isEqualTo(1L);
        SampleFieldDiffRow row = result.rows().get(0);
        assertThat(row.origCdate()).isEqualTo("20260611");
        assertThat(row.batchId()).isEqualTo("BATCH_A825");
        assertThat(row.tranCode()).isEqualTo("A825");
        assertThat(row.serviceCode()).isEqualTo("S030030014FcyCollCrspBnkLkgQry");
        assertThat(row.messageType()).isEqualTo("bizjson");
        assertThat(row.sopFieldName()).isEqualTo("HUOBDH");
        assertThat(row.soapFieldName()).isEqualTo("CurrencyId");
        assertThat(row.bizjsonFieldName()).isEqualTo("CurrencyId");
        assertThat(row.fieldCnName()).isEqualTo("币种");
        assertThat(row.mappingStatus()).isEqualTo("MAPPED");
        assertThat(row.sampleTranSeqNo()).isEqualTo("11111111111");
        assertThat(row.origFieldValue()).isEqualTo("111");
        assertThat(row.destFieldValue()).isEqualTo("222");
        assertThat(row.owner()).isEqualTo("张伟");
        assertThat(row.affectedTranCount()).isEqualTo(2L);

        List<SampleDetailFieldRow> fields = service.detailFields(101L, PageRequestParams.of(1, 20)).rows();
        assertThat(fields).hasSize(1);
        assertThat(fields)
                .extracting(SampleDetailFieldRow::rawFieldName)
                .containsExactly("CurrencyId");
        assertThat(fields)
                .extracting(SampleDetailFieldRow::stdFieldName)
                .containsExactly("currency_id");

        List<SampleFieldDiffRow> exportRows = new java.util.ArrayList<>();
        service.streamFieldDiffExport(criteria, exportRows::add);
        assertThat(exportRows).hasSize(1);
        SampleFieldDiffRow exportRow = exportRows.get(0);
        assertThat(exportRow.origCdate()).isEqualTo("20260611");
        assertThat(exportRow.tranCode()).isEqualTo("A825");
        assertThat(exportRow.serviceCode()).isEqualTo("S030030014FcyCollCrspBnkLkgQry");
        assertThat(exportRow.sampleTranSeqNo()).isEqualTo("11111111111");
        assertThat(exportRow.sopFieldName()).isEqualTo("HUOBDH");
        assertThat(exportRow.soapFieldName()).isEqualTo("CurrencyId");
        assertThat(exportRow.origFieldValue()).isEqualTo("111");
        assertThat(exportRow.destFieldValue()).isEqualTo("222");
        assertThat(exportRow.affectedTranCount()).isEqualTo(2L);

        SampleSearchCriteria unfilteredFieldCriteria = new SampleSearchCriteria(
                "BATCH_A825",
                "20260611",
                "FIELD_DIFF",
                "A825",
                "S030030014FcyCollCrspBnkLkgQry",
                "bizjson",
                "CONFIGURED",
                "MAPPED",
                null,
                null,
                null
        );
        List<SampleFieldDiffRow> unfilteredExportRows = new java.util.ArrayList<>();
        service.streamFieldDiffExport(unfilteredFieldCriteria, unfilteredExportRows::add);
        assertThat(unfilteredExportRows).hasSize(2);
        assertThat(unfilteredExportRows)
                .extracting(SampleFieldDiffRow::fieldCnName)
                .containsExactly("币种", "联动信息");
    }

    @Test
    void exportGroupsLimitsQueryToOneMillionRows() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.<SampleGroupRow>query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        SampleQueryService service = new SampleQueryService(jdbc);

        service.exportGroups(emptyCriteria());

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.contains("limit :exportLimit"),
                org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(params -> exportLimit(params) == 1_000_000),
                any(RowMapper.class)
        );
    }

    @Test
    void exportDetailsLimitsQueryToOneMillionRows() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.<SampleDetailRow>query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        SampleQueryService service = new SampleQueryService(jdbc);

        service.exportDetails(emptyCriteria());

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.contains("limit :exportLimit"),
                org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(params -> exportLimit(params) == 1_000_000),
                any(RowMapper.class)
        );
    }

    @Test
    void streamGroupsLimitsQueryToOneMillionRows() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SampleQueryService service = new SampleQueryService(jdbc);

        service.streamGroups(emptyCriteria(), row -> {
        });

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.contains("limit :exportLimit"),
                org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(params -> exportLimit(params) == 1_000_000),
                any(RowCallbackHandler.class)
        );
    }

    @Test
    void streamDetailsLimitsQueryToOneMillionRows() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SampleQueryService service = new SampleQueryService(jdbc);

        service.streamDetails(emptyCriteria(), row -> {
        });

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.contains("limit :exportLimit"),
                org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(params -> exportLimit(params) == 1_000_000),
                any(RowCallbackHandler.class)
        );
    }

    @Test
    void streamTransactionDiffExportDeduplicatesRowsByTransaction() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:transaction_diff_export;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSemanticSampleTables(jdbc);
        seedDuplicateTransactionDiffRows(jdbc);
        SampleQueryService service = new SampleQueryService(new NamedParameterJdbcTemplate(dataSource));

        List<SampleDetailRow> rows = new java.util.ArrayList<>();
        service.streamTransactionDiffExport(new SampleSearchCriteria(
                "BATCH_TX", "20260611", "RETURN_CODE", null, null, null, null, null, null, null, null
        ), rows::add);

        assertThat(rows).hasSize(1);
        SampleDetailRow row = rows.get(0);
        assertThat(row.tranSeqNo()).isEqualTo("SEQ_DUP");
        assertThat(row.tranCode()).isEqualTo("A825");
        assertThat(row.compResult()).isEqualTo("8");
        assertThat(row.origErrorCode()).isEqualTo("E1,E2");
        assertThat(row.origErrorDesc()).isEqualTo("错误1,错误2");
        assertThat(row.destErrorCode()).isEqualTo("C1,C2");
        assertThat(row.destErrorDesc()).isEqualTo("错误A,错误B");
        assertThat(row.affectedCount()).isEqualTo(1L);
    }

    @Test
    void transactionDiffsDeduplicatePagedRowsByTransactionAndIgnoreBlankRetcodeRows() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:transaction_diff_page;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSemanticSampleTables(jdbc);
        seedDuplicateTransactionDiffRows(jdbc);
        SampleQueryService service = new SampleQueryService(new NamedParameterJdbcTemplate(dataSource));

        var result = service.transactionDiffs(new SampleSearchCriteria(
                "BATCH_TX", "20260611", "RETURN_CODE", null, null, null, null, null, null, null, null
        ), PageRequestParams.of(1, 20));

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.rows()).hasSize(1);
        SampleDetailRow row = result.rows().get(0);
        assertThat(row.tranSeqNo()).isEqualTo("SEQ_DUP");
        assertThat(row.origErrorCode()).isEqualTo("E1,E2");
        assertThat(row.destErrorCode()).isEqualTo("C1,C2");
    }

    @Test
    void streamDetailFieldsLimitsQueryToOneMillionRows() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SampleQueryService service = new SampleQueryService(jdbc);

        service.streamDetailFields(emptyCriteria(), row -> {
        });

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.contains("limit :exportLimit"),
                org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(params -> exportLimit(params) == 1_000_000),
                any(RowCallbackHandler.class)
        );
    }

    @Test
    void streamFieldDiffExportUsesJoinedStreamingQueryAndLimitsToOneMillionRows() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SampleQueryService service = new SampleQueryService(jdbc);

        service.streamFieldDiffExport(emptyCriteria(), row -> {
        });

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("from ana_field_diff_result r")
                                && sql.contains("limit :exportLimit")
                ),
                org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(params -> exportLimit(params) == 1_000_000),
                any(RowCallbackHandler.class)
        );
    }

    @Test
    void streamTransactionDiffExportLimitsQueryToOneMillionRows() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SampleQueryService service = new SampleQueryService(jdbc);

        service.streamTransactionDiffExport(emptyCriteria(), row -> {
        });

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("from ana_tran_diff_result r")
                                && sql.contains("limit :exportLimit")
                ),
                org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(params -> exportLimit(params) == 1_000_000),
                any(RowCallbackHandler.class)
        );
    }

    @Test
    void detailQueriesReadMaterializedSampleTablesWithoutMappingOrRawRetcodeJoins() {
        String source = javaSource("SampleQueryService.java");

        assertThat(source).contains("from ana_sample_detail d");
        assertThat(source).contains("from ana_sample_detail_field f");
        assertThat(source).contains("semantic_field_names");
        assertThat(source).contains("config_status");
        assertThat(source).contains("mapping_status");
        assertThat(source).doesNotContain("left join ana_field_mapping m");
        assertThat(source).doesNotContain("left join tss_retcode_comp r");
    }

    @Test
    void streamServiceReportAggregatesCountsByServiceCodeWithTotalAsRateDenominator() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:service_report;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSemanticSampleTables(jdbc);
        createServiceReportSourceTables(jdbc);
        seedServiceReportRows(jdbc);
        assertThat(jdbc.queryForObject("select count(*) from tss_tran_comp where orig_cdate = '20260611'", Long.class))
                .isEqualTo(6L);
        SampleQueryService service = new SampleQueryService(new NamedParameterJdbcTemplate(dataSource));

        List<SamplingServiceReportRow> rows = new java.util.ArrayList<>();
        service.streamServiceReport(new SamplingSummarySearchCriteria("BATCH_RPT", "20260611"), rows::add);

        assertThat(rows).hasSize(2);
        SamplingServiceReportRow s001 = rows.get(0);
        assertThat(s001.batchId()).isEqualTo("BATCH_RPT");
        assertThat(s001.origCdate()).isEqualTo("20260611");
        assertThat(s001.tranCode()).isEqualTo("A001");
        assertThat(s001.serviceCode()).isEqualTo("S001");
        assertThat(s001.tranName()).isEqualTo("交易一");
        assertThat(s001.owner()).isEqualTo("张三");
        assertThat(s001.totalTranCount()).isEqualTo(5L);
        assertThat(s001.compResult1Count()).isEqualTo(1L);
        assertThat(s001.compResult2Count()).isEqualTo(1L);
        assertThat(s001.compResult3Count()).isEqualTo(1L);
        assertThat(s001.compResult4Count()).isEqualTo(1L);
        assertThat(s001.compResult8Count()).isEqualTo(1L);
        assertThat(s001.passTranCount()).isEqualTo(1L);
        assertThat(s001.tranIssueCount()).isEqualTo(2L);
        assertThat(s001.returnCodeIssueCount()).isEqualTo(1L);
        assertThat(s001.fieldDiffTranCount()).isEqualTo(1L);
        assertThat(s001.fullyMatchedCount()).isEqualTo(0L);
        assertThat(s001.issueFieldCount()).isEqualTo(2L);
        assertThat(s001.rate(s001.returnCodeIssueCount())).isEqualTo(0.2d);

        SamplingServiceReportRow s002 = rows.get(1);
        assertThat(s002.serviceCode()).isEqualTo("S002");
        assertThat(s002.totalTranCount()).isEqualTo(1L);
        assertThat(s002.compResult4Count()).isEqualTo(1L);
        assertThat(s002.fullyMatchedCount()).isEqualTo(1L);
    }

    @Test
    void streamServiceReportUsesBatchBusinessDateWhenOnlyBatchIdIsProvided() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:service_report_batch_only;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSemanticSampleTables(jdbc);
        createServiceReportSourceTables(jdbc);
        createSamplingSummaryTable(jdbc);
        seedServiceReportRows(jdbc);
        jdbc.update("insert into ana_sampling_summary (batch_id, orig_cdate) values ('BATCH_RPT', '20260611')");
        jdbc.update("""
                insert into tss_tran_comp (
                    mesg_seq, orig_cdate, dest_trcd, comp_result
                ) values ('SEQ_OTHER_DATE', '20260612', 'S999&bizjson', '4')
                """);
        SampleQueryService service = new SampleQueryService(new NamedParameterJdbcTemplate(dataSource));

        List<SamplingServiceReportRow> rows = new java.util.ArrayList<>();
        service.streamServiceReport(new SamplingSummarySearchCriteria("BATCH_RPT", null), rows::add);

        assertThat(rows).hasSize(2);
        assertThat(rows)
                .extracting(SamplingServiceReportRow::serviceCode)
                .containsExactly("S001", "S002");
    }

    @Test
    void streamTransactionSuccessStatsNormalizesArrayFieldsAndAggregatesConfiguredFields() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:transaction_success_stats;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createTransactionSuccessStatTables(jdbc);
        seedTransactionSuccessStatRows(jdbc);
        SampleQueryService service = new SampleQueryService(new NamedParameterJdbcTemplate(dataSource));

        List<TransactionSuccessStatRow> rows = new java.util.ArrayList<>();
        service.streamTransactionSuccessStats(new SampleSearchCriteria(
                "BATCH_SUCCESS", "20260703", "RETURN_CODE", null, null, null, null, null, null, null, null
        ), rows::add);

        assertThat(rows).hasSize(1);
        TransactionSuccessStatRow row = rows.get(0);
        assertThat(row.origCdate()).isEqualTo("20260703");
        assertThat(row.batchId()).isEqualTo("BATCH_SUCCESS");
        assertThat(row.tranCode()).isEqualTo("C000");
        assertThat(row.serviceCode()).isEqualTo("aaa");
        assertThat(row.messageType()).isEqualTo("bzjson");
        assertThat(row.successCount()).isEqualTo(100L);
        assertThat(row.interfaceFieldCount()).isEqualTo(2L);
        assertThat(row.comparedFieldCount()).isEqualTo(200L);
        assertThat(row.diffFieldCount()).isEqualTo(2L);
        assertThat(row.comparedFieldDiffCount()).isEqualTo(2L);
        assertThat(row.highRatioFieldCount()).isEqualTo(2L);
        assertThat(row.lowRatioFieldCount()).isEqualTo(0L);
        assertThat(row.moduleName()).isEqualTo("存款,负债");
        assertThat(row.owner()).isEqualTo("张三,李四");
    }

    private int exportLimit(SqlParameterSource params) {
        assertThat(params.hasValue("exportLimit")).isTrue();
        return ((Number) params.getValue("exportLimit")).intValue();
    }

    private SampleSearchCriteria emptyCriteria() {
        return new SampleSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);
    }

    private void createSemanticSampleTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                create table ana_tran_diff_result (
                    result_id bigint primary key,
                    batch_id varchar(64), orig_cdate varchar(8), tran_code varchar(32),
                    service_code varchar(200), message_type varchar(32), sample_tran_seq_no varchar(64),
                    orig_error_code varchar(64), orig_error_desc varchar(500),
                    dest_error_code varchar(64), dest_error_desc varchar(500),
                    owner varchar(100), affected_tran_count bigint
                )
                """);
        jdbc.execute("""
                create table ana_field_diff_result (
                    result_id bigint primary key,
                    batch_id varchar(64), orig_cdate varchar(8), tran_code varchar(32),
                    service_code varchar(200), message_type varchar(32), message_types varchar(200),
                    sop_field_name varchar(200), soap_field_name varchar(200),
                    bizjson_field_name varchar(200), field_cn_name varchar(200),
                    mapping_status varchar(32), sample_tran_seq_no varchar(64),
                    orig_field_value varchar(2000), dest_field_value varchar(2000),
                    owner varchar(100), affected_tran_count bigint
                )
                """);
        jdbc.execute("""
                create table ana_sample_group (
                    group_id bigint primary key,
                    batch_id varchar(64), orig_cdate varchar(8), sample_type varchar(32),
                    config_status varchar(32), mapping_status varchar(32),
                    semantic_signature varchar(2000), semantic_signature_hash varchar(32),
                    semantic_field_names varchar(1000), message_types varchar(200),
                    dest_trcd varchar(200), service_code varchar(200), message_type varchar(32),
                    tran_code varchar(32), comp_result varchar(1), sop_field_name varchar(200),
                    owner varchar(100), affected_count bigint, affected_tran_count bigint,
                    affected_field_count bigint, sample_count integer, reason varchar(1000)
                )
                """);
        jdbc.execute("""
                create table ana_sample_detail (
                    sample_id bigint primary key,
                    group_id bigint, batch_id varchar(64), orig_cdate varchar(8), sample_type varchar(32),
                    sample_seq_no integer, config_status varchar(32), dest_trcd varchar(200),
                    service_code varchar(200), message_type varchar(32), tran_code varchar(32),
                    comp_result varchar(1), sop_field_name varchar(200), soap_field_name varchar(200),
                    bizjson_field_name varchar(200), field_cn_name varchar(200), tran_seq_no varchar(64),
                    owner varchar(100), affected_count bigint, field_count integer,
                    orig_error_code varchar(64), orig_error_desc varchar(500),
                    dest_error_code varchar(64), dest_error_desc varchar(500),
                    reason varchar(1000), source_table varchar(64), source_pk varchar(300)
                )
                """);
        jdbc.execute("""
                create table ana_sample_detail_field (
                    field_detail_id bigint primary key,
                    sample_id bigint, group_id bigint, batch_id varchar(64), mesg_seq varchar(64),
                    message_type varchar(32), raw_field_name varchar(200), std_field_name varchar(200),
                    field_cn_name varchar(200), orig_field_value varchar(2000),
                    dest_field_value varchar(2000), mapping_status varchar(32), field_index integer
                )
                """);
    }

    private void createServiceReportSourceTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                create table tss_tran_comp (
                    mesg_seq varchar(64) primary key,
                    orig_cdate varchar(8),
                    dest_trcd varchar(200),
                    comp_result varchar(1)
                )
                """);
        jdbc.execute("""
                create table ana_tran_catalog (
                    tran_code varchar(32),
                    service_code varchar(200),
                    tran_name varchar(200),
                    module_name varchar(100),
                    owner varchar(100)
                )
                """);
    }

    private void createSamplingSummaryTable(JdbcTemplate jdbc) {
        jdbc.execute("""
                create table ana_sampling_summary (
                    batch_id varchar(64),
                    orig_cdate varchar(8)
                )
                """);
    }

    private void createTransactionSuccessStatTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                create table tss_tran_comp (
                    mesg_seq varchar(64),
                    orig_cdate varchar(8),
                    dest_trcd varchar(200),
                    comp_result varchar(1)
                )
                """);
        jdbc.execute("""
                create table tss_field_comp (
                    mesg_seq varchar(64),
                    orig_cdate varchar(8),
                    comp_result varchar(1),
                    orig_field_name varchar(200),
                    dest_field_name varchar(200),
                    conv_index integer,
                    conv_cindex integer
                )
                """);
        jdbc.execute("""
                create table ana_field_mapping (
                    tran_code varchar(32),
                    service_code varchar(200),
                    std_field_name varchar(200),
                    sop_field_name varchar(200),
                    soap_field_name varchar(200),
                    bizjson_field_name varchar(200)
                )
                """);
        jdbc.execute("""
                create table ana_tran_catalog (
                    tran_code varchar(32),
                    service_code varchar(200),
                    tran_name varchar(200),
                    module_name varchar(100),
                    owner varchar(100)
                )
                """);
        jdbc.execute("""
                create table ana_sampling_summary (
                    batch_id varchar(64),
                    orig_cdate varchar(8)
                )
                """);
    }

    private void seedTransactionSuccessStatRows(JdbcTemplate jdbc) {
        jdbc.update("insert into ana_sampling_summary (batch_id, orig_cdate) values ('BATCH_SUCCESS', '20260703')");
        jdbc.update("""
                insert into ana_tran_catalog (
                    tran_code, service_code, tran_name, module_name, owner
                ) values
                    ('C000', 'aaa', '交易一', '存款', '张三'),
                    ('C000', 'aaa', '交易一', '负债', '李四')
                """);
        jdbc.update("""
                insert into ana_field_mapping (
                    tran_code, service_code, std_field_name, sop_field_name, soap_field_name, bizjson_field_name
                ) values
                    ('C000', 'aaa', 'lst.acctNo', 'lst[0].acctNo', null, 'lst[0].acctNo'),
                    ('C000', 'aaa', 'lst.acctNo', 'lst[1].acctNo', null, 'lst[1].acctNo'),
                    ('C000', 'aaa', 'amount', 'amount', null, 'amount')
                """);
        StringBuilder sql = new StringBuilder("insert into tss_tran_comp (mesg_seq, orig_cdate, dest_trcd, comp_result) values ");
        for (int i = 1; i <= 100; i++) {
            if (i > 1) {
                sql.append(',');
            }
            sql.append("('SEQ").append(i).append("', '20260703', 'aaa&bzjson', '4')");
        }
        jdbc.update(sql.toString());
        jdbc.update("""
                insert into tss_field_comp (
                    mesg_seq, orig_cdate, comp_result, orig_field_name, dest_field_name, conv_index, conv_cindex
                ) values
                    ('SEQ1', '20260703', '0', 'fallback[0].unused', 'lst[0].acctNo', 1, 1),
                    ('SEQ2', '20260703', '0', 'amount', null, 1, 1)
                """);
    }

    private void seedServiceReportRows(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into ana_tran_catalog (
                    tran_code, service_code, tran_name, module_name, owner
                ) values
                    ('A001', 'S001', '交易一', 'M1', '张三'),
                    ('A002', 'S002', '交易二', 'M2', '李四')
                """);
        jdbc.update("""
                insert into tss_tran_comp (
                    mesg_seq, orig_cdate, dest_trcd, comp_result
                ) values
                    ('SEQ1', '20260611', 'S001&bizjson', '1'),
                    ('SEQ2', '20260611', 'S001&bizjson', '2'),
                    ('SEQ3', '20260611', 'S001&bizjson', '3'),
                    ('SEQ4', '20260611', 'S001&bizjson', '4'),
                    ('SEQ5', '20260611', 'S001&bizjson', '8'),
                    ('SEQ6', '20260611', 'S002&bizjson', '4')
                """);
        jdbc.update("""
                insert into ana_tran_diff_result (
                    result_id, batch_id, orig_cdate, tran_code, service_code, message_type,
                    sample_tran_seq_no, orig_error_code, orig_error_desc, dest_error_code,
                    dest_error_desc, owner, affected_tran_count
                ) values (
                    301, 'BATCH_RPT', '20260611', 'A001', 'S001', 'bizjson',
                    'SEQ5', 'E1', '错误1', 'C1', '错误A', '张三', 1
                )
                """);
        jdbc.update("""
                insert into ana_field_diff_result (
                    result_id, batch_id, orig_cdate, tran_code, service_code, message_type, message_types,
                    sop_field_name, soap_field_name, bizjson_field_name, field_cn_name, mapping_status,
                    sample_tran_seq_no, orig_field_value, dest_field_value, owner, affected_tran_count
                ) values
                    (302, 'BATCH_RPT', '20260611', 'A001', 'S001', 'bizjson', 'bizjson',
                     'F1', 'F1', 'F1', '字段1', 'MAPPED', 'SEQ4', '1', '2', '张三', 1),
                    (303, 'BATCH_RPT', '20260611', 'A001', 'S001', 'bizjson', 'bizjson',
                     'F2', 'F2', 'F2', '字段2', 'MAPPED', 'SEQ4', 'A', 'B', '张三', 1)
                """);
    }

    private void seedSemanticSampleRows(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into ana_field_diff_result (
                    result_id, batch_id, orig_cdate, tran_code, service_code, message_type, message_types,
                    sop_field_name, soap_field_name, bizjson_field_name, field_cn_name, mapping_status,
                    sample_tran_seq_no, orig_field_value, dest_field_value, owner, affected_tran_count
                ) values
                    (501, 'BATCH_A825', '20260611', 'A825', 'S030030014FcyCollCrspBnkLkgQry', 'bizjson', 'bizjson,sop',
                     'HUOBDH', 'CurrencyId', 'CurrencyId', '币种', 'MAPPED', '11111111111', '111', '222', '张伟', 2),
                    (502, 'BATCH_A825', '20260611', 'A825', 'S030030014FcyCollCrspBnkLkgQry', 'bizjson', 'bizjson',
                     'FAB251', 'FcyCollCrspBnkLkg', 'FcyCollCrspBnkLkg', '联动信息', 'MAPPED', '11111111111', 'A1/B1', 'A/B', '张伟', 1)
                """);
        jdbc.update("""
                insert into ana_sample_group (
                    group_id, batch_id, orig_cdate, sample_type, config_status, mapping_status,
                    semantic_signature, semantic_signature_hash, semantic_field_names, message_types,
                    dest_trcd, service_code, message_type, tran_code, comp_result, sop_field_name,
                    owner, affected_count, affected_tran_count, affected_field_count, sample_count, reason
                ) values (
                    11, 'BATCH_A825', '20260611', 'FIELD_DIFF', 'CONFIGURED', 'MAPPED',
                    'currency_id:111->222|link_info:A1/B1->A/B', 'HASH',
                    'currency_id,link_info', 'bizjson,sop',
                    'S030030014FcyCollCrspBnkLkgQry&bizjson', 'S030030014FcyCollCrspBnkLkgQry',
                    'bizjson', 'A825', '4', 'currency_id', '张伟', 2, 2, 4, 2, null
                )
                """);
        jdbc.update("""
                insert into ana_sample_detail (
                    sample_id, group_id, batch_id, orig_cdate, sample_type, sample_seq_no,
                    config_status, dest_trcd, service_code, message_type, tran_code,
                    comp_result, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
                    tran_seq_no, owner, affected_count, field_count,
                    orig_error_code, orig_error_desc, dest_error_code, dest_error_desc,
                    reason, source_table, source_pk
                ) values (
                    101, 11, 'BATCH_A825', '20260611', 'FIELD_DIFF', 1, 'CONFIGURED',
                    'S030030014FcyCollCrspBnkLkgQry&bizjson', 'S030030014FcyCollCrspBnkLkgQry',
                    'bizjson', 'A825', '4', 'HUOBDH', 'CurrencyId',
                    'CurrencyId', '币种',
                    '11111111111', '张伟', 2, 1,
                    null, null, null, null, null, 'tss_field_comp', '11111111111'
                ), (
                    102, 11, 'BATCH_A825', '20260611', 'FIELD_DIFF', 2, 'CONFIGURED',
                    'S030030014FcyCollCrspBnkLkgQry&bizjson', 'S030030014FcyCollCrspBnkLkgQry',
                    'bizjson', 'A825', '4', 'HUOBDH', 'CurrencyId',
                    'CurrencyId', '币种',
                    '11111111112', '张伟', 2, 1,
                    null, null, null, null, null, 'tss_field_comp', '11111111112'
                ), (
                    103, 11, 'BATCH_A825', '20260611', 'FIELD_DIFF', 3, 'CONFIGURED',
                    'S030030014FcyCollCrspBnkLkgQry&bizjson', 'S030030014FcyCollCrspBnkLkgQry',
                    'bizjson', 'A825', '4', 'FAB251', 'FcyCollCrspBnkLkg',
                    'FcyCollCrspBnkLkg', '联动信息',
                    '11111111111', '张伟', 1, 1,
                    null, null, null, null, null, 'tss_field_comp', '11111111111'
                )
                """);
        jdbc.update("""
                insert into ana_sample_detail_field (
                    field_detail_id, sample_id, group_id, batch_id, mesg_seq, message_type,
                    raw_field_name, std_field_name, field_cn_name, orig_field_value,
                    dest_field_value, mapping_status, field_index
                ) values
                    (1001, 101, 11, 'BATCH_A825', '11111111111', 'bizjson',
                     'CurrencyId', 'currency_id', '币种', '111', '222', 'MAPPED', 1),
                    (1002, 103, 11, 'BATCH_A825', '11111111111', 'bizjson',
                     'FcyCollCrspBnkLkg', 'link_info', '联动信息', 'A1/B1', 'A/B', 'MAPPED', 2),
                    (1003, 102, 11, 'BATCH_A825', '11111111112', 'bizjson',
                     'CurrencyId', 'currency_id', '币种', '333', '444', 'MAPPED', 1)
                """);
    }

    private void seedDuplicateTransactionDiffRows(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into ana_tran_diff_result (
                    result_id, batch_id, orig_cdate, tran_code, service_code, message_type,
                    sample_tran_seq_no, orig_error_code, orig_error_desc, dest_error_code,
                    dest_error_desc, owner, affected_tran_count
                ) values
                    (201, 'BATCH_TX', '20260611', 'A825', 'S001', 'bizjson',
                     'SEQ_DUP', 'E1,E2', '错误1,错误2', 'C1,C2', '错误A,错误B', '张三', 1)
                """);
    }

    private String javaSource(String fileName) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/spdb/sample/" + fileName));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
