package com.spdb.message;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class AnaMessageSendAsyncExecutor implements AnaMessageSendTaskLauncher {

    private final AnaMessageSendService service;
    private final ThreadPoolTaskExecutor executor;

    public AnaMessageSendAsyncExecutor(AnaMessageSendService service,
                                       @Qualifier("anaMessageSendTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.service = service;
        this.executor = executor;
    }

    @Override
    public void launch(String target, int concurrency, int rangeMinutes, int retries, int timeout) {
        executor.execute(() -> service.start(target, concurrency, rangeMinutes, retries, timeout));
    }
}
