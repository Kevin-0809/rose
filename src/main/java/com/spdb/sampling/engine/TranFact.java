package com.spdb.sampling.engine;

public record TranFact(
        SourceKey sourceKey,
        String origCdate,
        String destTrcd,
        String serviceCode,
        String messageType,
        String tranCode,
        String tranName,
        String moduleName,
        String owner,
        String compResult,
        String configStatus
) {
}
