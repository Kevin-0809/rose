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

    @Test
    void commandServiceSelectsSemanticSummaryCounters() {
        String source = javaSource("SamplingCommandService.java");

        assertThat(source).contains("tran_issue_count");
        assertThat(source).contains("return_code_issue_count");
        assertThat(source).contains("field_diff_tran_count");
        assertThat(source).contains("unconfigured_service_count");
        assertThat(source).contains("unmapped_field_count");
    }

    private String javaSource(String fileName) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/spdb/sampling/" + fileName));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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
