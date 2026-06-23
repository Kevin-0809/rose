package com.spdb.migration;

import java.time.LocalDateTime;
import java.util.List;

public record MigrationProgressRow(
        long commandId,
        String status,
        long timeFrom,
        long timeTo,
        long windowSeconds,
        int parallelism,
        long totalShardCount,
        long completedShardCount,
        long failedShardCount,
        long migratedRows,
        long skippedRows,
        long droppedRows,
        Long durationSeconds,
        LocalDateTime startedTime,
        LocalDateTime endedTime,
        String errorMessage,
        List<MigrationShardRow> shards
) {}
