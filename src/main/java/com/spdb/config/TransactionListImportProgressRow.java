package com.spdb.config;

import java.time.LocalDateTime;

public record TransactionListImportProgressRow(
        long taskId,
        String status,
        String originalFilename,
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
        String failureMessage,
        LocalDateTime createdTime,
        LocalDateTime startedTime,
        LocalDateTime endedTime
) {
    public int completionPercent() {
        if (requestBatchCount <= 0) {
            return "COMPLETED".equals(status) ? 100 : 0;
        }
        return Math.min(100, (completedBatchCount + failedBatchCount) * 100 / requestBatchCount);
    }

    public String progressText() {
        return (completedBatchCount + failedBatchCount) + "/" + requestBatchCount + " (" + completionPercent() + "%)";
    }

    public boolean running() {
        return "CREATED".equals(status) || "RUNNING".equals(status);
    }
}
