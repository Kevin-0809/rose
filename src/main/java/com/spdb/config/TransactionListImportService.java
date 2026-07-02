package com.spdb.config;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
        List<TransactionListEntry> entries = listParser.parse(path);
        List<List<String>> batches = mappingDocumentClient.partitionCodes(
                entries.stream().map(TransactionListEntry::tranCode).toList()
        );
        List<Workbook> workbooks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int successBatchCount = 0;
        for (List<String> batch : batches) {
            try {
                workbooks.add(workbook(mappingDocumentClient.download(batch)));
                successBatchCount++;
            } catch (RuntimeException | IOException ex) {
                failures.add(String.join(",", batch) + ": " + ex.getMessage());
            }
        }

        ConfigImportBatchResult importResult;
        try {
            importResult = importService.importParsedWorkbooks(workbooks, entries);
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
                batches.size(),
                successBatchCount,
                batches.size() - successBatchCount,
                importResult,
                failures
        );
    }

    private Workbook workbook(byte[] bytes) throws IOException {
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
}
