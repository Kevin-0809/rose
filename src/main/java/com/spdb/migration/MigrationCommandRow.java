package com.spdb.migration;

import java.time.LocalDateTime;

public record MigrationCommandRow(
        long commandId,
        String sourceDataSource,
        String targetDataSource,
        String commandType,
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
        String requestSql,
        String responseSql,
        String tranCodes,
        Integer sampleSize,
        String remark
) {
    public MigrationCommandRow(
            long commandId,
            String sourceDataSource,
            String targetDataSource,
            String commandType,
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
            String requestSql,
            String responseSql,
            String remark
    ) {
        this(commandId, sourceDataSource, targetDataSource, commandType, status, timeFrom, timeTo, windowSeconds,
                parallelism, totalShardCount, completedShardCount, failedShardCount, migratedRows, skippedRows,
                droppedRows, durationText, createdTime, startedTime, endedTime, errorMessage, requestSql, responseSql,
                null, null, remark);
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
