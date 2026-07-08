package com.spdb.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class TransactionListImportAsyncExecutor implements TransactionListImportTaskLauncher {
    private final ObjectProvider<TransactionListImportTaskRunner> runnerProvider;
    private final ThreadPoolTaskExecutor executor;

    public TransactionListImportAsyncExecutor(ObjectProvider<TransactionListImportTaskRunner> runnerProvider,
                                              @Qualifier("transactionListImportTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.runnerProvider = runnerProvider;
        this.executor = executor;
    }

    @Override
    public void launch(long taskId) {
        executor.execute(() -> runnerProvider.getObject().run(taskId));
    }
}
