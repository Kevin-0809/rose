package com.spdb.report;

public record BatchReportGapRow(
        String batchId,
        String gapType,
        String serviceCode,
        String messageType,
        String fieldKey,
        long affectedCount
) {
}
