package com.spdb.config;

import java.util.List;

public record TransactionListImportResult(
        int totalCount,
        int requestBatchCount,
        int successBatchCount,
        int failureBatchCount,
        ConfigImportBatchResult importResult,
        List<String> failures
) {
}
