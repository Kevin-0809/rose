package com.spdb.sampling;

import java.time.LocalDateTime;

public record SamplingSummaryRow(
        Long summaryId,
        String batchId,
        String origCdate,
        Long totalTranCount,
        Long compResult1Count,
        Long compResult2Count,
        Long compResult3Count,
        Long compResult4Count,
        Long compResult8Count,
        Long passTranCount,
        Long issueFieldCount,
        Long fullyMatchedCount,
        Long sampleGroupCount,
        Long sampleDetailCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
