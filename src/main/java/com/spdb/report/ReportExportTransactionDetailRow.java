package com.spdb.report;

import java.time.LocalDate;

public record ReportExportTransactionDetailRow(
        long exportId,
        long rowNo,
        String serviceCode,
        String origErrorCode,
        String destErrorCode,
        String tranCode,
        String tranName,
        String moduleName,
        String origErrorDesc,
        String destErrorDesc,
        Long issueId,
        long historicalOccurrenceCount,
        LocalDate firstSeenDate,
        LocalDate previousSeenDate
) {
}
