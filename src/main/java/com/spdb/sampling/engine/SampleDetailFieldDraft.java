package com.spdb.sampling.engine;

public record SampleDetailFieldDraft(
        String rawFieldName,
        String stdFieldName,
        String fieldCnName,
        String sopFieldName,
        String soapFieldName,
        String bizjsonFieldName,
        String origFieldValue,
        String destFieldValue,
        String mappingStatus,
        int fieldIndex
) {
}
