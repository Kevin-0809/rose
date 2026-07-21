package com.spdb.migration;

import java.time.LocalDateTime;
import java.util.List;

public record MigrationProgressRow(
        long commandId,
        String sourceDataSource,
        String targetDataSource,
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
        List<MigrationShardRow> shards,
        String commandType,
        String tranCodes,
        Integer sampleSize
) {
    public MigrationProgressRow(
            long commandId,
            String sourceDataSource,
            String targetDataSource,
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
    ) {
        this(commandId, sourceDataSource, targetDataSource, status, timeFrom, timeTo, windowSeconds, parallelism,
                totalShardCount, completedShardCount, failedShardCount, migratedRows, skippedRows, droppedRows,
                durationSeconds, startedTime, endedTime, errorMessage, shards, null, null, null);
    }

    public int completionPercent() {
        if (totalShardCount <= 0) {
            return 0;
        }
        return (int) (completedShardCount * 100 / totalShardCount);
    }

    public String progressText() {
        return completedShardCount + "/" + totalShardCount + " (" + completionPercent() + "%)";
    }
}
