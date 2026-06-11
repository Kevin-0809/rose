package com.spdb.sample;

public record SampleSearchCriteria(
        String batchId,
        String sampleType,
        String tranCode,
        String serviceCode,
        String sopFieldName,
        String fieldCnName,
        String owner,
        String tranSeqNo
) {
}
