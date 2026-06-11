package com.spdb.sample;

public record SummaryChartPoint(
        String batchId,
        String origCdate,
        Long issueFieldCount,
        Long fullyMatchedCount,
        Long sampleGroupCount,
        Long sampleDetailCount
) {
}
