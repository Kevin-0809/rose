package com.spdb.migration;

public record MigrationShardRow(
        int shardSeq,
        long timeFrom,
        long timeTo,
        String status,
        long migratedRows,
        long skippedRows,
        long droppedRows,
        int attempts,
        long durationSeconds,
        String errorMessage
) {}
