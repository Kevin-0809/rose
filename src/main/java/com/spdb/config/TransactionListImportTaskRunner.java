package com.spdb.config;

import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class TransactionListImportTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(TransactionListImportTaskRunner.class);
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;

    private final TransactionListImportTaskService taskService;
    private final TransactionListWorkbookParser listParser;
    private final MappingDocumentClient mappingDocumentClient;
    private final TransactionListImportService importService;
    private final ConfigImportService configImportService;
    private final boolean retrySleepEnabled;

    @Autowired
    public TransactionListImportTaskRunner(TransactionListImportTaskService taskService,
                                           TransactionListWorkbookParser listParser,
                                           MappingDocumentClient mappingDocumentClient,
                                           TransactionListImportService importService,
                                           ConfigImportService configImportService) {
        this(taskService, listParser, mappingDocumentClient, importService, configImportService, true);
    }

    TransactionListImportTaskRunner(TransactionListImportTaskService taskService,
                                    TransactionListWorkbookParser listParser,
                                    MappingDocumentClient mappingDocumentClient,
                                    TransactionListImportService importService,
                                    ConfigImportService configImportService,
                                    boolean retrySleepEnabled) {
        this.taskService = taskService;
        this.listParser = listParser;
        this.mappingDocumentClient = mappingDocumentClient;
        this.importService = importService;
        this.configImportService = configImportService;
        this.retrySleepEnabled = retrySleepEnabled;
    }

    public void run(long taskId) {
        TransactionListImportTaskRow task = taskService.task(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Transaction list import task not found: " + taskId);
        }
        Path listFile = Path.of(task.listFilePath());
        try {
            if (!taskService.markRunning(taskId)) {
                return;
            }
            runImport(taskId, listFile);
        } catch (Exception ex) {
            log.error("Transaction list import task failed, taskId={}", taskId, ex);
            taskService.markFailed(taskId, ex.getMessage());
        } finally {
            TransactionListImportTaskRow latest = taskService.task(taskId);
            if (latest != null && "COMPLETED".equals(latest.status())) {
                deleteTempFile(listFile);
            }
        }
    }

    private void runImport(long taskId, Path listFile) throws IOException {
        List<TransactionListEntry> entries = listParser.parse(listFile);
        Set<String> alreadyImported = taskService.importedTranCodes(taskId);
        List<TransactionListEntry> pendingEntries = entries.stream()
                .filter(entry -> !alreadyImported.contains(entry.tranCode()))
                .toList();
        List<List<String>> batches = mappingDocumentClient.partitionCodes(
                pendingEntries.stream().map(TransactionListEntry::tranCode).toList()
        );
        taskService.updatePlannedCounts(taskId, entries.size(), batches.size());

        List<String> failures = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            List<String> batch = batches.get(i);
            int batchNumber = i + 1;
            try {
                Workbook workbook = downloadWorkbookWithRetry(taskId, batchNumber, batches.size(), batch);
                ConfigImportBatchResult chunkResult = importDownloadedWorkbooks(
                        pendingEntriesForBatch(pendingEntries, batch),
                        List.of(workbook)
                );
                taskService.recordSuccessfulImportChunk(taskId, chunkResult);
                taskService.incrementCompletedBatch(taskId);
            } catch (RuntimeException | IOException ex) {
                String failure = String.join(",", batch) + ": " + ex.getMessage();
                failures.add(failure);
                taskService.incrementFailedBatch(taskId, failure);
                log.warn("Transaction list import batch failed after retries, taskId={}, batch={}/{}, tranCount={}, error={}",
                        taskId, batchNumber, batches.size(), batch.size(), ex.toString(), ex);
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(String.join("\n", failures));
        }
        String finalFailureMessage = String.join("\n", failures);
        taskService.markCompleted(
                taskId,
                new ConfigImportBatchResult(0, 0, 0, 0, 0, List.of()),
                taskService.progress(taskId).importedCount(),
                finalFailureMessage
        );
    }

    private Workbook downloadWorkbookWithRetry(long taskId, int batchNumber, int batchCount, List<String> batch) throws IOException {
        RuntimeException runtimeFailure = null;
        IOException ioFailure = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                log.info("Transaction list import download attempt, taskId={}, batch={}/{}, attempt={}/{}, tranCount={}",
                        taskId, batchNumber, batchCount, attempt, MAX_DOWNLOAD_ATTEMPTS, batch.size());
                return importService.workbook(mappingDocumentClient.download(batch));
            } catch (IOException ex) {
                ioFailure = ex;
            } catch (RuntimeException ex) {
                runtimeFailure = ex;
            }
            if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                sleepBeforeRetry(attempt);
            }
        }
        if (ioFailure != null) {
            throw ioFailure;
        }
        throw runtimeFailure;
    }

    private ConfigImportBatchResult importDownloadedWorkbooks(List<TransactionListEntry> entries,
                                                              List<Workbook> workbooks) {
        try {
            ConfigImportBatchResult importResult = configImportService.importParsedWorkbooks(workbooks, entries);
            Set<String> importedTranCodes = new HashSet<>();
            for (ConfigImportResult result : importResult.results()) {
                importedTranCodes.add(result.parsed().tran().tranCode());
            }
            List<String> missing = new ArrayList<>();
            for (TransactionListEntry entry : entries) {
                if (!importedTranCodes.contains(entry.tranCode())) {
                    missing.add(entry.tranCode() + ": 未在映射文档中找到交易码");
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException(String.join("\n", missing));
            }
            return importResult;
        } finally {
            close(workbooks);
        }
    }

    private List<TransactionListEntry> pendingEntriesForBatch(List<TransactionListEntry> pendingEntries, List<String> batch) {
        Set<String> batchCodes = new HashSet<>(batch);
        return pendingEntries.stream()
                .filter(entry -> batchCodes.contains(entry.tranCode()))
                .toList();
    }

    private void close(List<Workbook> workbooks) {
        for (Workbook workbook : workbooks) {
            try {
                workbook.close();
            } catch (IOException ignored) {
                // Import results should not be converted to failures by close-time cleanup.
            }
        }
    }

    private void sleepBeforeRetry(int attempt) {
        if (!retrySleepEnabled) {
            return;
        }
        try {
            Thread.sleep((long) Math.pow(2, attempt - 1) * 1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Transaction list import retry interrupted", ex);
        }
    }

    private void deleteTempFile(Path listFile) {
        try {
            Files.deleteIfExists(listFile);
        } catch (IOException ex) {
            log.warn("Transaction list import temp file delete failed, path={}, error={}", listFile, ex.toString(), ex);
        }
    }
}
