package com.spdb.migration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationExecutionConfigTest {

    @Test
    void shardExecutorUsesWorkersImmediatelyInsteadOfQueueingMigrationShards() {
        var executor = new MigrationExecutionConfig().migrationTaskExecutor();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(16);
            assertThat(executor.getMaxPoolSize()).isEqualTo(16);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
        } finally {
            executor.shutdown();
        }
    }
}
