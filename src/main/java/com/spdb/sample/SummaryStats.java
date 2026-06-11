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
        long issueFieldCount,
        long fullyMatchedCount,
        long sampleGroupCount,
        long sampleDetailCount
) {
}
