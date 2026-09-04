package com.spdb.replay;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
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
    public static final String[] HEADERS = {"528交易码", "交易后缀", "528交易名称", "业务领域（沙箱）", "批次", "新核心交易码", "交易名称", "是否需要参与回放", "原服务场景码", "新服务场景码", "最近交易日期"};
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
            if (!HEADERS[i].equals(text(header, i, 1))) throw new IllegalArgumentException("第1行表头错误，第" + (i + 1) + "列应为" + HEADERS[i]);
        }
        List<ReplayTransactionCatalogForm> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || empty(row)) continue;
            try {
                String first = text(row, 0, rowIndex + 1);
                if (!StringUtils.hasText(first)) throw new IllegalArgumentException("A列交易码不能为空");
                String code = first;
                if (!seen.add(code)) throw new IllegalArgumentException("重复交易码: " + code);
                String batch = text(row, 4, rowIndex + 1);
                if (StringUtils.hasText(batch)) {
                    if (batch.contains("动账")) batch = "动账";
                    else if (batch.contains("查询")) batch = "查询";
                    else throw new IllegalArgumentException("批次必须包含查询或动账");
                }
                String replay = text(row, 7, rowIndex + 1);
                String date = dateText(row, 10, rowIndex + 1);
                if (StringUtils.hasText(date)) {
                    try { LocalDate.parse(date, DATE); } catch (DateTimeParseException ex) { throw new IllegalArgumentException("最近交易日期必须为合法yyyyMMdd"); }
                }
                String tranName = text(row, 2, rowIndex + 1);
                String domain = text(row, 3, rowIndex + 1);
                String newCore = text(row, 5, rowIndex + 1);
                String newName = text(row, 6, rowIndex + 1);
                String originalScene = text(row, 8, rowIndex + 1);
                String newScene = text(row, 9, rowIndex + 1);
                validateLength(code, "交易码");
                validateLength(tranName, "交易名称");
                validateLength(domain, "业务领域");
                validateLength(batch, "批次");
                validateLength(newCore, "新核心交易码");
                validateLength(newName, "交易名称");
                validateLength(replay, "是否需要参与回放");
                validateLength(originalScene, "原服务场景码");
                validateLength(newScene, "新服务场景码");
                validateLength(date, "最近交易日期");
                result.add(new ReplayTransactionCatalogForm(code, tranName, domain, batch,
                        newCore, newName, replay, originalScene, newScene, date, null));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("第" + (rowIndex + 1) + "行: " + ex.getMessage(), ex);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("工作簿没有有效数据行");
        return result;
    }

    private boolean empty(Row row) {
        for (int i = 0; i < HEADERS.length; i++) if (row.getCell(i) != null && StringUtils.hasText(FORMATTER.formatCellValue(row.getCell(i)))) return false;
        return true;
    }

    private String text(Row row, int column, int rowNumber) {
        Cell cell = row.getCell(column);
        if (cell == null) return "";
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
            throw new IllegalArgumentException("第" + rowNumber + "行第" + (column + 1) + "列必须为文本，数值单元格可能丢失前导零");
        }
        return FORMATTER.formatCellValue(cell).replaceAll("[\\r\\n]+", " ").trim();
    }

    private String dateText(Row row, int column, int rowNumber) {
        Cell cell = row.getCell(column);
        if (cell == null) return "";
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
            if (!DateUtil.isCellDateFormatted(cell)) throw new IllegalArgumentException("第" + rowNumber + "行最近交易日期数值单元格必须为Excel日期");
            java.util.Date date = DateUtil.getJavaDate(cell.getNumericCellValue());
            return new java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(date);
        }
        return FORMATTER.formatCellValue(cell).replaceAll("[\\r\\n]+", " ").trim();
    }

    private void validateLength(String value, String field) {
        if (value != null && value.length() > 200) throw new IllegalArgumentException(field + "长度不能超过200字符");
    }
}
