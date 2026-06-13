package com.spdb.sample;

public record SampleDetailRow(
        Long sampleId,
        Long groupId,
        String batchId,
        String origCdate,
        String sampleType,
        Integer sampleSeqNo,
        String configStatus,
        String destTrcd,
        String serviceCode,
        String messageType,
        String tranCode,
        String compResult,
        String sopFieldName,
        String soapFieldName,
        String bizjsonFieldName,
        String fieldCnName,
        String tranSeqNo,
        String owner,
        Long affectedCount,
        Integer fieldCount,
        String origErrorCode,
        String origErrorDesc,
        String destErrorCode,
        String destErrorDesc,
        String reason,
        String sourceTable,
        String sourcePk
) {
}
