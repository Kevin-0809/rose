package com.spdb.migration;

public record MigrationShardResult(
        long migratedRows,
        long skippedRows,
        long droppedRows
) {
}
