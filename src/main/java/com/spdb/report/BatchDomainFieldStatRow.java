package com.spdb.report;

public record BatchDomainFieldStatRow(
        String batchId,
        String moduleName,
        long totalFieldCount,
        long diffFieldCount,
        long noDiffFieldCount
) {
}
