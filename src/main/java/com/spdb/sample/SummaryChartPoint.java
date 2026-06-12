package com.spdb.sample;

public record SummaryChartPoint(
        String batchId,
        String origCdate,
        Long tranIssueCount,
        Long returnCodeIssueCount,
        Long issueFieldCount,
        Long fieldDiffTranCount,
        Long fullyMatchedCount,
        Long sampleGroupCount,
        Long sampleDetailCount
) {
}
