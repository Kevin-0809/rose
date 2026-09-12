package com.spdb.message;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnaMessageSendServiceLiveStatsTest {

    @Test
    void initialLiveStatsAreZeroAndIdle() {
        AnaMessageSendService service = new AnaMessageSendService(mockJdbc(), mock(PlatformTransactionManager.class));

        Map<String, Object> stats = service.liveStats();

        assertThat(stats.get("running")).isEqualTo(false);
        assertThat(stats.get("tps")).isEqualTo(0.0);
        assertThat(stats.get("pending")).isEqualTo(0L);
        assertThat(stats.get("sending")).isEqualTo(0);
        assertThat(stats.get("success")).isEqualTo(0L);
        assertThat(stats.get("failed")).isEqualTo(0L);
    }

    @Test
    void initialIsNotRunning() {
        AnaMessageSendService service = new AnaMessageSendService(mockJdbc(), mock(PlatformTransactionManager.class));

        assertThat(service.isRunning()).isFalse();
    }

    private NamedParameterJdbcTemplate mockJdbc() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.getJdbcTemplate()).thenReturn(mock(JdbcTemplate.class));
        return jdbc;
    }
}
