package com.spdb.sample;

public record TransactionDiffTrackingExportRow(
        String sourceBatchId,
        String serviceCode,
        String origErrorCode,
        String destErrorCode,
        String tranCode,
        String tranName,
        String moduleName,
        String origErrorDesc,
        String destErrorDesc,
        String transactionOwner,
        String tranSeqNo) {
}
