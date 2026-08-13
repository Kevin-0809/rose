package com.spdb.report;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Provides capacity for transaction-detail cursor tasks independently of report command execution. */
@Configuration
class ReportExportTransactionDetailExecutionConfig {
    @Bean("reportExportTransactionDetailExecutor")
    ThreadPoolTaskExecutor reportExportTransactionDetailExecutor(
            ReportExportTransactionDetailExecutorProperties properties
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix(properties.threadNamePrefix());
        executor.initialize();
        return executor;
    }
}
