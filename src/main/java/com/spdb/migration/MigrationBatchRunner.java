package com.spdb.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class MigrationBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(MigrationBatchRunner.class);
    private static final int FETCH_SIZE = 1000;

    private final MigrationCommandService commandService;
    private final MigrationShardRunner shardRunner;
    private final ThreadPoolTaskExecutor executor;

    public MigrationBatchRunner(MigrationCommandService commandService,
                                MigrationShardRunner shardRunner,
                                @Qualifier("migrationTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.commandService = commandService;
        this.shardRunner = shardRunner;
        this.executor = executor;
    }

    public void run(long commandId) {
        MigrationCommandRow command = commandService.command(commandId);
        if (command == null) {
            throw new IllegalArgumentException("Migration command not found: " + commandId);
        }
        if ("CANCEL_REQUESTED".equals(command.status())) {
            commandService.markCancelled(commandId);
            return;
        }

        if (!commandService.markRunning(commandId)) {
            if (commandService.isCancelRequested(commandId)) {
                commandService.markCancelled(commandId);
            }
            return;
        }
        log.info("Migration command started, commandId={}, commandType={}, totalShardCount={}, parallelism={}",
                commandId, command.commandType(), command.totalShardCount(), Math.max(command.parallelism(), 1));
        try {
            runRunnableShards(command, Math.max(command.parallelism(), 1));
            commandService.refreshCommandCounters(commandId);
            finishCommand(commandId);
        } catch (Exception e) {
            log.error("Migration command execution failed, commandId={}", commandId, e);
            markCommandFailedBestEffort(commandId, e);
        }
    }

    private void runRunnableShards(MigrationCommandRow command, int parallelism) {
        long commandId = command.commandId();
        List<Long> shardIds = commandService.runnableShardIds(commandId);
        Deque<CompletableFuture<Void>> inFlight = new ArrayDeque<>();
        for (Long shardId : shardIds) {
            joinCompletedCapacity(inFlight, parallelism);
            if (commandService.isCancelRequested(commandId)) {
                break;
            }
            inFlight.add(CompletableFuture.runAsync(() -> runShard(command, shardId), executor));
        }
        joinAll(inFlight);
    }

    private void joinCompletedCapacity(Deque<CompletableFuture<Void>> inFlight, int parallelism) {
        while (inFlight.size() >= parallelism) {
            inFlight.removeFirst().join();
        }
    }

    private void joinAll(Deque<CompletableFuture<Void>> inFlight) {
        while (!inFlight.isEmpty()) {
            inFlight.removeFirst().join();
        }
    }

    private void runShard(MigrationCommandRow command, long shardId) {
        long commandId = command.commandId();
        if (commandService.isCancelRequested(commandId)) {
            return;
        }
        if (!commandService.tryStartShard(shardId)) {
            return;
        }
        MigrationShardRow shard = commandService.shard(shardId);
        if (shard == null) {
            commandService.markShardFailed(shardId, "Migration shard not found: " + shardId);
            return;
        }
        logShardStarted(command, shardId, shard);
        try {
            MigrationShardResult result;
            if ("SQL".equals(command.commandType())) {
                result = shardRunner.runSql(shardId, command.responseSql(), FETCH_SIZE);
            } else if ("TRAN_CODE".equals(command.commandType())) {
                int lookbackDays = command.lookbackDays() == null
                        ? MigrationTranCodeCommandForm.DEFAULT_LOOKBACK_DAYS
                        : command.lookbackDays();
                result = shardRunner.runTranCode(shardId, shard.tranCode(), command.sampleSize(), lookbackDays);
            } else {
                result = shardRunner.run(shardId, shard.timeFrom(), shard.timeTo(), FETCH_SIZE);
            }
            if ("TRAN_CODE".equals(command.commandType()) && isEmpty(result)) {
                commandService.markShardSkipped(shardId);
                log.info("Migration shard skipped, commandId={}, shardId={}, shardSeq={}, reason=no eligible complete pairs",
                        commandId, shardId, shard.shardSeq());
            } else {
                commandService.markShardCompleted(shardId, result);
                log.info("Migration shard completed, commandId={}, shardId={}, shardSeq={}, migratedRows={}, skippedRows={}, droppedRows={}",
                        commandId, shardId, shard.shardSeq(), result.migratedRows(), result.skippedRows(), result.droppedRows());
            }
        } catch (Exception e) {
            log.error("Migration shard execution failed, commandId={}, shardId={}", commandId, shardId, e);
            commandService.markShardFailed(shardId, e.getMessage());
        }
    }

    private boolean isEmpty(MigrationShardResult result) {
        return result.migratedRows() == 0 && result.skippedRows() == 0 && result.droppedRows() == 0;
    }

    private void logShardStarted(MigrationCommandRow command, long shardId, MigrationShardRow shard) {
        if ("TRAN_CODE".equals(command.commandType())) {
            log.info("Migration shard started, commandId={}, shardId={}, shardSeq={}, commandType={}, tranCode={}, sampleSize={}, lookbackDays={}",
                    command.commandId(), shardId, shard.shardSeq(), command.commandType(), shard.tranCode(), command.sampleSize(),
                    command.lookbackDays() == null ? MigrationTranCodeCommandForm.DEFAULT_LOOKBACK_DAYS : command.lookbackDays());
            return;
        }
        log.info("Migration shard started, commandId={}, shardId={}, shardSeq={}, commandType={}, timeFrom={}, timeTo={}",
                command.commandId(), shardId, shard.shardSeq(), command.commandType(), shard.timeFrom(), shard.timeTo());
    }

    private void finishCommand(long commandId) {
        if (commandService.isCancelRequested(commandId)) {
            commandService.markCancelled(commandId);
            return;
        }
        MigrationProgressRow progress = commandService.progress(commandId);
        boolean terminalMarked;
        if (progress.failedShardCount() > 0) {
            terminalMarked = commandService.markFailed(commandId, progress.failedShardCount() + " migration shard(s) failed");
        } else {
            terminalMarked = commandService.markCompleted(commandId);
        }
        if (!terminalMarked && commandService.isCancelRequested(commandId)) {
            commandService.markCancelled(commandId);
            return;
        }
        if (terminalMarked) {
            log.info("Migration command completed, commandId={}, status={}, completedShardCount={}, failedShardCount={}, migratedRows={}, skippedRows={}, droppedRows={}",
                    commandId, progress.failedShardCount() > 0 ? "FAILED" : "COMPLETED", progress.completedShardCount(),
                    progress.failedShardCount(), progress.migratedRows(), progress.skippedRows(), progress.droppedRows());
        }
    }

    private void markCommandFailedBestEffort(long commandId, Exception cause) {
        try {
            commandService.refreshCommandCounters(commandId);
        } catch (Exception refreshException) {
            log.error("Migration command counter refresh failed after command execution error, commandId={}, originalError={}",
                    commandId, cause.toString(), refreshException);
        }
        try {
            boolean failed = commandService.markFailed(commandId, cause.getMessage());
            if (!failed && commandService.isCancelRequested(commandId)) {
                commandService.markCancelled(commandId);
            }
        } catch (Exception markFailedException) {
            log.error("Migration command failure status writeback failed, commandId={}, originalError={}",
                    commandId, cause.toString(), markFailedException);
        }
    }
}
