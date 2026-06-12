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
        String source = javaSource("SamplingBatchRunner.java");

        assertThat(source).contains("ana_sampling_candidate");
        assertThat(source).contains("groupedCandidateCte");
        assertThat(source).contains("row_number() over");
        assertThat(source).contains(" group by ");
        assertThat(source).doesNotContain("findFieldRows");
    }

    @Test
    void samplingServiceMaterializesFilteredDiffRowsBeforeCandidateJoin() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");

        assertThat(source).contains("create temporary table tmp_sampling_diff");
        assertThat(source).contains("sample_type varchar(32) not null");
        assertThat(source).contains("analyze tmp_sampling_diff");
        assertThat(source).contains("from tmp_sampling_diff d");
    }

    @Test
    void samplingServiceReadsTransactionLevelDifferencesFromRetcodeTable() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");

        assertThat(source).contains("from tss_retcode_comp r");
        assertThat(source).contains("'RETURN_CODE' as sample_type");
        assertThat(source).contains("'FIELD_DIFF' as sample_type");
        assertThat(source).contains("'returnCode' as orig_field_name");
        assertThat(source).contains("'returnCode' as dest_field_name");
        assertThat(source).contains("d.service_code || '|' || d.comp_result");
        assertThat(source).contains("service_code || '|' ||\n                            comp_result");
        assertThat(source).contains("group by sample_type, tran_code, service_code, comp_result, sop_field_name");
        assertThat(source).contains("t.comp_result = '4'");
        assertThat(source).doesNotContain("f.orig_field_name = 'returnCode'");
        assertThat(source).doesNotContain("f.orig_field_name <> 'returnCode'");
    }

    @Test
    void samplingServiceJoinsTranAndFieldComparisonOnlyByMessageSequence() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");

        assertThat(source).contains("join tss_tran_comp t\n                      on t.mesg_seq = f.mesg_seq");
        assertThat(source).contains("where f.mesg_seq = t.mesg_seq");
        assertThat(source).doesNotContain("t.conv_index = f.conv_index");
        assertThat(source).doesNotContain("t.conv_cindex = f.conv_cindex");
        assertThat(source).doesNotContain("f.conv_index = t.conv_index");
        assertThat(source).doesNotContain("f.conv_cindex = t.conv_cindex");
    }

    @Test
    void samplingServiceResolvesConfiguredFieldNamesByTransactionAndSopField() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");

        assertThat(source).contains("and m.sop_field_name = d.orig_field_name");
        assertThat(source).doesNotContain("and m.bizjson_field_name = d.dest_field_name");
        assertThat(source).contains("coalesce(m.soap_field_name, d.dest_field_name) as soap_field_name");
        assertThat(source).contains("coalesce(m.bizjson_field_name, d.dest_field_name) as bizjson_field_name");
    }

    @Test
    void samplingServiceSplitsDetailInsertByGroupSize() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");

        assertThat(source).contains("case when sample_type = 'RETURN_CODE' then 1 else 10 end");
        assertThat(source).contains("case when c.sample_type = 'RETURN_CODE' then 1 else 10 end");
        assertThat(source).contains("partition by c.group_id");
        assertThat(source).doesNotContain("partition by c.group_key");
        assertThat(source).contains("create temporary table tmp_sampling_detail_candidate");
        assertThat(source).contains("from tmp_sampling_detail_candidate c");
        assertThat(source).doesNotContain("set group_id = g.group_id");
        assertThat(source).doesNotContain("least(affected_count, 100)");
        assertThat(source).doesNotContain("sample_seq_no <= 100");
    }

    @Test
    void samplingServiceRefreshesStatsBeforeDetailCandidateJoin() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");

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
        assertThat(service).doesNotContain("writeTranChunk");
        assertThat(service).doesNotContain("SamplingTranItem");
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
        return new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/com/spdb/sampling/" + fileName)
        ), StandardCharsets.UTF_8);
    }

    private String template(String fileName) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/resources/templates/" + fileName)
        ), StandardCharsets.UTF_8);
    }
}
