package com.spdb.sampling.engine;

public record FieldSemantic(
        String rawFieldName,
        String stdFieldName,
        String fieldCnName,
        String sopFieldName,
        String soapFieldName,
        String bizjsonFieldName,
        String mappingStatus
) {
    public static final String MAPPED = "MAPPED";
    public static final String UNMAPPED = "UNMAPPED";
}
