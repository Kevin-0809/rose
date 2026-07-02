package com.spdb.config;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class TransactionListWorkbookParser {
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.CHINA);
    private static final int FIRST_DATA_ROW = 2;
    private static final int MODULE_COLUMN = 0;
    private static final int TRAN_CODE_COLUMN = 3;
    private static final int DEV_OWNER_COLUMN = 7;
    private static final int BIZ_OWNER_COLUMN = 8;

    public List<TransactionListEntry> parse(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            return parse(workbook);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("读取交易清单Excel失败: " + ex.getMessage(), ex);
        }
    }

    public List<TransactionListEntry> parse(Workbook workbook) {
        List<TransactionListEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            for (int rowIndex = FIRST_DATA_ROW; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                String tranCode = text(row, TRAN_CODE_COLUMN);
                if (!StringUtils.hasText(tranCode) || !seen.add(tranCode)) {
                    continue;
                }
                entries.add(new TransactionListEntry(
                        tranCode,
                        text(row, MODULE_COLUMN),
                        owner(text(row, DEV_OWNER_COLUMN), text(row, BIZ_OWNER_COLUMN))
                ));
            }
        }
        return entries;
    }

    private String owner(String devOwner, String bizOwner) {
        boolean hasDevOwner = StringUtils.hasText(devOwner);
        boolean hasBizOwner = StringUtils.hasText(bizOwner);
        if (hasDevOwner && hasBizOwner) {
            return devOwner.trim() + "/" + bizOwner.trim();
        }
        if (hasDevOwner) {
            return devOwner.trim();
        }
        return hasBizOwner ? bizOwner.trim() : "";
    }

    private String text(Row row, int column) {
        Cell cell = row.getCell(column);
        return cell == null ? "" : FORMATTER.formatCellValue(cell).replaceAll("[\\r\\n]+", " ").trim();
    }
}
