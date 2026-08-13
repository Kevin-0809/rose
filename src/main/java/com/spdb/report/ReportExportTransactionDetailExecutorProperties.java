package com.spdb.report;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rose.report-export.transaction-detail.executor")
record ReportExportTransactionDetailExecutorProperties(
        Integer corePoolSize,
        Integer maxPoolSize,
        Integer queueCapacity,
        String threadNamePrefix
) {
    ReportExportTransactionDetailExecutorProperties {
        if (corePoolSize == null || corePoolSize <= 0) {
            corePoolSize = 64;
        }
        if (maxPoolSize == null || maxPoolSize < corePoolSize) {
            maxPoolSize = corePoolSize;
        }
        if (queueCapacity == null || queueCapacity < 0) {
            queueCapacity = 256;
        }
        if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
            threadNamePrefix = "report-export-transaction-";
        }
    }
}
