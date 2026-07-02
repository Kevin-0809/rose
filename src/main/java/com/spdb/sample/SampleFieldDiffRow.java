package com.spdb.sample;

public record SampleFieldDiffRow(
        String origCdate,
        String batchId,
        String tranCode,
        String serviceCode,
        String messageType,
        String sopFieldName,
        String soapFieldName,
        String bizjsonFieldName,
        String fieldCnName,
        String mappingStatus,
        String sampleTranSeqNo,
        String origFieldValue,
        String destFieldValue,
        String owner,
        Long affectedTranCount
) {
}
