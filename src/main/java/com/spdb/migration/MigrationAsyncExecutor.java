package com.spdb.migration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class MigrationAsyncExecutor implements MigrationTaskLauncher {
    private final ObjectProvider<MigrationBatchRunner> batchRunnerProvider;
    private final ThreadPoolTaskExecutor executor;

    public MigrationAsyncExecutor(ObjectProvider<MigrationBatchRunner> batchRunnerProvider,
                                  @Qualifier("migrationCommandTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.batchRunnerProvider = batchRunnerProvider;
        this.executor = executor;
    }

    @Override
    public void launch(long commandId) {
        executor.execute(() -> batchRunnerProvider.getObject().run(commandId));
    }
}
