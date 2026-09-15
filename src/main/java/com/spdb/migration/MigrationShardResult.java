package com.spdb.migration;

public record MigrationShardResult(
        long migratedRows,
        long skippedRows,
        long droppedRows,
        Integer actualScanDays
) {
    public MigrationShardResult(long migratedRows, long skippedRows, long droppedRows) {
        this(migratedRows, skippedRows, droppedRows, null);
    }
}
