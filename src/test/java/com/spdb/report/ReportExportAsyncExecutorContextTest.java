package com.spdb.report;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.mockito.Mockito.mock;

class ReportExportAsyncExecutorContextTest {

    @Test
    void createsAsyncExecutorFromApplicationContext() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ReportExportCommandService.class, () -> mock(ReportExportCommandService.class));
            context.registerBean(ReportExportBatchRunner.class, () -> mock(ReportExportBatchRunner.class));
            context.registerBean("samplingTaskExecutor", ThreadPoolTaskExecutor.class, ThreadPoolTaskExecutor::new);
            context.registerBean(ReportExportAsyncExecutor.class);

            context.refresh();
        }
    }
}
