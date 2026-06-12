package com.spdb.sample;

public record SampleSearchCriteria(
        String batchId,
        String origCdate,
        String sampleType,
        String tranCode,
        String serviceCode,
        String messageType,
        String configStatus,
        String mappingStatus,
        String semanticFieldName,
        String owner,
        String tranSeqNo
) {
}
