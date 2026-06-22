package com.spdb.config;

import com.spdb.repository.FieldMappingRepository;
import com.spdb.repository.TranCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class ConfigQueryServiceTest {

    private NamedParameterJdbcTemplate jdbc;
    private ConfigQueryService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:config_query;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().execute("create alias if not exists pg_systimestamp for \"com.spdb.config.ConfigQueryServiceTest.pgSystimestamp\"");
        jdbc.getJdbcTemplate().execute("drop table if exists system_config");
        jdbc.getJdbcTemplate().execute("""
                create table system_config (
                    config_key varchar(100) primary key,
                    config_value varchar(200) not null,
                    description varchar(500),
                    updated_time timestamp
                )
                """);
        service = new ConfigQueryService(
                mock(TranCatalogRepository.class),
                mock(FieldMappingRepository.class),
                jdbc
        );
    }

    @Test
    void savesAndReadsGlobalRecordingSwitchWithSystemConfigKey() {
        service.saveGlobalRecordingSwitch("true");

        assertThat(service.globalRecordingSwitch()).isEqualTo("true");
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "select config_value from system_config where config_key = 'recording.global_switch'",
                String.class
        )).isEqualTo("true");
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "select count(*) from system_config where config_key = 'global_recording_switch'",
                Integer.class
        )).isZero();

        service.saveGlobalRecordingSwitch("false");

        assertThat(service.globalRecordingSwitch()).isEqualTo("false");
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "select config_value from system_config where config_key = 'recording.global_switch'",
                String.class
        )).isEqualTo("false");
    }

    public static LocalDateTime pgSystimestamp() {
        return LocalDateTime.now();
    }
}
