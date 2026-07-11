package com.spdb.config;

import java.time.LocalDateTime;

public record TransactionListImportTaskRow(
        long taskId,
        String status,
        String originalFilename,
        String listFilePath,
        int totalCount,
        int requestBatchCount,
        int completedBatchCount,
        int failedBatchCount,
        int importedCount,
        int tranInserted,
        int tranUpdated,
        int fieldInserted,
        int fieldUpdated,
        int fieldSkipped,
        String importedTranCodes,
        String failureMessage,
        LocalDateTime createdTime,
        LocalDateTime startedTime,
        LocalDateTime endedTime
) {
}
