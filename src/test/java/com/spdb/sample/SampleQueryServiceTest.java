package com.spdb.sample;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SampleQueryServiceTest {

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
    void detailQueryLoadsFieldMappingColumnsFromMappingTableAndReturnCodeDescriptions() {
        String source = javaSource("SampleQueryService.java");

        assertThat(source).contains("left join ana_field_mapping m");
        assertThat(source).contains("m.tran_code = d.tran_code");
        assertThat(source).contains("m.service_code = d.service_code");
        assertThat(source).contains("m.sop_field_name = d.sop_field_name");
        assertThat(source).contains("case when d.sample_type = 'RETURN_CODE' then null else coalesce(m.sop_field_name, d.sop_field_name) end as sop_field_name");
        assertThat(source).contains("case when d.sample_type = 'RETURN_CODE' then null else coalesce(m.soap_field_name, d.soap_field_name) end as soap_field_name");
        assertThat(source).contains("case when d.sample_type = 'RETURN_CODE' then null else coalesce(m.bizjson_field_name, d.bizjson_field_name) end as bizjson_field_name");
        assertThat(source).contains("case when d.sample_type = 'RETURN_CODE' then null else coalesce(m.field_cn_name, d.field_cn_name) end as field_cn_name");
        assertThat(source).contains("left join tss_retcode_comp r");
        assertThat(source).contains("r.mesg_seq = d.tran_seq_no");
        assertThat(source).contains("case when d.sample_type = 'RETURN_CODE' then r.orig_error_desc else null end as orig_field_desc");
        assertThat(source).contains("case when d.sample_type = 'RETURN_CODE' then r.dest_error_desc else null end as dest_field_desc");
    }

    private int exportLimit(SqlParameterSource params) {
        assertThat(params.hasValue("exportLimit")).isTrue();
        return ((Number) params.getValue("exportLimit")).intValue();
    }

    private SampleSearchCriteria emptyCriteria() {
        return new SampleSearchCriteria(null, null, null, null, null, null, null, null);
    }

    private String javaSource(String fileName) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/spdb/sample/" + fileName));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
