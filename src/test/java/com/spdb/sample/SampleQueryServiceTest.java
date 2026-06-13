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
    void queriesSemanticGroupSampleTransactionsAndFieldDetails() {
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
                "currency_id",
                null,
                null
        );

        SampleGroupRow group = service.groups(criteria, PageRequestParams.of(1, 20)).rows().get(0);
        assertThat(group.origCdate()).isEqualTo("20260611");
        assertThat(group.semanticFieldNames()).isEqualTo("currency_id,link_info");
        assertThat(group.messageTypes()).isEqualTo("bizjson,sop");
        assertThat(group.configStatus()).isEqualTo("CONFIGURED");
        assertThat(group.mappingStatus()).isEqualTo("MAPPED");
        assertThat(group.affectedTranCount()).isEqualTo(2L);
        assertThat(group.affectedFieldCount()).isEqualTo(4L);

        SampleDetailRow detail = service.details(criteria, PageRequestParams.of(1, 20)).rows().get(0);
        assertThat(detail.sampleId()).isEqualTo(101L);
        assertThat(detail.tranSeqNo()).isEqualTo("11111111111");
        assertThat(detail.sopFieldName()).isEqualTo("HUOBDH,FAB251");
        assertThat(detail.soapFieldName()).isEqualTo("CurrencyId,FcyCollCrspBnkLkg");
        assertThat(detail.bizjsonFieldName()).isEqualTo("CurrencyId,FcyCollCrspBnkLkg");
        assertThat(detail.fieldCnName()).isEqualTo("币种,联动信息");
        assertThat(detail.fieldCount()).isEqualTo(2);
        assertThat(detail.origErrorCode()).isNull();
        assertThat(detail.destErrorCode()).isNull();

        List<SampleDetailFieldRow> fields = service.detailFields(101L, PageRequestParams.of(1, 20)).rows();
        assertThat(fields).hasSize(2);
        assertThat(fields)
                .extracting(SampleDetailFieldRow::rawFieldName)
                .containsExactly("CurrencyId", "FcyCollCrspBnkLkg");
        assertThat(fields)
                .extracting(SampleDetailFieldRow::stdFieldName)
                .containsExactly("currency_id", "link_info");
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

    private int exportLimit(SqlParameterSource params) {
        assertThat(params.hasValue("exportLimit")).isTrue();
        return ((Number) params.getValue("exportLimit")).intValue();
    }

    private SampleSearchCriteria emptyCriteria() {
        return new SampleSearchCriteria(null, null, null, null, null, null, null, null, null, null, null);
    }

    private void createSemanticSampleTables(JdbcTemplate jdbc) {
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

    private void seedSemanticSampleRows(JdbcTemplate jdbc) {
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
                    'bizjson', 'A825', '4', 'HUOBDH,FAB251', 'CurrencyId,FcyCollCrspBnkLkg',
                    'CurrencyId,FcyCollCrspBnkLkg', '币种,联动信息',
                    '11111111111', '张伟', 2, 2,
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
                    (1002, 101, 11, 'BATCH_A825', '11111111111', 'bizjson',
                     'FcyCollCrspBnkLkg', 'link_info', '联动信息', 'A1/B1', 'A/B', 'MAPPED', 2)
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
