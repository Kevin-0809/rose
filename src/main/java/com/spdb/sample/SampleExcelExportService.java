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

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class SampleExcelExportService {
    private final ThreadLocal<Map<TransactionDiffCategory, SheetState>> transactionDiffSheets = new ThreadLocal<>();
    private final Clock clock;

    private static final String TXT_DELIMITER = "!";
    private static final DateTimeFormatter EXPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private static final String[] GROUP_HEADERS = {
            "业务日期", "类型", "配置状态", "映射状态", "交易码", "服务码", "报文类型",
            "语义字段", "涉及报文", "责任人", "交易数", "字段数", "样本数", "原因"
    };
    private static final int[] GROUP_WIDTHS = {12, 16, 16, 16, 12, 28, 12, 28, 18, 14, 12, 12, 12, 28};

    private static final String[] DETAIL_HEADERS = {
            "业务日期", "类型", "配置状态", "交易码", "服务码", "报文类型",
            "流水号", "SOP字段名", "SOAP字段名", "BizJSON字段名", "字段中文名",
            "字段数", "528响应码", "528响应描述", "CCBS响应码", "CCBS响应描述",
            "责任人", "数量", "来源表", "原因"
    };
    private static final int[] DETAIL_WIDTHS = {12, 16, 16, 12, 28, 12, 24, 24, 24, 28, 18, 10, 24, 28, 24, 28, 14, 12, 18, 28};

    private static final String[] TRANSACTION_DIFF_HEADERS = {
            "业务日期", "批次", "交易码", "服务码", "报文类型", "流水号", "交易结果",
            "528响应码", "528响应描述", "CCBS响应码", "CCBS响应描述", "责任人", "数量"
    };
    private static final int[] TRANSACTION_DIFF_WIDTHS = {12, 22, 12, 28, 12, 24, 12, 24, 28, 24, 28, 14, 12};
    private static final String[] TRANSACTION_DIFF_TEXT_HEADERS = {
            "业务日期", "批次", "交易码", "服务码", "报文类型", "流水号", "交易结果",
            "528响应码", "528响应描述", "CCBS响应码", "CCBS响应描述", "责任人", "数量"
    };
    private static final String[] TRANSACTION_SUCCESS_STAT_HEADERS = {
            "业务日期", "批次", "交易码", "服务码", "报文类型", "成功数量", "接口字段总数",
            "比对字段总数", "差异字段数", "比对字段差异总数", "单字段差异>=1%", "单字段差异<1%",
            "领域", "责任人"
    };

    private static final String[] DETAIL_FIELD_HEADERS = {
            "批次", "流水号", "报文类型", "原字段名", "标准字段名", "中文名",
            "528字段值", "CCBS字段值", "映射状态", "字段序号"
    };
    private static final int[] DETAIL_FIELD_WIDTHS = {18, 24, 12, 28, 24, 18, 28, 28, 16, 10};

    private static final String[] FIELD_DIFF_EXPORT_HEADERS = {
            "业务日期", "批次号", "交易码", "服务码", "报文类型",
            "SOP字段名", "SOAP字段名", "BizJSON字段名", "字段中文名", "映射状态",
            "样例流水号", "528值", "CCBS值", "责任人", "影响交易笔数",
            "审核人", "是否修复", "差异分类", "差异说明"
    };
    private static final int[] FIELD_DIFF_EXPORT_WIDTHS = {
            12, 22, 12, 28, 12, 24, 24, 28, 18, 16, 24, 28, 28, 14, 14, 14, 12, 14, 32
    };
    private static final String[] SERVICE_REPORT_HEADERS = {
            "业务日期", "批次", "交易码", "服务码", "交易名称", "责任人",
            "发起交易数", "通过交易数", "通过率",
            "原失败新成功数", "原失败新成功占比",
            "原成功新失败数", "原成功新失败占比",
            "都失败数", "都失败占比",
            "都成功数", "都成功占比",
            "响应码不一致数", "响应码不一致占比",
            "响应码问题数", "响应码问题占比",
            "交易问题数", "交易问题占比",
            "字段差异流水数", "字段差异流水占比",
            "完全匹配数", "问题字段数"
    };
    private static final int[] SERVICE_REPORT_WIDTHS = {
            12, 22, 12, 28, 24, 14,
            12, 12, 12, 16, 16, 16, 16, 12, 12, 12, 12, 16, 16, 16, 16, 12, 12, 18, 18, 12, 12
    };

    public SampleExcelExportService() {
        this(Clock.systemDefaultZone());
    }

    SampleExcelExportService(Clock clock) {
        this.clock = clock;
    }

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

    public void streamTransactionDiffExport(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
        String timestamp = LocalDateTime.now(clock).format(EXPORT_TIMESTAMP);
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("transdiff-export-");
            Path ccbsFile = tempDir.resolve("transdiff_ccbs_" + timestamp + ".txt");
            Path cbspFile = tempDir.resolve("transdiff_cbsp_" + timestamp + ".txt");
            Path bothFile = tempDir.resolve("transdiff_both_" + timestamp + ".txt");
            Path successFile = tempDir.resolve("transdiff_success_" + timestamp + ".txt");
            long[] counts = new long[3];
            try (BufferedWriter ccbs = Files.newBufferedWriter(ccbsFile, StandardCharsets.UTF_8);
                 BufferedWriter cbsp = Files.newBufferedWriter(cbspFile, StandardCharsets.UTF_8);
                 BufferedWriter both = Files.newBufferedWriter(bothFile, StandardCharsets.UTF_8);
                 BufferedWriter success = Files.newBufferedWriter(successFile, StandardCharsets.UTF_8)) {
                appendTextLine(ccbs, (Object[]) TRANSACTION_DIFF_TEXT_HEADERS);
                appendTextLine(cbsp, (Object[]) TRANSACTION_DIFF_TEXT_HEADERS);
                appendTextLine(both, (Object[]) TRANSACTION_DIFF_TEXT_HEADERS);
                appendTextLine(success, (Object[]) TRANSACTION_SUCCESS_STAT_HEADERS);
                queryService.streamTransactionDiffExport(criteria, row -> writeTransactionTextRow(row, ccbs, cbsp, both, counts));
                queryService.streamTransactionSuccessStats(criteria, row -> writeTransactionSuccessStatRow(success, row));
            }
            try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
                writeTransactionStatEntry(zip, timestamp, counts);
                writeZipFile(zip, ccbsFile);
                writeZipFile(zip, cbspFile);
                writeZipFile(zip, bothFile);
                writeZipFile(zip, successFile);
                zip.finish();
            }
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException("生成交易级差异文本文件失败", e);
        } finally {
            deleteDirectoryQuietly(tempDir);
        }
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

    public void streamFieldDiffExport(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
        workbookToStream("字段级差异明细", "字段级差异合并导出", FIELD_DIFF_EXPORT_HEADERS, FIELD_DIFF_EXPORT_WIDTHS, outputStream, (sheet, styles) -> {
            int[] rowIndex = {2};
            queryService.streamFieldDiffExport(criteria, row -> writeFieldDiffExportRow(sheet, styles, rowIndex[0]++, row));
        });
    }

    public void streamServiceReport(SampleQueryService queryService, SamplingSummarySearchCriteria criteria, OutputStream outputStream) {
        workbookToStream("服务码汇报", "采样服务码维度汇报", SERVICE_REPORT_HEADERS, SERVICE_REPORT_WIDTHS, outputStream, (sheet, styles) -> {
            int[] rowIndex = {2};
            queryService.streamServiceReport(criteria, row -> writeServiceReportRow(sheet, styles, rowIndex[0]++, row));
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
            Styles styles = createStyles(workbook);
            org.apache.poi.ss.usermodel.Sheet sheet;
            if (headers == TRANSACTION_DIFF_HEADERS) {
                Map<TransactionDiffCategory, SheetState> sheets = new LinkedHashMap<>();
                for (TransactionDiffCategory category : TransactionDiffCategory.values()) {
                    org.apache.poi.ss.usermodel.Sheet categorySheet = workbook.createSheet(category.sheetName());
                    prepareSheet(categorySheet, category.sheetName(), headers, widths, styles);
                    sheets.put(category, new SheetState(categorySheet));
                }
                transactionDiffSheets.set(sheets);
                sheet = sheets.get(TransactionDiffCategory.ORIG_SUCCESS_DEST_FAIL).sheet();
            } else {
                sheet = workbook.createSheet(sheetName);
                prepareSheet(sheet, title, headers, widths, styles);
            }
            try {
                writer.write(sheet, styles);
            } finally {
                transactionDiffSheets.remove();
            }
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

        CellStyle percent = workbook.createCellStyle();
        percent.cloneStyleFrom(number);
        percent.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
        return new Styles(title, header, body, number, percent);
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
        write(excelRow, col++, row.sopFieldName(), styles.body());
        write(excelRow, col++, row.soapFieldName(), styles.body());
        write(excelRow, col++, row.bizjsonFieldName(), styles.body());
        write(excelRow, col++, row.fieldCnName(), styles.body());
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

    private void writeTransactionDiffRow(org.apache.poi.ss.usermodel.Sheet sheet, Styles styles, int rowIndex, SampleDetailRow row) {
        Map<TransactionDiffCategory, SheetState> categorySheets = transactionDiffSheets.get();
        if (categorySheets != null) {
            SheetState state = categorySheets.get(TransactionDiffCategory.of(row));
            sheet = state.sheet();
            rowIndex = state.nextRowIndex();
        }
        Row excelRow = sheet.createRow(rowIndex);
        int col = 0;
        write(excelRow, col++, row.origCdate(), styles.body());
        write(excelRow, col++, row.batchId(), styles.body());
        write(excelRow, col++, row.tranCode(), styles.body());
        write(excelRow, col++, row.serviceCode(), styles.body());
        write(excelRow, col++, row.messageType(), styles.body());
        write(excelRow, col++, row.tranSeqNo(), styles.body());
        write(excelRow, col++, row.compResult(), styles.body());
        write(excelRow, col++, row.origErrorCode(), styles.body());
        write(excelRow, col++, row.origErrorDesc(), styles.body());
        write(excelRow, col++, row.destErrorCode(), styles.body());
        write(excelRow, col++, row.destErrorDesc(), styles.body());
        write(excelRow, col++, row.owner(), styles.body());
        write(excelRow, col, row.affectedCount(), styles.number());
    }

    private void writeTransactionStatEntry(ZipOutputStream zip, String timestamp, long[] counts) throws IOException {
        StringBuilder text = new StringBuilder();
        appendTextLine(text, "类型", "数量");
        appendTextLine(text, "528成功CCBS失败", counts[0]);
        appendTextLine(text, "CCBS成功528失败", counts[1]);
        appendTextLine(text, "528与CCBS均失败但错误码不一致", counts[2]);
        writeZipText(zip, "transdiff_stat_" + timestamp + ".txt", text);
    }

    private void writeTransactionTextRow(SampleDetailRow row, BufferedWriter ccbs, BufferedWriter cbsp,
                                         BufferedWriter both, long[] counts) {
        try {
            TransactionDiffCategory category = TransactionDiffCategory.of(row);
            if (category == TransactionDiffCategory.ORIG_SUCCESS_DEST_FAIL) {
                appendTransactionTextLine(ccbs, row);
                counts[0]++;
            } else if (category == TransactionDiffCategory.ORIG_FAIL_DEST_SUCCESS) {
                appendTransactionTextLine(cbsp, row);
                counts[1]++;
            } else if (category == TransactionDiffCategory.BOTH_FAIL) {
                appendTransactionTextLine(both, row);
                counts[2]++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void appendTransactionTextLine(BufferedWriter writer, SampleDetailRow row) throws IOException {
        appendTextLine(writer,
                row.origCdate(),
                row.batchId(),
                row.tranCode(),
                row.serviceCode(),
                row.messageType(),
                row.tranSeqNo(),
                row.compResult(),
                row.origErrorCode(),
                row.origErrorDesc(),
                row.destErrorCode(),
                row.destErrorDesc(),
                row.owner(),
                row.affectedCount()
        );
    }

    private void writeTransactionSuccessStatRow(BufferedWriter writer, TransactionSuccessStatRow row) {
        try {
            appendTextLine(writer,
                    row.origCdate(),
                    row.batchId(),
                    row.tranCode(),
                    row.serviceCode(),
                    row.messageType(),
                    row.successCount(),
                    row.interfaceFieldCount(),
                    row.comparedFieldCount(),
                    row.diffFieldCount(),
                    row.comparedFieldDiffCount(),
                    row.highRatioFieldCount(),
                    row.lowRatioFieldCount(),
                    row.moduleName(),
                    row.owner()
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeZipText(ZipOutputStream zip, String filename, CharSequence text) throws IOException {
        zip.putNextEntry(new ZipEntry(filename));
        zip.write(text.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void writeZipFile(ZipOutputStream zip, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private void appendTextLine(StringBuilder text, Object... columns) {
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                text.append(TXT_DELIMITER);
            }
            text.append(columns[i] == null ? "" : columns[i]);
        }
        text.append('\n');
    }

    private void appendTextLine(BufferedWriter writer, Object... columns) throws IOException {
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                writer.write(TXT_DELIMITER);
            }
            writer.write(columns[i] == null ? "" : columns[i].toString());
        }
        writer.write('\n');
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
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

    private void writeFieldDiffExportRow(org.apache.poi.ss.usermodel.Sheet sheet, Styles styles, int rowIndex, SampleFieldDiffRow row) {
        Row excelRow = sheet.createRow(rowIndex);
        int col = 0;
        write(excelRow, col++, row.origCdate(), styles.body());
        write(excelRow, col++, row.batchId(), styles.body());
        write(excelRow, col++, row.tranCode(), styles.body());
        write(excelRow, col++, row.serviceCode(), styles.body());
        write(excelRow, col++, row.messageType(), styles.body());
        write(excelRow, col++, row.sopFieldName(), styles.body());
        write(excelRow, col++, row.soapFieldName(), styles.body());
        write(excelRow, col++, row.bizjsonFieldName(), styles.body());
        write(excelRow, col++, row.fieldCnName(), styles.body());
        write(excelRow, col++, mappingStatusText(row.mappingStatus()), styles.body());
        write(excelRow, col++, row.sampleTranSeqNo(), styles.body());
        write(excelRow, col++, row.origFieldValue(), styles.body());
        write(excelRow, col++, row.destFieldValue(), styles.body());
        write(excelRow, col++, row.owner(), styles.body());
        write(excelRow, col++, row.affectedTranCount(), styles.number());
        write(excelRow, col++, "", styles.body());
        write(excelRow, col++, "否", styles.body());
        write(excelRow, col++, "", styles.body());
        write(excelRow, col, "", styles.body());
    }

    private String mappingStatusText(String mappingStatus) {
        if ("MAPPED".equals(mappingStatus)) {
            return "已映射";
        }
        if ("UNMAPPED".equals(mappingStatus)) {
            return "未映射";
        }
        if ("MIXED".equals(mappingStatus)) {
            return "混合";
        }
        return mappingStatus;
    }

    private void writeServiceReportRow(org.apache.poi.ss.usermodel.Sheet sheet, Styles styles, int rowIndex, SamplingServiceReportRow row) {
        Row excelRow = sheet.createRow(rowIndex);
        int col = 0;
        write(excelRow, col++, row.origCdate(), styles.body());
        write(excelRow, col++, row.batchId(), styles.body());
        write(excelRow, col++, row.tranCode(), styles.body());
        write(excelRow, col++, row.serviceCode(), styles.body());
        write(excelRow, col++, row.tranName(), styles.body());
        write(excelRow, col++, row.owner(), styles.body());
        write(excelRow, col++, row.totalTranCount(), styles.number());
        write(excelRow, col++, row.passTranCount(), styles.number());
        write(excelRow, col++, row.rate(row.passTranCount()), styles.percent());
        write(excelRow, col++, row.compResult1Count(), styles.number());
        write(excelRow, col++, row.rate(row.compResult1Count()), styles.percent());
        write(excelRow, col++, row.compResult2Count(), styles.number());
        write(excelRow, col++, row.rate(row.compResult2Count()), styles.percent());
        write(excelRow, col++, row.compResult3Count(), styles.number());
        write(excelRow, col++, row.rate(row.compResult3Count()), styles.percent());
        write(excelRow, col++, row.compResult4Count(), styles.number());
        write(excelRow, col++, row.rate(row.compResult4Count()), styles.percent());
        write(excelRow, col++, row.compResult8Count(), styles.number());
        write(excelRow, col++, row.rate(row.compResult8Count()), styles.percent());
        write(excelRow, col++, row.returnCodeIssueCount(), styles.number());
        write(excelRow, col++, row.rate(row.returnCodeIssueCount()), styles.percent());
        write(excelRow, col++, row.tranIssueCount(), styles.number());
        write(excelRow, col++, row.rate(row.tranIssueCount()), styles.percent());
        write(excelRow, col++, row.fieldDiffTranCount(), styles.number());
        write(excelRow, col++, row.rate(row.fieldDiffTranCount()), styles.percent());
        write(excelRow, col++, row.fullyMatchedCount(), styles.number());
        write(excelRow, col, row.issueFieldCount(), styles.number());
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

    private record Styles(CellStyle title, CellStyle header, CellStyle body, CellStyle number, CellStyle percent) {}

    @FunctionalInterface
    private interface SheetWriter {
        void write(org.apache.poi.ss.usermodel.Sheet sheet, Styles styles);
    }

    private record SheetState(org.apache.poi.ss.usermodel.Sheet sheet, int[] rowIndex) {
        SheetState(org.apache.poi.ss.usermodel.Sheet sheet) {
            this(sheet, new int[]{2});
        }

        int nextRowIndex() {
            return rowIndex[0]++;
        }
    }

    private enum TransactionDiffCategory {
        ORIG_SUCCESS_DEST_FAIL("528\u6210\u529fCCBS\u5931\u8d25"),
        ORIG_FAIL_DEST_SUCCESS("528\u5931\u8d25CCBS\u6210\u529f"),
        BOTH_SUCCESS("\u4e8c\u8005\u90fd\u6210\u529f"),
        BOTH_FAIL("\u4e8c\u8005\u90fd\u5931\u8d25");

        private static final String SUCCESS_CODE_ZERO = "000000000000";
        private static final String SUCCESS_CODE_A = "AAAAAAA";

        private final String sheetName;

        TransactionDiffCategory(String sheetName) {
            this.sheetName = sheetName;
        }

        String sheetName() {
            return sheetName;
        }

        boolean exported() {
            return this != BOTH_SUCCESS;
        }

        static TransactionDiffCategory of(SampleDetailRow row) {
            boolean origSuccess = isSuccess(row.origErrorCode());
            boolean destSuccess = isSuccess(row.destErrorCode());
            if (origSuccess && !destSuccess) {
                return ORIG_SUCCESS_DEST_FAIL;
            }
            if (!origSuccess && destSuccess) {
                return ORIG_FAIL_DEST_SUCCESS;
            }
            if (origSuccess) {
                return BOTH_SUCCESS;
            }
            return BOTH_FAIL;
        }

        private static boolean isSuccess(String code) {
            String normalized = code == null ? "" : code.trim().toUpperCase();
            return SUCCESS_CODE_ZERO.equals(normalized) || SUCCESS_CODE_A.equals(normalized);
        }
    }
}
