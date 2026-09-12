package com.spdb.message;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnaMessageSendAsyncExecutorTest {

    @Test
    void launchRunsServiceStartOnExecutor() {
        AnaMessageSendService service = mock(AnaMessageSendService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        doAnswer(invocation -> {
            submitted.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));

        new AnaMessageSendAsyncExecutor(service, executor).launch("528", 2, 60, 3, 15);

        submitted.get().run();
        verify(service).start("528", 2, 60, 3, 15);
    }
}
