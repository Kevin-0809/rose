package com.spdb.sample;

public record SampleDetailRow(
        Long sampleId,
        Long groupId,
        String batchId,
        String sampleType,
        Integer sampleSeqNo,
        String destTrcd,
        String serviceCode,
        String messageType,
        String tranCode,
        String compResult,
        String sopFieldName,
        String soapFieldName,
        String bizjsonFieldName,
        String fieldCnName,
        String origFieldValue,
        String destFieldValue,
        String tranSeqNo,
        String owner,
        Long affectedCount,
        String reason
) {
}
