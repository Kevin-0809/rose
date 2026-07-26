package com.spdb.report;

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
        String destErrorDesc
) {
}
