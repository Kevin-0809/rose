package com.spdb.sample;

public record TransactionSuccessStatRow(
        String origCdate,
        String batchId,
        String tranCode,
        String serviceCode,
        String messageType,
        Long successCount,
        Long interfaceFieldCount,
        Long comparedFieldCount,
        Long diffFieldCount,
        Long comparedFieldDiffCount,
        Long highRatioFieldCount,
        Long lowRatioFieldCount,
        String moduleName,
        String owner
) {
}
