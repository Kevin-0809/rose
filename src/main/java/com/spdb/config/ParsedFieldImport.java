package com.spdb.config;

public record ParsedFieldImport(
        String tranCode,
        String serviceCode,
        String stdFieldName,
        String fieldCnName,
        String sopFieldName,
        String soapFieldName,
        String bizjsonFieldName,
        String remark
) {
}
