package com.spdb.sample;

public record FieldDiffTrackingExportRow(
        String sourceBatchId,
        String tranCode,
        String serviceCode,
        String sopFieldName,
        String soapFieldName,
        String bizjsonFieldName,
        String fieldCnName,
        String mappingStatus,
        String origFieldValue,
        String destFieldValue,
        String tranName,
        String moduleName,
        String transactionOwner,
        String tranSeqNo) {
}
