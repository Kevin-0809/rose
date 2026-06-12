package com.spdb.sampling.engine;

import java.util.List;

public record SampleDetailDraft(
        SourceKey sourceKey,
        String tranSeqNo,
        String messageType,
        String destTrcd,
        String compResult,
        String configStatus,
        String origErrorCode,
        String origErrorDesc,
        String destErrorCode,
        String destErrorDesc,
        List<SampleDetailFieldDraft> fields
) {
}
