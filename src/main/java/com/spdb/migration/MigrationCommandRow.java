package com.spdb.migration;

import java.time.LocalDateTime;

public record MigrationCommandRow(
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
        String durationText,
        LocalDateTime createdTime,
        LocalDateTime startedTime,
        LocalDateTime endedTime,
        String errorMessage,
        String remark
) {}
