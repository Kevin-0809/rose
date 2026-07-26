package com.spdb.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
public class ReportExportAsyncExecutor implements ReportExportTaskLauncher {
    private static final Logger log = LoggerFactory.getLogger(ReportExportAsyncExecutor.class);

    private final ObjectProvider<ReportExportCommandService> commandServiceProvider;
    private final ReportExportBatchRunner batchRunner;
    private final ThreadPoolTaskExecutor executor;
    private final Clock clock;

    @Autowired
    public ReportExportAsyncExecutor(ObjectProvider<ReportExportCommandService> commandServiceProvider,
                                     ReportExportBatchRunner batchRunner,
                                     @Qualifier("samplingTaskExecutor") ThreadPoolTaskExecutor samplingTaskExecutor) {
        this(commandServiceProvider, batchRunner, samplingTaskExecutor, Clock.systemDefaultZone());
    }

    ReportExportAsyncExecutor(ObjectProvider<ReportExportCommandService> commandServiceProvider,
                              ReportExportBatchRunner batchRunner, ThreadPoolTaskExecutor executor, Clock clock) {
        this.commandServiceProvider = commandServiceProvider;
        this.batchRunner = batchRunner;
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public void launch(String batchId) {
        executor.execute(() -> run(batchId));
    }

    void run(String batchId) {
        ReportExportCommandService commandService = commandServiceProvider.getObject();
        if (!commandService.markRunning(batchId)) return;
        try {
            ReportExportCommandRow command = commandService.findByBatchId(batchId);
            if (command == null) throw new IllegalArgumentException("报表导出批次不存在：" + batchId);
            batchRunner.run(batchId, command.reportDate(), LocalDateTime.now(clock));
            commandService.markSucceeded(batchId);
        } catch (RuntimeException exception) {
            log.error("报表明细导出失败，batchId={}", batchId, exception);
            commandService.markFailed(batchId, exception.getMessage());
        }
    }
}
