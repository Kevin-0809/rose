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
            deleteTempFile(listFile);
        }
    }

    private void runImport(long taskId, Path listFile) throws IOException {
        List<TransactionListEntry> entries = listParser.parse(listFile);
        List<List<String>> batches = mappingDocumentClient.partitionCodes(
                entries.stream().map(TransactionListEntry::tranCode).toList()
        );
        taskService.updatePlannedCounts(taskId, entries.size(), batches.size());

        List<Workbook> workbooks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int successBatchCount = 0;
        for (int i = 0; i < batches.size(); i++) {
            List<String> batch = batches.get(i);
            int batchNumber = i + 1;
            try {
                workbooks.add(downloadWorkbookWithRetry(taskId, batchNumber, batches.size(), batch));
                successBatchCount++;
                taskService.incrementCompletedBatch(taskId);
            } catch (RuntimeException | IOException ex) {
                String failure = String.join(",", batch) + ": " + ex.getMessage();
                failures.add(failure);
                taskService.incrementFailedBatch(taskId, failure);
                log.warn("Transaction list import batch failed after retries, taskId={}, batch={}/{}, tranCount={}, error={}",
                        taskId, batchNumber, batches.size(), batch.size(), ex.toString(), ex);
            }
        }

        int existingFailureCount = failures.size();
        TransactionListImportResult result = importDownloadedWorkbooks(entries, batches.size(), successBatchCount, workbooks, failures);
        String finalFailureMessage = String.join("\n", result.failures().subList(existingFailureCount, result.failures().size()));
        taskService.markCompleted(
                taskId,
                result.importResult(),
                result.importResult().results().size(),
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

    private TransactionListImportResult importDownloadedWorkbooks(List<TransactionListEntry> entries,
                                                                  int requestBatchCount,
                                                                  int successBatchCount,
                                                                  List<Workbook> workbooks,
                                                                  List<String> failures) {
        ConfigImportBatchResult importResult;
        try {
            importResult = configImportService.importParsedWorkbooks(workbooks, entries);
        } finally {
            close(workbooks);
        }
        Set<String> importedTranCodes = new HashSet<>();
        for (ConfigImportResult result : importResult.results()) {
            importedTranCodes.add(result.parsed().tran().tranCode());
        }
        for (TransactionListEntry entry : entries) {
            if (!importedTranCodes.contains(entry.tranCode())) {
                failures.add(entry.tranCode() + ": 未在映射文档中找到交易码");
            }
        }
        return new TransactionListImportResult(
                entries.size(),
                requestBatchCount,
                successBatchCount,
                requestBatchCount - successBatchCount,
                importResult,
                failures
        );
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
