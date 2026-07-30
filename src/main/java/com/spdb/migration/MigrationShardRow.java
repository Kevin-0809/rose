package com.spdb.migration;

public record MigrationShardRow(
        int shardSeq,
        String tranCode,
        long timeFrom,
        long timeTo,
        String status,
        long migratedRows,
        long skippedRows,
        long droppedRows,
        Integer actualLookbackDays,
        int attempts,
        long durationSeconds,
        String errorMessage
) {
    public MigrationShardRow(
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
    ) {
        this(shardSeq, null, timeFrom, timeTo, status, migratedRows, skippedRows, droppedRows, null, attempts,
                durationSeconds, errorMessage);
    }
}
