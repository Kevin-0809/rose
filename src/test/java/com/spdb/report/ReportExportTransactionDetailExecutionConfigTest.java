package com.spdb.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class ReportExportTransactionDetailExecutionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class, ReportExportTransactionDetailExecutionConfig.class);

    @Test
    void createsExecutorWithDefaultSettings() {
        contextRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(
                    "reportExportTransactionDetailExecutor",
                    ThreadPoolTaskExecutor.class
            );

            assertThat(executor.getCorePoolSize()).isEqualTo(64);
            assertThat(executor.getMaxPoolSize()).isEqualTo(64);
            assertThat(executor.getQueueCapacity()).isEqualTo(256);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("report-export-transaction-");
        });
    }

    @Test
    void appliesConfiguredExecutorSettings() {
        contextRunner
                .withPropertyValues(
                        "rose.report-export.transaction-detail.executor.core-pool-size=3",
                        "rose.report-export.transaction-detail.executor.max-pool-size=5",
                        "rose.report-export.transaction-detail.executor.queue-capacity=7",
                        "rose.report-export.transaction-detail.executor.thread-name-prefix=test-report-export-"
                )
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean(
                            "reportExportTransactionDetailExecutor",
                            ThreadPoolTaskExecutor.class
                    );

                    assertThat(executor.getCorePoolSize()).isEqualTo(3);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(5);
                    assertThat(executor.getQueueCapacity()).isEqualTo(7);
                    assertThat(executor.getThreadNamePrefix()).isEqualTo("test-report-export-");
                });
    }

    @EnableConfigurationProperties(ReportExportTransactionDetailExecutorProperties.class)
    static class TestConfig {
    }
}
