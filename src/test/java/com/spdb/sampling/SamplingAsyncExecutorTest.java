package com.spdb.sampling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SamplingAsyncExecutorTest {

    @Test
    void logsStackTraceWhenSamplingExecutionFails() {
        RuntimeException failure = new RuntimeException("missing result table");
        SamplingCommandService commandService = mock(SamplingCommandService.class);
        when(commandService.findByBatchId("BATCH_LOG")).thenReturn(command("BATCH_LOG"));
        doThrow(failure).when(commandService).runSamplingBatch("BATCH_LOG");

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            new SamplingAsyncExecutor(provider(commandService), directExecutor()).launch("BATCH_LOG");
        } finally {
            detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("BATCH_LOG").contains("采样批次执行失败");
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("missing result table");
        });
    }

    private ThreadPoolTaskExecutor directExecutor() {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        return executor;
    }

    private ObjectProvider<SamplingCommandService> provider(SamplingCommandService commandService) {
        return new ObjectProvider<>() {
            @Override
            public SamplingCommandService getObject(Object... args) {
                return commandService;
            }

            @Override
            public SamplingCommandService getIfAvailable() {
                return commandService;
            }

            @Override
            public SamplingCommandService getIfUnique() {
                return commandService;
            }

            @Override
            public SamplingCommandService getObject() {
                return commandService;
            }
        };
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(SamplingAsyncExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(SamplingAsyncExecutor.class);
        logger.detachAppender(appender);
    }

    private SamplingCommandRow command(String batchId) {
        return new SamplingCommandRow(
                1L,
                batchId,
                "20260611",
                null,
                null,
                null,
                "CREATED",
                null,
                "0秒",
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                null,
                null,
                LocalDateTime.now(),
                null,
                null
        );
    }
}
