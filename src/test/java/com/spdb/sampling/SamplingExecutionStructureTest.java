package com.spdb.sampling;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SamplingExecutionStructureTest {

    @Test
    void samplingExecutionDoesNotUseSpringBatch() throws IOException {
        String pom = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("pom.xml")
        ), StandardCharsets.UTF_8);
        String appProperties = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/resources/application.properties")
        ), StandardCharsets.UTF_8);

        assertThat(pom).doesNotContain("spring-boot-starter-batch");
        assertThat(appProperties).doesNotContain("spring.batch");
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(
                "src/main/java/com/spdb/sampling/SamplingBatchConfig.java"
        ))).isFalse();
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(
                "src/main/java/com/spdb/sampling/SamplingChunkWriter.java"
        ))).isFalse();
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(
                "src/main/java/com/spdb/sampling/SamplingAsyncExecutor.java"
        ))).isTrue();
    }

    @Test
    void samplingServiceUsesSqlCandidateAggregationForSampling() throws IOException {
        String source = javaSource("SamplingCommandService.java");

        assertThat(source).contains("ana_sampling_candidate");
        assertThat(source).contains("groupedCandidateCte");
        assertThat(source).contains("distinct on");
        assertThat(source).contains(" group by ");
        assertThat(source).doesNotContain("findFieldRows");
    }

    @Test
    void samplingServiceMaterializesFilteredDiffRowsBeforeCandidateJoin() throws IOException {
        String source = javaSource("SamplingCommandService.java");

        assertThat(source).contains("create temporary table tmp_sampling_diff");
        assertThat(source).contains("analyze tmp_sampling_diff");
        assertThat(source).contains("from tmp_sampling_diff d");
    }

    @Test
    void samplingServiceSplitsDetailInsertByGroupSize() throws IOException {
        String source = javaSource("SamplingCommandService.java");

        assertThat(source).contains("c.affected_count <= 100");
        assertThat(source).contains("c.affected_count > 100");
        assertThat(source).contains("partition by group_id");
        assertThat(source).doesNotContain("partition by c.group_key");
        assertThat(source).contains("create temporary table tmp_sampling_detail_candidate");
        assertThat(source).contains("from tmp_sampling_detail_candidate c");
        assertThat(source).doesNotContain("set group_id = g.group_id");
    }

    @Test
    void samplingServiceRefreshesStatsBeforeDetailCandidateJoin() throws IOException {
        String source = javaSource("SamplingCommandService.java");

        assertThat(source).contains("analyze ana_sampling_candidate");
        assertThat(source).contains("analyze ana_sample_group");
        assertThat(source.indexOf("analyze ana_sampling_candidate"))
                .isLessThan(source.indexOf("insert into tmp_sampling_detail_candidate"));
        assertThat(source.indexOf("analyze ana_sample_group"))
                .isLessThan(source.indexOf("insert into tmp_sampling_detail_candidate"));
    }

    @Test
    void asyncExecutorRunsOneSetBasedSamplingPass() throws IOException {
        String source = javaSource("SamplingAsyncExecutor.java");
        String service = javaSource("SamplingCommandService.java");

        assertThat(source).contains("runSamplingBatch(batchId)");
        assertThat(source).doesNotContain("CHUNK_SIZE");
        assertThat(source).doesNotContain("writeTranChunk");
        assertThat(source).doesNotContain("streamTransactions");
        assertThat(service).doesNotContain("@Transactional\n    public void runSamplingBatch");
    }

    @Test
    void templatesDoNotMentionSpringBatch() throws IOException {
        String home = template("home.html");
        String commands = template("sampling/commands.html");

        assertThat(home).doesNotContain("Spring Batch");
        assertThat(commands).doesNotContain("Spring Batch");
    }

    private String javaSource(String fileName) throws IOException {
        try (var input = getClass().getResourceAsStream("/../java/com/spdb/sampling/" + fileName)) {
            if (input == null) {
                return new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Path.of("src/main/java/com/spdb/sampling/" + fileName)
                ), StandardCharsets.UTF_8);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String template(String fileName) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/resources/templates/" + fileName)
        ), StandardCharsets.UTF_8);
    }
}
