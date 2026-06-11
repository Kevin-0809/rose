package com.spdb.config;

public record FieldSearchCriteria(
        String tranCode,
        String serviceCode,
        String stdFieldName,
        String sopFieldName,
        String soapFieldName,
        String bizjsonFieldName,
        String fieldCnName
) {
}
