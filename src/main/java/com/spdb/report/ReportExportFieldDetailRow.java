package com.spdb.report;

import java.time.LocalDate;

public record ReportExportFieldDetailRow(
        long exportId,
        long rowNo,
        String serviceCode,
        String tranCode,
        String tranName,
        String moduleName,
        String soapFieldName,
        String fieldName,
        String mappingStatus,
        String origFieldValue,
        String destFieldValue,
        Long issueId,
        long historicalOccurrenceCount,
        LocalDate firstSeenDate,
        LocalDate previousSeenDate
) {
}
