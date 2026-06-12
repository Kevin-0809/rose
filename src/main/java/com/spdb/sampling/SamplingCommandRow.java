package com.spdb.sampling;

import java.time.LocalDateTime;

public record SamplingCommandRow(
        Long commandId,
        String batchId,
        String origCdate,
        String sampleType,
        String tranCode,
        String serviceCode,
        String status,
        Long jobExecutionId,
        String durationText,
        Long totalTranCount,
        Long compResult1Count,
        Long compResult2Count,
        Long compResult3Count,
        Long compResult4Count,
        Long compResult8Count,
        Long passTranCount,
        Long tranIssueCount,
        Long returnCodeIssueCount,
        Long issueFieldCount,
        Long fieldDiffTranCount,
        Long unconfiguredServiceCount,
        Long unmappedFieldCount,
        Long fullyMatchedCount,
        Long fieldDiffCount,
        Long sampleGroupCount,
        Long sampleDetailCount,
        String errorMessage,
        String remark,
        LocalDateTime createdTime,
        LocalDateTime startedTime,
        LocalDateTime endedTime
) {
}
