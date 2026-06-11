package com.spdb.sampling;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class SamplingAsyncExecutor implements SamplingTaskLauncher {
    private final ObjectProvider<SamplingCommandService> samplingCommandService;
    private final ThreadPoolTaskExecutor executor;

    public SamplingAsyncExecutor(ObjectProvider<SamplingCommandService> samplingCommandService,
                                 ThreadPoolTaskExecutor samplingTaskExecutor) {
        this.samplingCommandService = samplingCommandService;
        this.executor = samplingTaskExecutor;
    }

    @Override
    public void launch(String batchId) {
        executor.execute(() -> run(batchId));
    }

    private void run(String batchId) {
        SamplingCommandService commandService = samplingCommandService.getObject();
        try {
            SamplingCommandRow command = commandService.findByBatchId(batchId);
            if (command == null) {
                throw new IllegalArgumentException("采样批次不存在：" + batchId);
            }
            commandService.markRunning(batchId, null);
            commandService.runSamplingBatch(batchId);
            commandService.markCompleted(batchId);
        } catch (Exception e) {
            commandService.markFailed(batchId, e.getMessage());
        }
    }
}
