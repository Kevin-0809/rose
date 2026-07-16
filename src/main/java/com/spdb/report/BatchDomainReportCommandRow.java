package com.spdb.report;

import java.time.LocalDateTime;

public record BatchDomainReportCommandRow(
        long commandId,
        String batchId,
        String status,
        LocalDateTime startedTime,
        LocalDateTime endedTime,
        String errorMessage,
        LocalDateTime createdTime
) {
}
