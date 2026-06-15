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
    void samplingServiceUsesJavaSemanticComponentsForSampling() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");

        assertThat(source).contains("JdbcSamplingSourceReader");
        assertThat(source).contains("SamplingConfigSnapshot");
        assertThat(source).contains("SemanticSignatureBuilder");
        assertThat(source).contains("IssueGrouper");
        assertThat(source).contains("ana_sample_detail_field");
        assertThat(source).doesNotContain("ana_sampling_candidate");
        assertThat(source).doesNotContain("groupedCandidateCte");
        assertThat(source).doesNotContain("row_number() over");
        assertThat(source).doesNotContain("findFieldRows");
    }

    @Test
    void samplingServiceDoesNotMaterializeTemporaryCandidateTables() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");

        assertThat(source).doesNotContain("create temporary table");
        assertThat(source).doesNotContain("tmp_sampling_diff");
        assertThat(source).doesNotContain("tmp_sampling_detail_candidate");
        assertThat(source).doesNotContain("analyze tmp");
    }

    @Test
    void samplingServiceCreatesThreeIssueTypesFromStreamedSources() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");

        assertThat(source).contains("readTranFacts");
        assertThat(source).contains("readReturnCodes");
        assertThat(source).contains("readFieldDiffs");
        assertThat(source).contains("\"RETURN_CODE\"");
        assertThat(source).contains("\"RETURN_CODE\"");
        assertThat(source).contains("IssueCandidate.fieldDiff");
        assertThat(source).contains("\"4\".equals(fact.compResult())");
        assertThat(source).doesNotContain("f.orig_field_name = 'returnCode'");
        assertThat(source).doesNotContain("f.orig_field_name <> 'returnCode'");
    }

    @Test
    void samplingServiceKeysFieldDiffsBySourceKeyInJava() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");

        assertThat(source).contains("Map<SourceKey, TranFact> tranFacts");
        assertThat(source).contains("tranFacts.get(buffer.key)");
        assertThat(source).contains("Objects.equals(buffer.key, diff.sourceKey())");
        assertThat(source).doesNotContain("join tss_tran_comp t");
        assertThat(source).doesNotContain("from tss_field_comp f");
    }

    @Test
    void samplingServiceResolvesConfiguredFieldNamesByMessageTypeAndSemanticMapping() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");
        String snapshot = engineSource("SamplingConfigSnapshot.java");

        assertThat(source).contains("config.resolveField(fact.tranCode(), fact.serviceCode(), diff.messageType(), diff.rawFieldName())");
        assertThat(snapshot).contains("sopFieldName");
        assertThat(snapshot).contains("soapFieldName");
        assertThat(snapshot).contains("bizjsonFieldName");
        assertThat(snapshot).contains("UNMAPPED");
        assertThat(source).doesNotContain("and m.sop_field_name = d.orig_field_name");
    }

    @Test
    void samplingServiceWritesTransactionSamplesAndFieldDetails() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");

        assertThat(source).contains("private Long insertDetail");
        assertThat(source).contains("private void insertDetailFields");
        assertThat(source).contains("field_count");
        assertThat(source).contains("raw_field_name");
        assertThat(source).contains("std_field_name");
        assertThat(source).contains("mapping_status");
        assertThat(source).contains("MAX_SAMPLES_PER_GROUP = 20");
        assertThat(source).doesNotContain("case when sample_type = 'RETURN_CODE' then 1 else 10 end");
        assertThat(source).doesNotContain("set group_id = g.group_id");
        assertThat(source).doesNotContain("least(affected_count, 100)");
        assertThat(source).doesNotContain("sample_seq_no <= 100");
    }

    @Test
    void samplingSourceReaderUsesCursorStyleStreamingInsteadOfPagination() throws IOException {
        String source = engineSource("JdbcSamplingSourceReader.java").toLowerCase();

        assertThat(source).contains("setfetchsize");
        assertThat(source).contains("setautocommit(false)");
        assertThat(source).contains("resultset.type_forward_only");
        assertThat(source).doesNotContain(" offset ");
        assertThat(source).doesNotContain(" limit ");
    }

    @Test
    void samplingKeysIgnoreConversationIndexes() throws IOException {
        String sourceKey = engineSource("SourceKey.java");
        String sourceReader = engineSource("JdbcSamplingSourceReader.java").toLowerCase();
        String referenceSql = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("docs/sampling-batch-logic.sql")
        ), StandardCharsets.UTF_8).toLowerCase();

        assertThat(sourceKey).contains("record SourceKey(String mesgSeq)");
        assertThat(sourceReader).doesNotContain("conv_index");
        assertThat(sourceReader).doesNotContain("conv_cindex");
        assertThat(referenceSql).doesNotContain("conv_index");
        assertThat(referenceSql).doesNotContain("conv_cindex");
    }

    @Test
    void samplingServiceDoesNotRefreshDatabaseStatsForTemporaryJoins() throws IOException {
        String source = javaSource("SamplingBatchRunner.java");

        assertThat(source).doesNotContain("analyze ana_sampling_candidate");
        assertThat(source).doesNotContain("analyze ana_sample_group");
        assertThat(source).doesNotContain("insert into tmp_sampling_detail_candidate");
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

    private String engineSource(String fileName) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/com/spdb/sampling/engine/" + fileName)
        ), StandardCharsets.UTF_8);
    }

    private String template(String fileName) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/resources/templates/" + fileName)
        ), StandardCharsets.UTF_8);
    }
}
