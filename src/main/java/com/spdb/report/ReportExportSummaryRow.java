package com.spdb.report;

import java.math.BigDecimal;

public record ReportExportSummaryRow(
        long summaryId,
        String batchId,
        String reportDate,
        String moduleName,
        long covered528InterfaceCount,
        long sentTransactionCount,
        long compResult1Count,
        long compResult2Count,
        long compResult3Count,
        long compResult4Count,
        long compResult8Count,
        long compResult5Count,
        long diff528FieldCount,
        BigDecimal successRate,
        long fieldPassTransactionCount,
        BigDecimal comparisonPassRate,
        long transactionIssueCount,
        long fieldIssueCount,
        long issueTotalCount,
        long duplicateIssueCount
) {
    public ReportExportSummaryRow(long summaryId,
                                  String batchId,
                                  String reportDate,
                                  String moduleName,
                                  long covered528InterfaceCount,
                                  long sentTransactionCount,
                                  long compResult1Count,
                                  long compResult2Count,
                                  long compResult3Count,
                                  long compResult4Count,
                                  long compResult8Count,
                                  long compResult5Count,
                                  long diff528FieldCount,
                                  BigDecimal successRate) {
        this(summaryId, batchId, reportDate, moduleName, covered528InterfaceCount, sentTransactionCount,
                compResult1Count, compResult2Count, compResult3Count, compResult4Count, compResult8Count, compResult5Count,
                diff528FieldCount, successRate, 0L, BigDecimal.ZERO, 0L, 0L, 0L, 0L);
    }
}
