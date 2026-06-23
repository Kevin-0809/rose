package com.spdb.migration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationMockDataTest {

    @Test
    void commandRowsCoversAllStatuses() {
        List<MigrationCommandRow> rows = MigrationMockData.commandRows();

        assertThat(rows).isNotEmpty();
        assertThat(rows).extracting(MigrationCommandRow::status)
                .contains("CREATED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED");
    }

    @Test
    void progressForRunningCommandIncludesShards() {
        MigrationProgressRow progress = MigrationMockData.progress(2L);

        assertThat(progress.commandId()).isEqualTo(2L);
        assertThat(progress.status()).isEqualTo("RUNNING");
        assertThat(progress.totalShardCount()).isGreaterThan(0);
        assertThat(progress.completedShardCount()).isLessThanOrEqualTo(progress.totalShardCount());
        assertThat(progress.shards()).isNotEmpty();
        assertThat(progress.shards()).extracting(MigrationShardRow::status)
                .contains("COMPLETED", "RUNNING", "PENDING");
    }

    @Test
    void progressForCompletedCommandHasOnlyCompletedOrSkippedShards() {
        MigrationProgressRow progress = MigrationMockData.progress(3L);

        assertThat(progress.commandId()).isEqualTo(3L);
        assertThat(progress.status()).isEqualTo("COMPLETED");
        assertThat(progress.shards()).isNotEmpty();
        assertThat(progress.shards()).extracting(MigrationShardRow::status)
                .containsOnly("COMPLETED", "SKIPPED");
    }

    @Test
    void progressForFailedCommandHasSixFailedShardsWithoutRunningOrPending() {
        MigrationProgressRow progress = MigrationMockData.progress(4L);

        assertThat(progress.commandId()).isEqualTo(4L);
        assertThat(progress.status()).isEqualTo("FAILED");
        assertThat(progress.shards()).isNotEmpty();
        assertThat(progress.shards()).filteredOn(shard -> "FAILED".equals(shard.status()))
                .hasSize(6);
        assertThat(progress.shards()).extracting(MigrationShardRow::status)
                .doesNotContain("RUNNING", "PENDING");
    }

    @Test
    void progressForCancelledCommandHasNoRunningShards() {
        MigrationProgressRow progress = MigrationMockData.progress(5L);

        assertThat(progress.commandId()).isEqualTo(5L);
        assertThat(progress.status()).isEqualTo("CANCELLED");
        assertThat(progress.shards()).isNotEmpty();
        assertThat(progress.shards()).extracting(MigrationShardRow::status)
                .doesNotContain("RUNNING");
    }

    @Test
    void progressForUnknownCommandReturnsNull() {
        assertThat(MigrationMockData.progress(9999L)).isNull();
    }
}
