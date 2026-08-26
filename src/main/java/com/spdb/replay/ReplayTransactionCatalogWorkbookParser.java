package com.spdb.replay;

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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ReplayTransactionCatalogWorkbookParser {
    public static final String[] HEADERS = {"528交易码", "交易码后缀", "528交易名称", "业务领域（沙箱）", "批次", "新核心交易码", "交易名称", "是否需要参与回放", "原服务场景码", "新服务场景码", "最近交易日期"};
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.CHINA);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    public List<ReplayTransactionCatalogForm> parse(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input)) {
            return parse(workbook);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("读取回放交易清单Excel失败: " + ex.getMessage(), ex);
        }
    }

    public List<ReplayTransactionCatalogForm> parse(InputStream input) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            return parse(workbook);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("读取回放交易清单Excel失败: " + ex.getMessage(), ex);
        }
    }

    public List<ReplayTransactionCatalogForm> parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Excel文件为空");
        return parse(new java.io.ByteArrayInputStream(bytes));
    }

    public List<ReplayTransactionCatalogForm> parse(Workbook workbook) {
        if (workbook == null || workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("工作簿为空");
        Sheet sheet = workbook.getSheetAt(0);
        Row header = sheet.getRow(0);
        if (header == null) throw new IllegalArgumentException("第1行表头不能为空");
        for (int i = 0; i < HEADERS.length; i++) {
            if (!HEADERS[i].equals(text(header, i))) throw new IllegalArgumentException("第1行表头错误，第" + (i + 1) + "列应为" + HEADERS[i]);
        }
        List<ReplayTransactionCatalogForm> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || empty(row)) continue;
            try {
                String first = text(row, 0);
                String suffix = text(row, 1);
                if (!StringUtils.hasText(first)) throw new IllegalArgumentException("A列交易码不能为空");
                String code = first + (StringUtils.hasText(suffix) ? "-" + suffix : "");
                if (!seen.add(code)) throw new IllegalArgumentException("重复交易码: " + code);
                String batch = text(row, 4);
                if (StringUtils.hasText(batch)) {
                    if (batch.length() < 2 || !(batch.startsWith("查询") || batch.startsWith("动账"))) throw new IllegalArgumentException("批次只允许查询或动账");
                    batch = batch.substring(0, 2);
                }
                String replay = text(row, 7);
                if (StringUtils.hasText(replay) && !List.of("是", "否").contains(replay)) throw new IllegalArgumentException("是否需要参与回放只允许是或否");
                String date = text(row, 10);
                if (StringUtils.hasText(date)) {
                    try { LocalDate.parse(date, DATE); } catch (DateTimeParseException ex) { throw new IllegalArgumentException("最近交易日期必须为合法yyyyMMdd"); }
                }
                result.add(new ReplayTransactionCatalogForm(code, text(row, 2), text(row, 3), batch,
                        text(row, 5), text(row, 6), replay, text(row, 8), text(row, 9), date, null));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("第" + (rowIndex + 1) + "行: " + ex.getMessage(), ex);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("工作簿没有有效数据行");
        return result;
    }

    private boolean empty(Row row) {
        for (int i = 0; i < HEADERS.length; i++) if (StringUtils.hasText(text(row, i))) return false;
        return true;
    }

    private String text(Row row, int column) {
        Cell cell = row.getCell(column);
        return cell == null ? "" : FORMATTER.formatCellValue(cell).replaceAll("[\\r\\n]+", " ").trim();
    }
}
