package com.spdb.sampling.engine;

import java.util.List;

public record SampleGroupDraft(
        String origCdate,
        String sampleType,
        String groupKey,
        String groupHash,
        String tranCode,
        String tranName,
        String moduleName,
        String owner,
        String serviceCode,
        String compResult,
        String configStatus,
        String mappingStatus,
        String semanticSignature,
        String semanticSignatureHash,
        String semanticFieldNames,
        String messageTypes,
        long affectedTranCount,
        long affectedFieldCount,
        List<SampleDetailDraft> details
) {
}
