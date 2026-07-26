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
        long diff528FieldCount,
        BigDecimal successRate
) {
}
