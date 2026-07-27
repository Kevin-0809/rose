package com.spdb.report;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** Provides capacity for transaction-detail cursor tasks independently of report command execution. */
@Configuration
class ReportExportTransactionDetailExecutionConfig {
    @Bean("reportExportTransactionDetailExecutor")
    Executor reportExportTransactionDetailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("report-export-transaction-");
        executor.initialize();
        return executor;
    }
}
