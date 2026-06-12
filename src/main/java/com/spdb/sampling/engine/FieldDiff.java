package com.spdb.sampling.engine;

public record FieldDiff(
        SourceKey sourceKey,
        String origCdate,
        String destTrcd,
        String serviceCode,
        String messageType,
        String rawFieldName,
        String destFieldName,
        String origFieldValue,
        String destFieldValue,
        int fieldIndex
) {
}
