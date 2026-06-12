package com.spdb.sample;

public record SampleDetailFieldRow(
        Long fieldDetailId,
        Long sampleId,
        Long groupId,
        String batchId,
        String mesgSeq,
        String messageType,
        String rawFieldName,
        String stdFieldName,
        String fieldCnName,
        String origFieldValue,
        String destFieldValue,
        String mappingStatus,
        Integer fieldIndex
) {
}
