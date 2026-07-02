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
        try {
            MigrationShardResult result;
            if ("SQL".equals(command.commandType())) {
                result = shardRunner.runSql(shardId, command.responseSql(), FETCH_SIZE);
            } else {
                result = shardRunner.run(shardId, shard.timeFrom(), shard.timeTo(), FETCH_SIZE);
            }
            commandService.markShardCompleted(shardId, result);
        } catch (Exception e) {
            log.error("Migration shard execution failed, commandId={}, shardId={}", commandId, shardId, e);
            commandService.markShardFailed(shardId, e.getMessage());
        }
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
