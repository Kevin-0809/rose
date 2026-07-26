package com.spdb.report;

import java.time.LocalDateTime;

public record ReportExportCommandRow(
        long commandId,
        String batchId,
        String reportDate,
        String status,
        LocalDateTime startedTime,
        LocalDateTime endedTime,
        String errorMessage,
        LocalDateTime createdTime
) {
}
