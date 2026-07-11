package com.spdb.sample;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class SampleExcelExportService {
    private final Clock clock;

    private static final String TXT_DELIMITER = "|^";
    private static final DateTimeFormatter EXPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private static final String[] TRANSACTION_DIFF_TEXT_HEADERS = {
            "业务日期", "批次", "交易码", "服务码", "报文类型", "流水号", "交易结果",
            "528响应码", "528响应描述", "CCBS响应码", "CCBS响应描述", "责任人", "数量"
    };
    private static final String[] TRANSACTION_SUCCESS_STAT_HEADERS = {
            "业务日期", "批次", "交易码", "服务码", "报文类型", "成功数量", "接口字段总数",
            "比对字段总数", "差异字段数", "比对字段差异总数", "单字段差异>=1%", "单字段差异<1%",
            "领域", "责任人"
    };

    private static final String[] FIELD_DIFF_EXPORT_HEADERS = {
            "业务日期", "批次号", "交易码", "服务码", "报文类型",
            "SOP字段名", "SOAP字段名", "BizJSON字段名", "字段中文名", "映射状态",
            "样例流水号", "528值", "CCBS值", "责任人", "影响交易笔数",
            "审核人", "是否修复", "差异分类", "差异说明"
    };
    private static final int[] FIELD_DIFF_EXPORT_WIDTHS = {
            10, 20, 10, 30, 10, 18, 22, 22, 14, 10, 20, 22, 22, 10, 12, 10, 10, 12, 34
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

    public void streamTransactionDiffExport(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
        String timestamp = LocalDateTime.now(clock).format(EXPORT_TIMESTAMP);
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("transdiff-export-");
            Path ccbsFile = tempDir.resolve("transdiff_ccbs_" + timestamp + ".txt");
            Path cbspFile = tempDir.resolve("transdiff_cbsp_" + timestamp + ".txt");
            Path bothFile = tempDir.resolve("transdiff_both_" + timestamp + ".txt");
            Path successFile = tempDir.resolve("transdiff_success_" + timestamp + ".txt");
            Path leadershipFile = tempDir.resolve("leadership_summary_" + timestamp + ".xlsx");
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
            writeLeadershipWorkbook(queryService, criteria, counts, leadershipFile);
            try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
                writeZipFile(zip, ccbsFile);
                writeZipFile(zip, cbspFile);
                writeZipFile(zip, bothFile);
                writeZipFile(zip, successFile);
                writeZipFile(zip, leadershipFile);
                zip.finish();
            }
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException("生成交易级差异文本文件失败", e);
        } finally {
            deleteDirectoryQuietly(tempDir);
        }
    }

    public void streamFieldDiffExport(SampleQueryService queryService, SampleSearchCriteria criteria, OutputStream outputStream) {
        workbookToStream("字段级差异明细", "字段级差异合并导出", FIELD_DIFF_EXPORT_HEADERS, FIELD_DIFF_EXPORT_WIDTHS, ExportStyle.FIELD_REVIEW, outputStream, (sheet, styles) -> {
            int[] rowIndex = {2};
            queryService.streamFieldDiffExport(criteria, row -> writeFieldDiffExportRow(sheet, styles, rowIndex[0]++, row));
        });
    }

    public void streamServiceReport(SampleQueryService queryService, SamplingSummarySearchCriteria criteria, OutputStream outputStream) {
        workbookToStream("服务码汇报", "采样服务码维度汇报", SERVICE_REPORT_HEADERS, SERVICE_REPORT_WIDTHS, ExportStyle.DEFAULT, outputStream, (sheet, styles) -> {
            int[] rowIndex = {2};
            queryService.streamServiceReport(criteria, row -> writeServiceReportRow(sheet, styles, rowIndex[0]++, row));
        });
    }

    private void writeLeadershipWorkbook(SampleQueryService queryService, SampleSearchCriteria criteria,
                                         long[] categoryCounts, Path output) throws IOException {
        List<LeadershipServiceReportRow> serviceRows = new ArrayList<>();
        List<TransactionSuccessStatRow> successRows = new ArrayList<>();
        List<ModuleOwnerConfigRow> configRows = new ArrayList<>();
        SamplingSummarySearchCriteria summaryCriteria = new SamplingSummarySearchCriteria(
                criteria == null ? null : criteria.batchId(),
                criteria == null ? null : criteria.origCdate()
        );
        queryService.streamLeadershipServiceReport(summaryCriteria, serviceRows::add);
        queryService.streamTransactionSuccessStats(criteria, successRows::add);
        queryService.streamModuleOwnerConfigs(configRows::add);

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
            workbook.setCompressTempFiles(true);
            LeadershipStyles styles = createLeadershipStyles(workbook);
            writeLeadershipSummarySheet(workbook, styles, serviceRows, categoryCounts);
            writeOwnerDashboardSheet(workbook, styles, serviceRows);
            writeModuleDashboardSheet(workbook, styles, serviceRows, configRows);
            writeModuleOwnerConfigSheet(workbook, styles, configRows);
            writeLeadershipServiceDetailSheet(workbook, styles, serviceRows);
            writeFieldSummarySheet(workbook, styles, successRows);
            try (OutputStream out = Files.newOutputStream(output)) {
                workbook.write(out);
            }
            workbook.dispose();
        }
    }

    private void writeLeadershipSummarySheet(SXSSFWorkbook workbook, LeadershipStyles styles,
                                             List<LeadershipServiceReportRow> rows, long[] categoryCounts) {
        Sheet sheet = workbook.createSheet("领导总览");
        int[] widths = {16, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14};
        setWidths(sheet, widths);
        mergeWrite(sheet, 0, 0, 0, 13, "回放差异领导汇总报表", styles.title());
        mergeWrite(sheet, 1, 0, 1, 13, leadershipSubtitle(rows), styles.subtitle());

        long total = rows.stream().mapToLong(LeadershipServiceReportRow::totalTranCount).sum();
        long pass = rows.stream().mapToLong(LeadershipServiceReportRow::passTranCount).sum();
        long tranIssue = rows.stream().mapToLong(LeadershipServiceReportRow::tranIssueCount).sum();
        long retIssue = rows.stream().mapToLong(LeadershipServiceReportRow::returnCodeIssueCount).sum();
        long fieldDiff = rows.stream().mapToLong(LeadershipServiceReportRow::fieldDiffTranCount).sum();
        long issueFields = rows.stream().mapToLong(LeadershipServiceReportRow::issueFieldCount).sum();
        writeKpi(sheet, styles, 3, 0, "发起交易数", total, "全量回放交易", styles.kpiBlue());
        writeKpi(sheet, styles, 3, 2, "通过交易数", pass, "二者均成功", styles.kpiGreen());
        writeKpi(sheet, styles, 3, 4, "通过率", rate(pass, total), "通过 / 发起", styles.kpiGreen());
        writeKpi(sheet, styles, 3, 6, "问题交易数", tranIssue, "原失败/新失败类", styles.kpiOrange());
        writeKpi(sheet, styles, 3, 8, "响应码问题", retIssue, "响应码不一致", styles.kpiOrange());
        writeKpi(sheet, styles, 3, 10, "字段差异流水", fieldDiff, "成功交易内字段差异", styles.kpiRed());
        writeKpi(sheet, styles, 3, 12, "问题字段数", issueFields, "去重字段数", styles.kpiRed());

        mergeWrite(sheet, 6, 0, 6, 13, "本批结论", styles.section());
        mergeWrite(sheet, 7, 0, 8, 13,
                "本批次整体通过率 " + percentText(rate(pass, total)) + "，问题交易占比 " + percentText(rate(tranIssue, total))
                        + "。建议优先关注问题占比较高的领域、服务码，以及字段差异流水较高的服务。",
                styles.note());

        mergeWrite(sheet, 10, 0, 10, 6, "问题分类摘要", styles.section());
        writeTable(sheet, styles, 11, 0,
                new String[]{"问题类型", "数量", "占比", "归类", "建议动作"},
                problemCategoryRows(total, categoryCounts, retIssue, fieldDiff),
                new int[]{0, 1, 2, 0, 0});

        mergeWrite(sheet, 10, 8, 10, 13, "Top 风险服务码", styles.section());
        List<LeadershipServiceReportRow> top = rows.stream()
                .sorted(Comparator.comparingDouble((LeadershipServiceReportRow row) -> row.rate(row.tranIssueCount())).reversed())
                .limit(5)
                .toList();
        List<Object[]> topRows = top.stream()
                .map(row -> new Object[]{row.serviceCode(), row.tranName(), row.moduleName(), row.owner(), row.tranIssueCount(), row.rate(row.tranIssueCount())})
                .toList();
        writeTable(sheet, styles, 11, 8,
                new String[]{"服务码", "交易名称", "领域", "责任人", "问题交易", "问题占比"},
                topRows, new int[]{0, 0, 0, 0, 1, 2});
        sheet.createFreezePane(0, 2);
    }

    private void writeOwnerDashboardSheet(SXSSFWorkbook workbook, LeadershipStyles styles, List<LeadershipServiceReportRow> rows) {
        Sheet sheet = workbook.createSheet("责任人看板");
        setWidths(sheet, new int[]{16, 22, 14, 14, 12, 14, 12, 14, 12, 14, 12, 14, 12, 22});
        mergeWrite(sheet, 0, 0, 0, 13, "责任人维度看板", styles.title());
        mergeWrite(sheet, 1, 0, 1, 13, "用于按责任人识别风险分布、整改压力和重点服务码", styles.subtitle());
        writeTable(sheet, styles, 3, 0,
                new String[]{"责任人", "涉及领域", "发起交易数", "通过交易数", "通过率", "问题交易数", "问题占比", "响应码问题", "响应码占比", "字段差异流水", "字段差异占比", "问题字段数", "字段问题占比", "Top 服务码"},
                aggregateByOwner(rows), new int[]{0, 0, 1, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 0});
        sheet.createFreezePane(0, 4);
    }

    private void writeModuleDashboardSheet(SXSSFWorkbook workbook, LeadershipStyles styles,
                                           List<LeadershipServiceReportRow> rows, List<ModuleOwnerConfigRow> configs) {
        Sheet sheet = workbook.createSheet("领域看板");
        setWidths(sheet, new int[]{16, 20, 14, 14, 12, 14, 12, 14, 12, 14, 12, 14, 12, 22});
        mergeWrite(sheet, 0, 0, 0, 13, "领域分组维度看板", styles.title());
        mergeWrite(sheet, 1, 0, 1, 13, "用于按业务领域识别质量风险和资源投入重点", styles.subtitle());
        writeTable(sheet, styles, 3, 0,
                new String[]{"领域", "责任人", "发起交易数", "通过交易数", "通过率", "问题交易数", "问题占比", "响应码问题", "响应码占比", "字段差异流水", "字段差异占比", "问题字段数", "字段问题占比", "Top 服务码"},
                aggregateByModule(rows, configs), new int[]{0, 0, 1, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 0});
        sheet.createFreezePane(0, 4);
    }

    private void writeModuleOwnerConfigSheet(SXSSFWorkbook workbook, LeadershipStyles styles, List<ModuleOwnerConfigRow> rows) {
        Sheet sheet = workbook.createSheet("领域负责人配置");
        setWidths(sheet, new int[]{18, 18, 18, 34, 14});
        mergeWrite(sheet, 0, 0, 0, 4, "领域与负责人配置", styles.title());
        mergeWrite(sheet, 1, 0, 1, 4, "领域级看板的归口负责人来源；正式功能中建议作为可维护配置", styles.subtitle());
        List<Object[]> tableRows = rows.stream()
                .map(row -> new Object[]{row.moduleName(), row.primaryOwner(), row.backupOwner(), row.remark(), row.status()})
                .toList();
        writeTable(sheet, styles, 3, 0, new String[]{"领域", "主负责人", "备份负责人", "说明", "状态"}, tableRows, new int[]{0, 0, 0, 0, 0});
        sheet.createFreezePane(0, 4);
    }

    private void writeLeadershipServiceDetailSheet(SXSSFWorkbook workbook, LeadershipStyles styles, List<LeadershipServiceReportRow> rows) {
        Sheet sheet = workbook.createSheet("服务码明细");
        setWidths(sheet, new int[]{12, 20, 10, 14, 18, 12, 14, 12, 12, 12, 14, 14, 14, 14, 12, 12, 12, 12, 16, 16, 14, 14, 18, 18, 14, 12});
        mergeWrite(sheet, 0, 0, 0, 25, "服务码明细汇总", styles.title());
        mergeWrite(sheet, 1, 0, 1, 25, "沿用现有服务码汇报口径，增加领域列并做领导汇报版式", styles.subtitle());
        List<Object[]> tableRows = rows.stream()
                .map(row -> new Object[]{row.origCdate(), row.batchId(), row.tranCode(), row.serviceCode(), row.tranName(), row.moduleName(), row.owner(),
                        row.totalTranCount(), row.passTranCount(), row.rate(row.passTranCount()),
                        row.compResult1Count(), row.rate(row.compResult1Count()), row.compResult2Count(), row.rate(row.compResult2Count()),
                        row.compResult3Count(), row.rate(row.compResult3Count()), row.compResult4Count(), row.rate(row.compResult4Count()),
                        row.compResult8Count(), row.rate(row.compResult8Count()), row.tranIssueCount(), row.rate(row.tranIssueCount()),
                        row.fieldDiffTranCount(), row.rate(row.fieldDiffTranCount()), row.fullyMatchedCount(), row.issueFieldCount()})
                .toList();
        writeTable(sheet, styles, 3, 0,
                new String[]{"业务日期", "批次", "交易码", "服务码", "交易名称", "领域", "责任人", "发起交易数", "通过交易数", "通过率", "原失败新成功数", "原失败新成功占比", "原成功新失败数", "原成功新失败占比", "都失败数", "都失败占比", "都成功数", "都成功占比", "响应码不一致数", "响应码不一致占比", "交易问题数", "交易问题占比", "字段差异流水数", "字段差异流水占比", "完全匹配数", "问题字段数"},
                tableRows, new int[]{0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 1});
        sheet.createFreezePane(4, 4);
    }

    private void writeFieldSummarySheet(SXSSFWorkbook workbook, LeadershipStyles styles, List<TransactionSuccessStatRow> rows) {
        Sheet sheet = workbook.createSheet("字段差异摘要");
        setWidths(sheet, new int[]{12, 20, 10, 14, 12, 12, 14, 14, 14, 16, 14, 18, 16, 16});
        mergeWrite(sheet, 0, 0, 0, 13, "二者都成功字段差异摘要", styles.title());
        mergeWrite(sheet, 1, 0, 1, 13, "对应 transdiff_success 口径，帮助识别成功交易内部字段质量问题", styles.subtitle());
        List<Object[]> tableRows = rows.stream()
                .map(row -> new Object[]{row.origCdate(), row.batchId(), row.tranCode(), row.serviceCode(), row.messageType(), row.moduleName(), row.owner(),
                        row.successCount(), row.interfaceFieldCount(), row.comparedFieldCount(), row.diffFieldCount(), row.comparedFieldDiffCount(), row.highRatioFieldCount(), row.lowRatioFieldCount()})
                .toList();
        writeTable(sheet, styles, 3, 0,
                new String[]{"业务日期", "批次", "交易码", "服务码", "报文类型", "领域", "责任人", "成功数", "接口字段数", "比对字段总数", "差异字段数", "比对字段差异总数", "单字段差异>=1%", "单字段差异<1%"},
                tableRows, new int[]{0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1});
        sheet.createFreezePane(0, 4);
    }

    private void workbookToStream(String sheetName, String title, String[] headers, int[] widths,
                                  ExportStyle exportStyle, OutputStream outputStream, SheetWriter writer) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(500)) {
            workbook.setCompressTempFiles(true);
            Styles styles = createStyles(workbook, exportStyle);
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet(sheetName);
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
        titleRow.setHeightInPoints(styles.titleHeightInPoints());
        for (int i = 0; i < headers.length; i++) {
            write(titleRow, i, i == 0 ? title : "", styles.title());
        }

        Row headerRow = sheet.createRow(1);
        headerRow.setHeightInPoints(styles.headerHeightInPoints());
        for (int i = 0; i < headers.length; i++) {
            write(headerRow, i, headers[i], styles.header());
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(0, 2);
        sheet.setAutoFilter(new CellRangeAddress(1, 1, 0, headers.length - 1));
    }

    private LeadershipStyles createLeadershipStyles(org.apache.poi.ss.usermodel.Workbook workbook) {
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 16);
        titleFont.setColor(IndexedColors.WHITE.getIndex());
        titleFont.setFontName("Microsoft YaHei");

        CellStyle title = workbook.createCellStyle();
        title.setFont(titleFont);
        title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        title.setAlignment(HorizontalAlignment.CENTER);
        title.setVerticalAlignment(VerticalAlignment.CENTER);

        Font subtitleFont = workbook.createFont();
        subtitleFont.setFontHeightInPoints((short) 10);
        subtitleFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        subtitleFont.setFontName("Microsoft YaHei");

        CellStyle subtitle = workbook.createCellStyle();
        subtitle.setFont(subtitleFont);
        subtitle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        subtitle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        subtitle.setAlignment(HorizontalAlignment.CENTER);
        subtitle.setVerticalAlignment(VerticalAlignment.CENTER);

        Font whiteBold = workbook.createFont();
        whiteBold.setBold(true);
        whiteBold.setColor(IndexedColors.WHITE.getIndex());
        whiteBold.setFontName("Microsoft YaHei");

        CellStyle section = workbook.createCellStyle();
        section.setFont(whiteBold);
        section.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        section.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        section.setAlignment(HorizontalAlignment.LEFT);
        section.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle header = workbook.createCellStyle();
        header.setFont(whiteBold);
        header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setWrapText(true);
        border(header);

        Font bodyFont = workbook.createFont();
        bodyFont.setFontName("Microsoft YaHei");
        bodyFont.setFontHeightInPoints((short) 10);

        CellStyle body = workbook.createCellStyle();
        body.setFont(bodyFont);
        body.setVerticalAlignment(VerticalAlignment.CENTER);
        border(body);

        CellStyle alternate = workbook.createCellStyle();
        alternate.cloneStyleFrom(body);
        alternate.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        alternate.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle number = workbook.createCellStyle();
        number.cloneStyleFrom(body);
        number.setAlignment(HorizontalAlignment.RIGHT);
        number.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));

        CellStyle percent = workbook.createCellStyle();
        percent.cloneStyleFrom(number);
        percent.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));

        CellStyle note = workbook.createCellStyle();
        note.setFont(bodyFont);
        note.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        note.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        note.setWrapText(true);
        note.setVerticalAlignment(VerticalAlignment.CENTER);
        border(note);

        return new LeadershipStyles(title, subtitle, section, header, body, alternate, number, percent, note,
                kpiStyle(workbook, IndexedColors.DARK_BLUE),
                kpiStyle(workbook, IndexedColors.GREEN),
                kpiStyle(workbook, IndexedColors.TAN),
                kpiStyle(workbook, IndexedColors.RED));
    }

    private CellStyle kpiStyle(org.apache.poi.ss.usermodel.Workbook workbook, IndexedColors color) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        font.setFontName("Microsoft YaHei");
        font.setFontHeightInPoints((short) 10);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(color.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        return style;
    }

    private void setWidths(Sheet sheet, int[] widths) {
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
    }

    private void mergeWrite(Sheet sheet, int firstRow, int firstCol, int lastRow, int lastCol, Object value, CellStyle style) {
        sheet.addMergedRegion(new CellRangeAddress(firstRow, lastRow, firstCol, lastCol));
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = getOrCreateRow(sheet, r);
            for (int c = firstCol; c <= lastCol; c++) {
                write(row, c, r == firstRow && c == firstCol ? value : "", style);
            }
        }
    }

    private void writeKpi(Sheet sheet, LeadershipStyles styles, int rowIndex, int colIndex,
                          String label, Object value, String note, CellStyle style) {
        String valueText = value instanceof Double d ? percentText(d) : formatLong(((Number) value).longValue());
        mergeWrite(sheet, rowIndex, colIndex, rowIndex + 1, colIndex + 1,
                label + "\n" + valueText + "\n" + note, style);
        sheet.getRow(rowIndex).setHeightInPoints(34);
        sheet.getRow(rowIndex + 1).setHeightInPoints(34);
    }

    private void writeTable(Sheet sheet, LeadershipStyles styles, int startRow, int startCol,
                            String[] headers, List<Object[]> rows, int[] formats) {
        Row header = getOrCreateRow(sheet, startRow);
        for (int i = 0; i < headers.length; i++) {
            write(header, startCol + i, headers[i], styles.header());
        }
        for (int r = 0; r < rows.size(); r++) {
            Row row = getOrCreateRow(sheet, startRow + 1 + r);
            Object[] values = rows.get(r);
            for (int c = 0; c < headers.length; c++) {
                CellStyle style = switch (formats[c]) {
                    case 1 -> styles.number();
                    case 2 -> styles.percent();
                    default -> (r % 2 == 0 ? styles.alternate() : styles.body());
                };
                write(row, startCol + c, values[c], style);
            }
        }
        if (!rows.isEmpty()) {
            sheet.setAutoFilter(new CellRangeAddress(startRow, startRow, startCol, startCol + headers.length - 1));
        }
    }

    private Row getOrCreateRow(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        return row == null ? sheet.createRow(rowIndex) : row;
    }

    private String leadershipSubtitle(List<LeadershipServiceReportRow> rows) {
        String businessDate = rows.isEmpty() ? "" : rows.get(0).origCdate();
        String batchId = rows.stream().map(LeadershipServiceReportRow::batchId).filter(s -> s != null && !s.isBlank()).findFirst().orElse("");
        return "业务日期：" + businessDate + "    批次：" + batchId + "    数据范围：交易级差异 + 字段级差异";
    }

    private List<Object[]> problemCategoryRows(long total, long[] categoryCounts, long retIssue, long fieldDiff) {
        return List.of(
                new Object[]{"528成功CCBS失败", categoryCounts[0], rate(categoryCounts[0], total), "交易结果类", "优先排查回归风险"},
                new Object[]{"CCBS成功528失败", categoryCounts[1], rate(categoryCounts[1], total), "交易结果类", "关注新系统修复收益"},
                new Object[]{"均失败错误码不一致", categoryCounts[2], rate(categoryCounts[2], total), "交易结果类", "确认错误码一致性"},
                new Object[]{"响应码不一致", retIssue, rate(retIssue, total), "响应码类", "按服务码定位错误码映射"},
                new Object[]{"字段差异流水", fieldDiff, rate(fieldDiff, total), "字段类", "进入字段差异摘要"}
        );
    }

    private List<Object[]> aggregateByOwner(List<LeadershipServiceReportRow> rows) {
        return aggregate(rows, LeadershipServiceReportRow::owner, true);
    }

    private List<Object[]> aggregateByModule(List<LeadershipServiceReportRow> rows, List<ModuleOwnerConfigRow> configs) {
        Map<String, String> moduleOwners = new LinkedHashMap<>();
        for (ModuleOwnerConfigRow config : configs) {
            moduleOwners.put(config.moduleName(), config.primaryOwner());
        }
        return aggregate(rows, LeadershipServiceReportRow::moduleName, false).stream()
                .map(row -> {
                    row[1] = moduleOwners.getOrDefault((String) row[0], (String) row[1]);
                    return row;
                })
                .toList();
    }

    private List<Object[]> aggregate(List<LeadershipServiceReportRow> rows,
                                     java.util.function.Function<LeadershipServiceReportRow, String> classifier,
                                     boolean ownerMode) {
        Map<String, List<LeadershipServiceReportRow>> grouped = new LinkedHashMap<>();
        for (LeadershipServiceReportRow row : rows) {
            grouped.computeIfAbsent(blankToUnknown(classifier.apply(row)), key -> new ArrayList<>()).add(row);
        }
        List<Object[]> result = new ArrayList<>();
        for (Map.Entry<String, List<LeadershipServiceReportRow>> entry : grouped.entrySet()) {
            List<LeadershipServiceReportRow> groupRows = entry.getValue();
            long total = groupRows.stream().mapToLong(LeadershipServiceReportRow::totalTranCount).sum();
            long pass = groupRows.stream().mapToLong(LeadershipServiceReportRow::passTranCount).sum();
            long tranIssue = groupRows.stream().mapToLong(LeadershipServiceReportRow::tranIssueCount).sum();
            long retIssue = groupRows.stream().mapToLong(LeadershipServiceReportRow::returnCodeIssueCount).sum();
            long fieldDiff = groupRows.stream().mapToLong(LeadershipServiceReportRow::fieldDiffTranCount).sum();
            long fields = groupRows.stream().mapToLong(LeadershipServiceReportRow::issueFieldCount).sum();
            String second = ownerMode
                    ? distinctJoin(groupRows.stream().map(LeadershipServiceReportRow::moduleName).toList())
                    : distinctJoin(groupRows.stream().map(LeadershipServiceReportRow::owner).toList());
            String topServices = distinctJoin(groupRows.stream()
                    .sorted(Comparator.comparingLong(LeadershipServiceReportRow::tranIssueCount).reversed())
                    .limit(3)
                    .map(LeadershipServiceReportRow::serviceCode)
                    .toList());
            result.add(new Object[]{entry.getKey(), second, total, pass, rate(pass, total), tranIssue, rate(tranIssue, total),
                    retIssue, rate(retIssue, total), fieldDiff, rate(fieldDiff, total), fields, rate(fields, total), topServices});
        }
        result.sort(Comparator.comparingDouble((Object[] row) -> (Double) row[6]).reversed());
        return result;
    }

    private String distinctJoin(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).distinct().reduce((a, b) -> a + "," + b).orElse("");
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "未配置" : value;
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0d : numerator / (double) denominator;
    }

    private String percentText(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100);
    }

    private String formatLong(long value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }

    private Styles createStyles(org.apache.poi.ss.usermodel.Workbook workbook, ExportStyle exportStyle) {
        if (exportStyle == ExportStyle.FIELD_REVIEW) {
            return createFieldReviewStyles(workbook);
        }
        return createDefaultStyles(workbook);
    }

    private Styles createDefaultStyles(org.apache.poi.ss.usermodel.Workbook workbook) {
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
        return new Styles(title, header, body, body, number, number, percent, 28, 24, 0);
    }

    private Styles createFieldReviewStyles(org.apache.poi.ss.usermodel.Workbook workbook) {
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 11);
        titleFont.setColor(IndexedColors.WHITE.getIndex());
        titleFont.setFontName("Microsoft YaHei");

        CellStyle title = workbook.createCellStyle();
        title.setFont(titleFont);
        title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        title.setAlignment(HorizontalAlignment.CENTER);
        title.setVerticalAlignment(VerticalAlignment.CENTER);
        border(title);

        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 9);
        headerFont.setColor(IndexedColors.DARK_BLUE.getIndex());
        headerFont.setFontName("Microsoft YaHei");

        CellStyle header = workbook.createCellStyle();
        header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setWrapText(true);
        border(header);

        Font bodyFont = workbook.createFont();
        bodyFont.setFontHeightInPoints((short) 8);
        bodyFont.setColor(IndexedColors.BLACK.getIndex());
        bodyFont.setFontName("Microsoft YaHei");

        CellStyle body = workbook.createCellStyle();
        body.setFont(bodyFont);
        body.setVerticalAlignment(VerticalAlignment.CENTER);
        body.setWrapText(true);
        body.setFillForegroundColor(IndexedColors.WHITE.getIndex());
        body.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        border(body);

        CellStyle bodyAlternate = workbook.createCellStyle();
        bodyAlternate.cloneStyleFrom(body);
        bodyAlternate.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        bodyAlternate.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle number = workbook.createCellStyle();
        number.cloneStyleFrom(body);
        number.setAlignment(HorizontalAlignment.RIGHT);

        CellStyle numberAlternate = workbook.createCellStyle();
        numberAlternate.cloneStyleFrom(bodyAlternate);
        numberAlternate.setAlignment(HorizontalAlignment.RIGHT);

        CellStyle percent = workbook.createCellStyle();
        percent.cloneStyleFrom(number);
        percent.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
        return new Styles(title, header, body, bodyAlternate, number, numberAlternate, percent, 21, 22, 26);
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

    private void writeZipFile(ZipOutputStream zip, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
        Files.copy(file, zip);
        zip.closeEntry();
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

    private void writeFieldDiffExportRow(org.apache.poi.ss.usermodel.Sheet sheet, Styles styles, int rowIndex, SampleFieldDiffRow row) {
        Row excelRow = sheet.createRow(rowIndex);
        if (styles.bodyHeightInPoints() > 0) {
            excelRow.setHeightInPoints(styles.bodyHeightInPoints());
        }
        CellStyle body = styles.body(rowIndex);
        CellStyle number = styles.number(rowIndex);
        int col = 0;
        write(excelRow, col++, row.origCdate(), body);
        write(excelRow, col++, row.batchId(), body);
        write(excelRow, col++, row.tranCode(), body);
        write(excelRow, col++, row.serviceCode(), body);
        write(excelRow, col++, row.messageType(), body);
        write(excelRow, col++, row.sopFieldName(), body);
        write(excelRow, col++, row.soapFieldName(), body);
        write(excelRow, col++, row.bizjsonFieldName(), body);
        write(excelRow, col++, row.fieldCnName(), body);
        write(excelRow, col++, mappingStatusText(row.mappingStatus()), body);
        write(excelRow, col++, row.sampleTranSeqNo(), body);
        write(excelRow, col++, row.origFieldValue(), body);
        write(excelRow, col++, row.destFieldValue(), body);
        write(excelRow, col++, row.owner(), body);
        write(excelRow, col++, row.affectedTranCount(), number);
        write(excelRow, col++, "", body);
        write(excelRow, col++, "否", body);
        write(excelRow, col++, "", body);
        write(excelRow, col, "", body);
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
        style.setTopBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setRightBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setBottomBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setLeftBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
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

    private record Styles(CellStyle title,
                          CellStyle header,
                          CellStyle body,
                          CellStyle bodyAlternate,
                          CellStyle number,
                          CellStyle numberAlternate,
                          CellStyle percent,
                          int titleHeightInPoints,
                          int headerHeightInPoints,
                          int bodyHeightInPoints) {
        private CellStyle body(int rowIndex) {
            return useAlternate(rowIndex) ? bodyAlternate : body;
        }

        private CellStyle number(int rowIndex) {
            return useAlternate(rowIndex) ? numberAlternate : number;
        }

        private boolean useAlternate(int rowIndex) {
            return (rowIndex - 2) % 2 == 1;
        }
    }

    private record LeadershipStyles(CellStyle title,
                                    CellStyle subtitle,
                                    CellStyle section,
                                    CellStyle header,
                                    CellStyle body,
                                    CellStyle alternate,
                                    CellStyle number,
                                    CellStyle percent,
                                    CellStyle note,
                                    CellStyle kpiBlue,
                                    CellStyle kpiGreen,
                                    CellStyle kpiOrange,
                                    CellStyle kpiRed) {
    }

    private enum ExportStyle {
        DEFAULT,
        FIELD_REVIEW
    }

    @FunctionalInterface
    private interface SheetWriter {
        void write(org.apache.poi.ss.usermodel.Sheet sheet, Styles styles);
    }

    private enum TransactionDiffCategory {
        ORIG_SUCCESS_DEST_FAIL,
        ORIG_FAIL_DEST_SUCCESS,
        BOTH_SUCCESS,
        BOTH_FAIL;

        private static final String SUCCESS_CODE_ZERO = "000000000000";
        private static final String SUCCESS_CODE_A = "AAAAAAA";

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
