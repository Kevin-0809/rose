package com.spdb.sample;

public record SampleGroupRow(
        Long groupId,
        String batchId,
        String origCdate,
        String sampleType,
        String configStatus,
        String mappingStatus,
        String semanticSignature,
        String semanticSignatureHash,
        String semanticFieldNames,
        String messageTypes,
        String destTrcd,
        String serviceCode,
        String messageType,
        String tranCode,
        String compResult,
        String owner,
        Long affectedCount,
        Long affectedTranCount,
        Long affectedFieldCount,
        Integer sampleCount,
        String reason
) {
}
