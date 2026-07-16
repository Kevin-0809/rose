package com.spdb.report;

public record BatchDomainReportRow(
        String batchId,
        String moduleName,
        long coveredServiceCount,
        long sentTransactionCount,
        long compResult1Count,
        long compResult2Count,
        long compResult3Count,
        long compResult4Count,
        long compResult8Count
) {
}
