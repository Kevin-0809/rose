package com.spdb.config;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class TransactionListImportService {
    private static final Logger log = LoggerFactory.getLogger(TransactionListImportService.class);

    private final TransactionListWorkbookParser listParser;
    private final MappingDocumentClient mappingDocumentClient;
    private final ConfigImportService importService;

    public TransactionListImportService(TransactionListWorkbookParser listParser,
                                        MappingDocumentClient mappingDocumentClient,
                                        ConfigImportService importService) {
        this.listParser = listParser;
        this.mappingDocumentClient = mappingDocumentClient;
        this.importService = importService;
    }

    public TransactionListImportResult importList(Path path) throws IOException {
        log.info("Transaction list import started, path={}", path);
        List<TransactionListEntry> entries = listParser.parse(path);
        log.info("Transaction list parsed, totalCount={}", entries.size());
        List<List<String>> batches = mappingDocumentClient.partitionCodes(
                entries.stream().map(TransactionListEntry::tranCode).toList()
        );
        log.info("Transaction list mapping download batches prepared, totalCount={}, batchCount={}",
                entries.size(), batches.size());
        List<Workbook> workbooks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int successBatchCount = 0;
        for (int i = 0; i < batches.size(); i++) {
            List<String> batch = batches.get(i);
            int batchNumber = i + 1;
            log.info("Transaction list mapping download batch started, batch={}/{}, tranCount={}, firstTranCode={}, lastTranCode={}",
                    batchNumber, batches.size(), batch.size(), first(batch), last(batch));
            try {
                workbooks.add(workbook(mappingDocumentClient.download(batch)));
                successBatchCount++;
                log.info("Transaction list mapping download batch completed, batch={}/{}, tranCount={}, successBatchCount={}",
                        batchNumber, batches.size(), batch.size(), successBatchCount);
            } catch (RuntimeException | IOException ex) {
                failures.add(String.join(",", batch) + ": " + ex.getMessage());
                log.warn("Transaction list mapping download batch failed, batch={}/{}, tranCount={}, firstTranCode={}, lastTranCode={}, error={}",
                        batchNumber, batches.size(), batch.size(), first(batch), last(batch), ex.toString(), ex);
            }
        }

        ConfigImportBatchResult importResult;
        try {
            log.info("Transaction list workbook import started, workbookCount={}, totalCount={}",
                    workbooks.size(), entries.size());
            importResult = importService.importParsedWorkbooks(workbooks, entries);
            log.info("Transaction list workbook import completed, resultCount={}, tranInserted={}, tranUpdated={}, fieldInserted={}, fieldUpdated={}, fieldSkipped={}",
                    importResult.results().size(),
                    importResult.tranInserted(),
                    importResult.tranUpdated(),
                    importResult.fieldInserted(),
                    importResult.fieldUpdated(),
                    importResult.fieldSkipped());
        } finally {
            close(workbooks);
        }
        Set<String> importedTranCodes = new HashSet<>();
        for (ConfigImportResult result : importResult.results()) {
            importedTranCodes.add(result.parsed().tran().tranCode());
        }
        int missingCount = 0;
        for (TransactionListEntry entry : entries) {
            if (!importedTranCodes.contains(entry.tranCode())) {
                missingCount++;
                failures.add(entry.tranCode() + ": 未在映射文档中找到交易码");
            }
        }
        if (missingCount > 0) {
            log.warn("Transaction list import found missing transaction mappings, missingCount={}, totalCount={}",
                    missingCount, entries.size());
        }
        log.info("Transaction list import completed, totalCount={}, requestBatchCount={}, successBatchCount={}, failureBatchCount={}, importedCount={}, failureCount={}",
                entries.size(),
                batches.size(),
                successBatchCount,
                batches.size() - successBatchCount,
                importedTranCodes.size(),
                failures.size());
        return new TransactionListImportResult(
                entries.size(),
                batches.size(),
                successBatchCount,
                batches.size() - successBatchCount,
                importResult,
                failures
        );
    }

    Workbook workbook(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("映射文档为空");
        }
        try {
            return WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("读取映射文档失败: " + ex.getMessage(), ex);
        }
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

    private String first(List<String> batch) {
        return batch.isEmpty() ? "" : batch.get(0);
    }

    private String last(List<String> batch) {
        return batch.isEmpty() ? "" : batch.get(batch.size() - 1);
    }
}
