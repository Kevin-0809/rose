package com.spdb.sample;

public record LeadershipServiceReportRow(
        String batchId,
        String origCdate,
        String tranCode,
        String serviceCode,
        String tranName,
        String moduleName,
        String owner,
        long totalTranCount,
        long compResult1Count,
        long compResult2Count,
        long compResult3Count,
        long compResult4Count,
        long compResult8Count,
        long passTranCount,
        long tranIssueCount,
        long returnCodeIssueCount,
        long fieldDiffTranCount,
        long fullyMatchedCount,
        long issueFieldCount
) {
    public double rate(long numerator) {
        if (totalTranCount == 0) {
            return 0.0d;
        }
        return numerator / (double) totalTranCount;
    }
}
