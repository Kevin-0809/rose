package com.spdb.sampling;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamplingCommandServiceTest {

    @Test
    void createsBatchIdFromOrigCdateAndCurrentTime() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-09T06:31:05Z"), ZoneId.of("Asia/Shanghai"));
        SamplingCommandService service = new SamplingCommandService(null, emptyProvider(), clock);

        String batchId = service.nextBatchId("20260608");

        assertThat(batchId).startsWith("SMP20260608-143105-");
        assertThat(batchId).hasSize(23);
    }

    @Test
    void rejectsMissingOrigCdate() {
        SamplingCommandService service = new SamplingCommandService(null, emptyProvider(), Clock.systemDefaultZone());

        assertThatThrownBy(() -> service.createCommand(new SamplingCommandForm(
                null, null
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orig_cdate不能为空");
    }

    @Test
    void rejectsOrigCdateThatIsNotEightDigits() {
        SamplingCommandService service = new SamplingCommandService(null, emptyProvider(), Clock.systemDefaultZone());

        assertThatThrownBy(() -> service.createCommand(new SamplingCommandForm(
                "2026-06-08", null
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orig_cdate必须是8位日期");
    }

    private ObjectProvider<SamplingTaskLauncher> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public SamplingTaskLauncher getObject(Object... args) {
                return null;
            }

            @Override
            public SamplingTaskLauncher getIfAvailable() {
                return null;
            }

            @Override
            public SamplingTaskLauncher getIfUnique() {
                return null;
            }

            @Override
            public SamplingTaskLauncher getObject() {
                return null;
            }
        };
    }
}
