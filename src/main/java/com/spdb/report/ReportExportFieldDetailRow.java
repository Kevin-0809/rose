package com.spdb.report;

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
        String destFieldValue
) {
}
