package com.spdb.report;

import java.time.LocalDateTime;

public record ReportExportCommandRow(
        long commandId,
        String batchId,
        String reportDate,
        String status,
        String currentStage,
        LocalDateTime startedTime,
        LocalDateTime endedTime,
        String errorMessage,
        LocalDateTime createdTime
) {
}
