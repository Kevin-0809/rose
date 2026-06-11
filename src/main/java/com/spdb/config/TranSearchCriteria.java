package com.spdb.config;

public record TranSearchCriteria(
        String tranCode,
        String serviceCode,
        String tranName,
        String moduleName,
        String owner,
        String isKeyTran
) {
}
