package com.spdb.config;

public record ParsedTranImport(
        String tranCode,
        String serviceCode,
        String tranName,
        String moduleName,
        String owner,
        String remark
) {
}
