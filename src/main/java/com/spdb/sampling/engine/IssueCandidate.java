package com.spdb.sampling.engine;

import java.util.List;

public record IssueCandidate(
        String origCdate,
        String sampleType,
        SourceKey sourceKey,
        String tranCode,
        String tranName,
        String moduleName,
        String owner,
        String serviceCode,
        String messageType,
        String destTrcd,
        String compResult,
        String configStatus,
        String mappingStatus,
        String semanticSignature,
        String semanticSignatureHash,
        String origErrorCode,
        String origErrorDesc,
        String destErrorCode,
        String destErrorDesc,
        List<SampleDetailFieldDraft> fields
) {
    public static IssueCandidate fieldDiff(String origCdate,
                                           SourceKey sourceKey,
                                           String tranCode,
                                           String tranName,
                                           String moduleName,
                                           String owner,
                                           String serviceCode,
                                           String messageType,
                                           String destTrcd,
                                           String compResult,
                                           String semanticSignature,
                                           String semanticSignatureHash,
                                           List<SampleDetailFieldDraft> fields) {
        String mappingStatus = fields.stream().allMatch(field -> FieldSemantic.MAPPED.equals(field.mappingStatus()))
                ? FieldSemantic.MAPPED
                : "MIXED";
        return new IssueCandidate(
                origCdate,
                "FIELD_DIFF",
                sourceKey,
                tranCode,
                tranName,
                moduleName,
                owner,
                serviceCode,
                messageType,
                destTrcd,
                compResult,
                "CONFIGURED",
                mappingStatus,
                semanticSignature,
                semanticSignatureHash,
                null,
                null,
                null,
                null,
                fields
        );
    }
}
