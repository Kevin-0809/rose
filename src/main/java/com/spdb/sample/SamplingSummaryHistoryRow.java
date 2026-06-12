package com.spdb.sample;

import java.time.LocalDateTime;

public record SamplingSummaryHistoryRow(
        String batchId,
        String origCdate,
        Long totalTranCount,
        Long compResult1Count,
        Long compResult2Count,
        Long compResult3Count,
        Long compResult4Count,
        Long compResult8Count,
        Long passTranCount,
        Long tranIssueCount,
        Long returnCodeIssueCount,
        Long issueFieldCount,
        Long fieldDiffTranCount,
        Long unconfiguredServiceCount,
        Long unmappedFieldCount,
        Long fullyMatchedCount,
        Long sampleGroupCount,
        Long sampleDetailCount,
        LocalDateTime createdAt
) {
}
