package com.spdb.sample;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

@Service
public class SampleExcelExportService {
    private static final String[] GROUP_HEADERS = {
            "业务日期", "类型", "配置状态", "映射状态", "交易码", "服务码", "报文类型",
            "语义字段", "涉及报文", "责任人", "交易数", "字段数", "样本数", "原因"
    };
    private static final int[] GROUP_WIDTHS = {12, 16, 16, 16, 12, 28, 12, 28, 18, 14, 12, 12, 12, 28};

    private static final String[] DETAIL_HEADERS = {
            "业务日期", "类型", "配置状态", "交易码", "服务码", "报文类型",
            "流水号", "字段数", "528响应码", "528响应描述", "CCBS响应码", "CCBS响应描述",
            "责任人", "数量", "来源表", "原因"
    };
    private static final int[] DETAIL_WIDTHS = {12, 16, 16, 12, 28, 12, 24, 10, 24, 28, 24, 28, 14, 12, 18, 28};

    private static final String[] DETAIL_FIELD_HEADERS = {
            "批次", "流水号", "报文类型", "原字段名", "标准字段名", "中文名",
            "528字段值", "CCBS字段值", "映射状态", "字段序号"
    };
    private static final int[] DETAIL_FIELD_WIDTHS = {18, 24, 12, 28, 24, 18, 28, 28, 16, 10};

    public byte[] exportGroups(List<SampleGroupRow> rows) {
        return workbookToBytes("采样分组", "采样分组导出", GROUP_HEADERS, GROUP_WIDTHS, (sheet, styles) -> {
            int rowIndex = 2;
            for (SampleGroupRow row : rows) {
                writeGroupRow(sheet, styles, rowIndex++, row);
            }
        });
    }

    public byte[] exportDetails(List<SampleDetailRow> rows) {
        return workbookToBytes("采样明细", "采样明细导出", DETAIL_HEADERS, DETAIL_WIDTHS, (sheet, styles) -> {
            int rowIndex = 2;
            for (SampleDetailRow row : rows) {
                writeDetailRow(sheet, styles, rowIndex++, row);
            }
        });
    }

    public void streamGroups(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
        workbookToStream("采样分组", "采样分组导出", GROUP_HEADERS, GROUP_WIDTHS, outputStream, (sheet, styles) -> {
            int[] rowIndex = {2};
            queryService.streamGroups(criteria, row -> writeGroupRow(sheet, styles, rowIndex[0]++, row));
        });
    }

    public void streamDetails(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
        workbookToStream("采样明细", "采样明细导出", DETAIL_HEADERS, DETAIL_WIDTHS, outputStream, (sheet, styles) -> {
            int[] rowIndex = {2};
            queryService.streamDetails(criteria, row -> writeDetailRow(sheet, styles, rowIndex[0]++, row));
        });
    }

    public byte[] exportDetailFields(List<SampleDetailFieldRow> rows) {
        return workbookToBytes("字段明细", "样本字段明细导出", DETAIL_FIELD_HEADERS, DETAIL_FIELD_WIDTHS, (sheet, styles) -> {
            int rowIndex = 2;
            for (SampleDetailFieldRow row : rows) {
                writeDetailFieldRow(sheet, styles, rowIndex++, row);
            }
        });
    }

    public void streamDetailFields(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
        workbookToStream("字段明细", "样本字段明细导出", DETAIL_FIELD_HEADERS, DETAIL_FIELD_WIDTHS, outputStream, (sheet, styles) -> {
            int[] rowIndex = {2};
            queryService.streamDetailFields(criteria, row -> writeDetailFieldRow(sheet, styles, rowIndex[0]++, row));
        });
    }

    private byte[] workbookToBytes(String sheetName, String title, String[] headers, int[] widths, SheetWriter writer) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet(sheetName);
            Styles styles = createStyles(workbook);
            prepareSheet(sheet, title, headers, widths, styles);
            writer.write(sheet, styles);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成 Excel 文件失败", e);
        }
    }

    private void workbookToStream(String sheetName, String title, String[] headers, int[] widths,
                                  OutputStream outputStream, SheetWriter writer) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(500)) {
            workbook.setCompressTempFiles(true);
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet(sheetName);
            Styles styles = createStyles(workbook);
            prepareSheet(sheet, title, headers, widths, styles);
            writer.write(sheet, styles);
            workbook.write(outputStream);
            outputStream.flush();
            workbook.dispose();
        } catch (IOException e) {
            throw new IllegalStateException("生成 Excel 文件失败", e);
        }
    }

    private void prepareSheet(org.apache.poi.ss.usermodel.Sheet sheet, String title, String[] headers, int[] widths, Styles styles) {
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.length - 1));
        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(28);
        write(titleRow, 0, title, styles.title());

        Row headerRow = sheet.createRow(1);
        headerRow.setHeightInPoints(24);
        for (int i = 0; i < headers.length; i++) {
            write(headerRow, i, headers[i], styles.header());
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(0, 2);
        sheet.setAutoFilter(new CellRangeAddress(1, 1, 0, headers.length - 1));
    }

    private Styles createStyles(org.apache.poi.ss.usermodel.Workbook workbook) {
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleFont.setColor(IndexedColors.WHITE.getIndex());

        CellStyle title = workbook.createCellStyle();
        title.setFont(titleFont);
        title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        title.setAlignment(HorizontalAlignment.CENTER);
        title.setVerticalAlignment(VerticalAlignment.CENTER);

        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());

        CellStyle header = workbook.createCellStyle();
        header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.BLUE_GREY.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        border(header);

        CellStyle body = workbook.createCellStyle();
        body.setVerticalAlignment(VerticalAlignment.CENTER);
        body.setWrapText(true);
        border(body);

        CellStyle number = workbook.createCellStyle();
        number.cloneStyleFrom(body);
        number.setAlignment(HorizontalAlignment.RIGHT);
        return new Styles(title, header, body, number);
    }

    private void writeGroupRow(org.apache.poi.ss.usermodel.Sheet sheet, Styles styles, int rowIndex, SampleGroupRow row) {
        Row excelRow = sheet.createRow(rowIndex);
        int col = 0;
        write(excelRow, col++, row.origCdate(), styles.body());
        write(excelRow, col++, row.sampleType(), styles.body());
        write(excelRow, col++, row.configStatus(), styles.body());
        write(excelRow, col++, row.mappingStatus(), styles.body());
        write(excelRow, col++, row.tranCode(), styles.body());
        write(excelRow, col++, row.serviceCode(), styles.body());
        write(excelRow, col++, row.messageType(), styles.body());
        write(excelRow, col++, row.semanticFieldNames(), styles.body());
        write(excelRow, col++, row.messageTypes(), styles.body());
        write(excelRow, col++, row.owner(), styles.body());
        write(excelRow, col++, row.affectedTranCount(), styles.number());
        write(excelRow, col++, row.affectedFieldCount(), styles.number());
        write(excelRow, col++, row.sampleCount(), styles.number());
        write(excelRow, col, row.reason(), styles.body());
    }

    private void writeDetailRow(org.apache.poi.ss.usermodel.Sheet sheet, Styles styles, int rowIndex, SampleDetailRow row) {
        Row excelRow = sheet.createRow(rowIndex);
        int col = 0;
        write(excelRow, col++, row.origCdate(), styles.body());
        write(excelRow, col++, row.sampleType(), styles.body());
        write(excelRow, col++, row.configStatus(), styles.body());
        write(excelRow, col++, row.tranCode(), styles.body());
        write(excelRow, col++, row.serviceCode(), styles.body());
        write(excelRow, col++, row.messageType(), styles.body());
        write(excelRow, col++, row.tranSeqNo(), styles.body());
        write(excelRow, col++, row.fieldCount(), styles.number());
        write(excelRow, col++, row.origErrorCode(), styles.body());
        write(excelRow, col++, row.origErrorDesc(), styles.body());
        write(excelRow, col++, row.destErrorCode(), styles.body());
        write(excelRow, col++, row.destErrorDesc(), styles.body());
        write(excelRow, col++, row.owner(), styles.body());
        write(excelRow, col++, row.affectedCount(), styles.number());
        write(excelRow, col++, row.sourceTable(), styles.body());
        write(excelRow, col, row.reason(), styles.body());
    }

    private void writeDetailFieldRow(org.apache.poi.ss.usermodel.Sheet sheet, Styles styles, int rowIndex, SampleDetailFieldRow row) {
        Row excelRow = sheet.createRow(rowIndex);
        int col = 0;
        write(excelRow, col++, row.batchId(), styles.body());
        write(excelRow, col++, row.mesgSeq(), styles.body());
        write(excelRow, col++, row.messageType(), styles.body());
        write(excelRow, col++, row.rawFieldName(), styles.body());
        write(excelRow, col++, row.stdFieldName(), styles.body());
        write(excelRow, col++, row.fieldCnName(), styles.body());
        write(excelRow, col++, row.origFieldValue(), styles.body());
        write(excelRow, col++, row.destFieldValue(), styles.body());
        write(excelRow, col++, row.mappingStatus(), styles.body());
        write(excelRow, col, row.fieldIndex(), styles.number());
    }

    private void border(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
    }

    private void write(Row row, int column, Object value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(value == null ? "" : value.toString());
        }
        cell.setCellStyle(style);
    }

    private record Styles(CellStyle title, CellStyle header, CellStyle body, CellStyle number) {}

    @FunctionalInterface
    private interface SheetWriter {
        void write(org.apache.poi.ss.usermodel.Sheet sheet, Styles styles);
    }
}
