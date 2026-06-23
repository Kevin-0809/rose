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
    void progressForUnknownCommandReturnsNull() {
        assertThat(MigrationMockData.progress(9999L)).isNull();
    }
}
