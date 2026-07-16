package com.spdb.report;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

@Component
public class BatchDomainReportAsyncExecutor implements BatchDomainReportTaskLauncher {
    private final ObjectProvider<BatchDomainReportRunner> runnerProvider;
    private final ObjectProvider<BatchDomainReportService> serviceProvider;
    private final ThreadPoolTaskExecutor executor;

    public BatchDomainReportAsyncExecutor(ObjectProvider<BatchDomainReportRunner> runnerProvider,
                                          ObjectProvider<BatchDomainReportService> serviceProvider,
                                          @Qualifier("batchDomainReportTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.runnerProvider = runnerProvider;
        this.serviceProvider = serviceProvider;
        this.executor = executor;
    }

    @Override
    public void launch(String batchId) {
        try {
            executor.execute(() -> runnerProvider.getObject().run(batchId));
        } catch (TaskRejectedException exception) {
            serviceProvider.getObject().markLaunchRejected(batchId, exception.getMessage());
            throw exception;
        }
    }
}
