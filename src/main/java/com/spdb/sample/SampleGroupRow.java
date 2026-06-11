package com.spdb.sample;

public record SampleGroupRow(
        Long groupId,
        String batchId,
        String sampleType,
        String destTrcd,
        String serviceCode,
        String messageType,
        String tranCode,
        String compResult,
        String sopFieldName,
        String soapFieldName,
        String bizjsonFieldName,
        String fieldCnName,
        String owner,
        Long affectedCount,
        Integer sampleCount,
        String reason
) {
}
