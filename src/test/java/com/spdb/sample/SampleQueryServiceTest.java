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

    private int exportLimit(SqlParameterSource params) {
        assertThat(params.hasValue("exportLimit")).isTrue();
        return ((Number) params.getValue("exportLimit")).intValue();
    }

    private SampleSearchCriteria emptyCriteria() {
        return new SampleSearchCriteria(null, null, null, null, null, null, null, null);
    }
}
