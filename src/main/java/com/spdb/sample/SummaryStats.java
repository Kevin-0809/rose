package com.spdb.sample;

public record SummaryStats(
        String batchId,
        String origCdate,
        long totalTranCount,
        long compResult1Count,
        long compResult2Count,
        long compResult3Count,
        long compResult4Count,
        long compResult8Count,
        long passTranCount,
        long tranIssueCount,
        long returnCodeIssueCount,
        long issueFieldCount,
        long fieldDiffTranCount,
        long unconfiguredServiceCount,
        long unmappedFieldCount,
        long fullyMatchedCount,
        long sampleGroupCount,
        long sampleDetailCount
) {
}
