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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ConfigWorkbookParser {
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.CHINA);

    public ParsedConfigImport parse(Path path, String originalFilename, String serviceCode,
                                    String moduleName, String owner) throws IOException {
        List<ParsedConfigImport> parsed = parseAll(path, originalFilename, serviceCode, moduleName, owner);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("未找到交易字段映射工作表");
        }
        return parsed.get(0);
    }

    public List<ParsedConfigImport> parseAll(Path path, String originalFilename, String serviceCode,
                                             String moduleName, String owner) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            return parseAll(workbook, originalFilename, serviceCode, moduleName, owner);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("读取Excel失败: " + ex.getMessage(), ex);
        }
    }

    public ParsedConfigImport parse(Workbook workbook, String originalFilename, String serviceCode,
                                    String moduleName, String owner) {
        Sheet detailSheet = findDetailSheet(workbook);
        return parseSheet(detailSheet, serviceCode, moduleName, owner);
    }

    public List<ParsedConfigImport> parseAll(Workbook workbook, String originalFilename, String serviceCode,
                                             String moduleName, String owner) {
        List<ParsedConfigImport> parsed = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (!"INDEX".equalsIgnoreCase(sheet.getSheetName())) {
                parsed.add(parseSheet(sheet, serviceCode, moduleName, owner));
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("未找到交易字段映射工作表");
        }
        return parsed;
    }

    private ParsedConfigImport parseSheet(Sheet detailSheet, String serviceCode, String moduleName, String owner) {
        String resolvedModule = textOrEmpty(moduleName);
        String resolvedOwner = textOrEmpty(owner);
        String resolvedServiceCode = requireText(firstText(serviceCode, deriveServiceCode(detailSheet)), "服务码不能为空");
        ParsedTranImport tran = parseTran(detailSheet, resolvedServiceCode, resolvedModule, resolvedOwner);
        List<ParsedFieldImport> fields = parseOutputFields(detailSheet, tran.tranCode(), resolvedServiceCode);
        return new ParsedConfigImport(tran, fields, List.of());
    }

    public static String toSoapFieldName(String fieldName) {
        if (!StringUtils.hasText(fieldName)) {
            return fieldName;
        }
        String trimmed = normalizeFieldName(fieldName);
        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT) + trimmed.substring(1);
    }

    public static String normalizeFieldName(String value) {
        return value == null ? null : value.replaceAll("\\s+", "").trim();
    }

    private ParsedTranImport parseTran(Sheet sheet, String serviceCode, String moduleName, String owner) {
        String tranCode = value(sheet, 0, 1);
        String tranName = value(sheet, 1, 1);
        String serviceName = value(sheet, 0, 11);
        String operationName = value(sheet, 1, 11);
        String sourceInterface = value(sheet, 4, 10);
        String remark = joinRemark("服务名称=" + serviceName, "服务操作=" + operationName, "原始接口=" + sourceInterface);
        return new ParsedTranImport(
                requireText(tranCode, "交易码不能为空"),
                serviceCode,
                tranName,
                moduleName,
                owner,
                remark
        );
    }

    private List<ParsedFieldImport> parseOutputFields(Sheet sheet, String tranCode, String serviceCode) {
        int outputHeader = findRow(sheet, "输出");
        if (outputHeader < 0) {
            throw new IllegalArgumentException("未找到输出字段区域");
        }
        List<ParsedFieldImport> fields = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = outputHeader + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            String sopField = cell(row, 0);
            String leftCnName = cell(row, 1);
            String leftType = cell(row, 2);
            String leftRemark = cell(row, 8);
            String targetField = cell(row, 10);
            String targetType = cell(row, 11);
            String targetCnName = cell(row, 12);
            String targetRemark = cell(row, 13);
            if (!StringUtils.hasText(sopField) || !StringUtils.hasText(targetField)) {
                continue;
            }
            if ("End".equalsIgnoreCase(targetRemark) || "End".equalsIgnoreCase(leftRemark)) {
                continue;
            }
            String normalizedSopField = normalizeFieldName(sopField);
            String normalizedTargetField = normalizeFieldName(targetField);
            String stdFieldName = normalizedSopField;
            if (!seen.add(stdFieldName)) {
                continue;
            }
            String remark = buildFieldRemark(leftType, targetType, leftRemark, targetRemark);
            fields.add(new ParsedFieldImport(
                    tranCode,
                    serviceCode,
                    stdFieldName,
                    firstText(leftCnName, targetCnName),
                    normalizedSopField,
                    toSoapFieldName(normalizedTargetField),
                    normalizedTargetField,
                    remark
            ));
        }
        return fields;
    }

    private String buildFieldRemark(String leftType, String targetType, String leftRemark, String targetRemark) {
        List<String> parts = new ArrayList<>();
        parts.add("输出");
        if ("Start".equalsIgnoreCase(leftRemark) || "Start".equalsIgnoreCase(targetRemark)
                || "array".equalsIgnoreCase(leftType) || "Array".equals(targetType)) {
            parts.add("数组");
        }
        if (StringUtils.hasText(leftType) || StringUtils.hasText(targetType)) {
            parts.add("类型=" + nullToEmpty(leftType) + "->" + nullToEmpty(targetType));
        }
        if (StringUtils.hasText(leftRemark)) {
            parts.add("原备注=" + leftRemark.trim());
        }
        if (StringUtils.hasText(targetRemark)) {
            parts.add("目标备注=" + targetRemark.trim());
        }
        return String.join("; ", parts);
    }

    private String deriveServiceCode(Sheet sheet) {
        String serviceName = value(sheet, 0, 11);
        String operationName = value(sheet, 1, 11);
        String serviceNo = textInsideLastParentheses(serviceName);
        String operationCode = textBeforeFirstParentheses(operationName);
        if (StringUtils.hasText(serviceNo) && StringUtils.hasText(operationCode)) {
            return serviceNo + operationCode;
        }
        return sheet.getSheetName();
    }

    private String textInsideLastParentheses(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        int end = value.lastIndexOf(')');
        int start = value.lastIndexOf('(', end);
        if (start < 0 || end <= start) {
            return null;
        }
        return value.substring(start + 1, end).trim();
    }

    private String textBeforeFirstParentheses(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        int start = value.indexOf('(');
        String text = start >= 0 ? value.substring(0, start) : value;
        return text.trim();
    }

    private Sheet findDetailSheet(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (!"INDEX".equalsIgnoreCase(sheet.getSheetName())) {
                return sheet;
            }
        }
        throw new IllegalArgumentException("未找到交易字段映射工作表");
    }

    private int findRow(Sheet sheet, String marker) {
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && marker.equals(cell(row, 0))) {
                return i;
            }
        }
        return -1;
    }

    private String value(Sheet sheet, int rowIndex, int colIndex) {
        Row row = sheet.getRow(rowIndex);
        return row == null ? "" : cell(row, colIndex);
    }

    private String cell(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex);
        return cell == null ? "" : FORMATTER.formatCellValue(cell).replaceAll("[\\r\\n]+", " ").trim();
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : (StringUtils.hasText(second) ? second.trim() : null);
    }

    private String textOrEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String joinRemark(String... parts) {
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (StringUtils.hasText(part) && !part.endsWith("=")) {
                values.add(part);
            }
        }
        return String.join("; ", values);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
