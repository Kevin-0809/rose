package com.spdb.report;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void persistsBatchRunnerStageUpdates() {
        ReportExportCommandService commandService = mock(ReportExportCommandService.class);
        ReportExportBatchRunner batchRunner = mock(ReportExportBatchRunner.class);
        when(commandService.markRunning("RPT1")).thenReturn(true);
        when(commandService.findByBatchId("RPT1")).thenReturn(new ReportExportCommandRow(
                1L, "RPT1", "20260728", "RUNNING", null, null, null, null, null));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ReportExportStage> stageConsumer = invocation.getArgument(3);
            stageConsumer.accept(ReportExportStage.TRANSACTION_DETAILS);
            stageConsumer.accept(ReportExportStage.FIELD_DETAILS);
            stageConsumer.accept(ReportExportStage.SUMMARY);
            return null;
        }).when(batchRunner).run(eq("RPT1"), eq("20260728"),
                eq(LocalDateTime.of(2026, 7, 28, 9, 30)), any());
        ReportExportAsyncExecutor executor = new ReportExportAsyncExecutor(provider(commandService), batchRunner,
                new ThreadPoolTaskExecutor(), Clock.fixed(Instant.parse("2026-07-28T09:30:00Z"), ZoneOffset.UTC));

        executor.run("RPT1");

        verify(commandService).markStage("RPT1", ReportExportStage.TRANSACTION_DETAILS);
        verify(commandService).markStage("RPT1", ReportExportStage.FIELD_DETAILS);
        verify(commandService).markStage("RPT1", ReportExportStage.SUMMARY);
        verify(commandService).markSucceeded("RPT1");
    }

    private static ObjectProvider<ReportExportCommandService> provider(ReportExportCommandService commandService) {
        return new ObjectProvider<>() {
            @Override public ReportExportCommandService getObject(Object... args) { return commandService; }
            @Override public ReportExportCommandService getObject() { return commandService; }
            @Override public ReportExportCommandService getIfAvailable() { return commandService; }
            @Override public ReportExportCommandService getIfUnique() { return commandService; }
        };
    }
}
