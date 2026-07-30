package com.spdb.report;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReportExportExcelService {
    private static final Logger log = LoggerFactory.getLogger(ReportExportExcelService.class);
    private static final int DEFAULT_DELAY_GRACE_DAYS = 4;
    private static final String[] DETAIL_HEADERS = {"领域", "序号", "批次", "交易码", "交易名称", "问题级别", "登记日期",
            "字段名", "问题描述", "交易负责人", "问题类型", "初步问题分析", "最终处理方案", "解决日期", "需协同组",
            "解决人员", "流水号", "缺陷修复日期", "备注", "历史出现次数", "首次出现日期", "上次出现日期"};
    private final NamedParameterJdbcTemplate jdbc;
    private final int delayGraceDays;

    @Autowired
    public ReportExportExcelService(NamedParameterJdbcTemplate jdbc,
                                    @Value("${rose.report-export.delay-grace-days:4}") int delayGraceDays) {
        this.jdbc = jdbc;
        this.delayGraceDays = Math.max(0, delayGraceDays);
    }

    ReportExportExcelService(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, DEFAULT_DELAY_GRACE_DAYS);
    }

    public void stream(String batchId, OutputStream output) throws IOException {
        long started = System.nanoTime();
        log.info("日报导出开始，batchId={}, delayGraceDays={}", batchId, delayGraceDays);
        SXSSFWorkbook workbook = new SXSSFWorkbook(200);
        workbook.setCompressTempFiles(true);
        try {
            Styles styles = new Styles(workbook);
            String previousBatchId = previousSucceededBatchId(batchId);
            List<String> modules = modules(batchId);
            log.info("日报导出批次信息已加载，batchId={}, previousBatchId={}, moduleCount={}",
                    batchId, previousBatchId, modules.size());
            writeSummary(workbook, batchId, previousBatchId, false, styles);
            for (String module : modules) {
                writeModule(workbook, batchId, module, styles);
            }
            writeDelayDistribution(workbook, batchId, previousSucceededBatchId(batchId), "解决人员", false, styles);
            workbook.write(output);
            log.info("日报导出完成，batchId={}, previousBatchId={}, sheetCount={}, elapsedMs={}",
                    batchId, previousBatchId, workbook.getNumberOfSheets(), elapsedMs(started));
        } catch (IOException | RuntimeException e) {
            log.error("日报导出失败，batchId={}, elapsedMs={}", batchId, elapsedMs(started), e);
            throw e;
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }

    public void streamWeekly(String batchId, OutputStream output) throws IOException {
        long started = System.nanoTime();
        log.info("周报导出开始，batchId={}, delayGraceDays={}", batchId, delayGraceDays);
        SXSSFWorkbook workbook = new SXSSFWorkbook(200);
        workbook.setCompressTempFiles(true);
        try {
            Styles styles = new Styles(workbook);
            String baselineBatchId = weeklyBaselineSucceededBatchId(batchId);
            log.info("周报导出基准批次已确定，batchId={}, baselineBatchId={}", batchId, baselineBatchId);
            writeSummary(workbook, batchId, baselineBatchId, true, styles);
            writeDelayDistribution(workbook, batchId, baselineBatchId, "领域", true, styles);
            workbook.write(output);
            log.info("周报导出完成，batchId={}, baselineBatchId={}, sheetCount={}, elapsedMs={}",
                    batchId, baselineBatchId, workbook.getNumberOfSheets(), elapsedMs(started));
        } catch (IOException | RuntimeException e) {
            log.error("周报导出失败，batchId={}, elapsedMs={}", batchId, elapsedMs(started), e);
            throw e;
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }

    private void writeSummary(SXSSFWorkbook book, String batchId, Styles styles) {
        writeSummary(book, batchId, previousSucceededBatchId(batchId), false, styles);
    }

    private void writeSummary(SXSSFWorkbook book, String batchId, String previousBatchId, boolean weekly, Styles styles) {
        SXSSFSheet sheet = book.createSheet("汇总信息");
        List<SummaryRow> currentRows = weekly ? summaryRowsWithWeeklyDuplicates(batchId, previousBatchId) : summaryRows(batchId);
        List<SummaryRow> previousRows = previousBatchId == null ? List.of() : summaryRows(previousBatchId);
        log.info("{}汇总Sheet数据已加载，batchId={}, previousBatchId={}, currentRowCount={}, previousRowCount={}",
                weekly ? "周报" : "日报", batchId, previousBatchId, currentRows.size(), previousRows.size());

        String previousTitle = weekly ? "周期周报 - 上周期" : "上一批次";
        int nextRow = writeSummarySection(sheet, 0, previousBatchId, previousTitle, previousRows, Map.of(), false, styles);
        writeSummarySection(sheet, nextRow + 2, batchId, "本批次", currentRows, issueTotalsByModule(previousRows), true, styles);

        for (int i = 0; i < 20; i++) {
            sheet.setColumnWidth(i, i == 1 ? 4600 : 3600);
        }
    }

    private int writeSummarySection(SXSSFSheet sheet, int startRow, String batchId, String title, List<SummaryRow> rows,
                                    Map<String, Long> previousIssueTotals, boolean current, Styles styles) {
        int lastColumn = current ? 19 : 17;
        Row titleRow = sheet.createRow(startRow);
        titleRow.setHeightInPoints(24f);
        mergedCell(sheet, startRow, startRow, 0, lastColumn,
                "批次号：" + (batchId == null || batchId.isBlank() ? "-" : batchId) + "（" + title + "）",
                styles.summaryTitleStyle(current));

        writeSummaryHeaders(sheet, startRow + 1, current, styles);

        int rowIndex = startRow + 3;
        int dataOrdinal = 1;
        for (SummaryRow row : rows) {
            writeSummaryDataRow(sheet.createRow(rowIndex++), row, previousIssueTotals.get(row.moduleName()),
                    current, dataOrdinal++, styles);
        }
        writeSummaryTotalRow(sheet.createRow(rowIndex), rows, previousIssueTotals, current, dataOrdinal, styles);
        return rowIndex;
    }

    private void writeSummaryHeaders(SXSSFSheet sheet, int mainHeaderRowIndex, boolean current, Styles styles) {
        Row mainHeader = sheet.createRow(mainHeaderRowIndex);
        Row subHeader = sheet.createRow(mainHeaderRowIndex + 1);
        mainHeader.setHeightInPoints(28f);
        subHeader.setHeightInPoints(42f);

        CellStyle mainStyle = styles.summaryMainHeaderStyle(current);
        CellStyle subStyle = styles.summarySubHeaderStyle(current);
        CellStyle issueStyle = styles.summaryIssueHeaderStyle();
        CellStyle manualFillStyle = styles.summaryManualFillHeaderStyle();

        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 0, 0, "批次", mainStyle);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 1, 1, "领域", mainStyle);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 2, 2, "覆盖528接口", mainStyle);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 3, 3, "发送交易量", mainStyle);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex, 4, 8, "交易状态分类统计", mainStyle);

        String[] statusHeaders = {"528成功/CCBS失败", "528失败/CCBS成功", "二者均失败响应码一致",
                "二者均失败响应码不一致", "二者均成功"};
        for (int i = 0; i < statusHeaders.length; i++) {
            cell(subHeader, 4 + i, statusHeaders[i], subStyle);
        }

        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 9, 9, "成功率", mainStyle);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 10, 10, "比对通过率", mainStyle);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 11, 11, "问题总数", issueStyle);

        if (current) {
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 12, 12, "重复问题", issueStyle);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 13, 13, "上轮问题解决率", issueStyle);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex, 14, 18,
                    "已解决问题分类统计（待验证）", manualFillStyle);
            writeSolvedIssueSubHeaders(subHeader, 14, manualFillStyle);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 19, 19, "问题解决进度", manualFillStyle);
        } else {
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex, 12, 16,
                    "已解决问题分类统计（待验证）", manualFillStyle);
            writeSolvedIssueSubHeaders(subHeader, 12, manualFillStyle);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 17, 17, "问题解决进度", manualFillStyle);
        }
    }

    private void writeSolvedIssueSubHeaders(Row subHeader, int firstColumn, CellStyle style) {
        String[] solvedHeaders = {"迁移问题", "防腐问题", "功能问题", "新核心下线", "其他问题"};
        for (int i = 0; i < solvedHeaders.length; i++) {
            cell(subHeader, firstColumn + i, solvedHeaders[i], style);
        }
    }

    private void writeSummaryDataRow(Row excelRow, SummaryRow row, Long previousIssueTotal,
                                     boolean current, int dataOrdinal, Styles styles) {
        CellStyle rowStyle = styles.summaryBodyStyle();
        cell(excelRow, 0, row.batchId(), rowStyle);
        cell(excelRow, 1, row.moduleName(), rowStyle);
        cell(excelRow, 2, text(row.covered528InterfaceCount()), rowStyle);
        cell(excelRow, 3, text(row.sentTransactionCount()), rowStyle);
        cell(excelRow, 4, text(row.compResult1Count()), rowStyle);
        cell(excelRow, 5, text(row.compResult2Count()), rowStyle);
        cell(excelRow, 6, text(row.compResult3Count()), rowStyle);
        cell(excelRow, 7, text(row.compResult8Count()), rowStyle);
        cell(excelRow, 8, text(row.compResult4Count()), rowStyle);
        percentCell(excelRow, 9, row.successRate(), styles.summaryPercentStyle());
        percentCell(excelRow, 10, row.comparisonPassRate(), styles.summaryPercentStyle());
        cell(excelRow, 11, text(row.issueTotalCount()), rowStyle);
        if (current) {
            cell(excelRow, 12, text(row.duplicateIssueCount()), rowStyle);
            percentCell(excelRow, 13, previousResolutionRate(previousIssueTotal, row.duplicateIssueCount()), styles.summaryPercentStyle());
            blankCells(excelRow, 14, 19, styles.summaryManualFillStyle());
        } else {
            blankCells(excelRow, 12, 17, styles.summaryManualFillStyle());
        }
    }

    private void writeSummaryTotalRow(Row excelRow, List<SummaryRow> rows, Map<String, Long> previousIssueTotals,
                                      boolean current, int dataOrdinal, Styles styles) {
        CellStyle rowStyle = styles.summaryTotalStyle(current);
        SummaryTotals totals = SummaryTotals.of(rows);
        cell(excelRow, 0, "", rowStyle);
        cell(excelRow, 1, "合计", rowStyle);
        cell(excelRow, 2, text(totals.covered528InterfaceCount()), rowStyle);
        cell(excelRow, 3, text(totals.sentTransactionCount()), rowStyle);
        cell(excelRow, 4, text(totals.compResult1Count()), rowStyle);
        cell(excelRow, 5, text(totals.compResult2Count()), rowStyle);
        cell(excelRow, 6, text(totals.compResult3Count()), rowStyle);
        cell(excelRow, 7, text(totals.compResult8Count()), rowStyle);
        cell(excelRow, 8, text(totals.compResult4Count()), rowStyle);
        percentCell(excelRow, 9, rate(totals.compResult3Count() + totals.compResult4Count(),
                totals.sentTransactionCount()), styles.summaryTotalPercentStyle(current));
        percentCell(excelRow, 10, rate(totals.fieldPassTransactionCount() + totals.compResult3Count(),
                totals.sentTransactionCount()), styles.summaryTotalPercentStyle(current));
        cell(excelRow, 11, text(totals.issueTotalCount()), rowStyle);
        if (current) {
            long previousIssueTotal = previousIssueTotals.values().stream().mapToLong(Long::longValue).sum();
            cell(excelRow, 12, text(totals.duplicateIssueCount()), rowStyle);
            percentCell(excelRow, 13, previousResolutionRate(previousIssueTotal, totals.duplicateIssueCount()),
                    styles.summaryTotalPercentStyle(current));
            blankCells(excelRow, 14, 19, rowStyle);
        } else {
            blankCells(excelRow, 12, 17, rowStyle);
        }
    }

    private String previousSucceededBatchId(String batchId) {
        List<CommandPosition> current = jdbc.query("""
                select command_id, created_time
                  from ana_report_export_command
                 where batch_id = :batchId
                """, params(batchId), (rs, rowNum) -> new CommandPosition(
                rs.getLong("command_id"), rs.getTimestamp("created_time").toInstant()));
        if (current.isEmpty()) {
            return null;
        }
        MapSqlParameterSource queryParams = params(batchId)
                .addValue("commandId", current.get(0).commandId())
                .addValue("createdTime", java.sql.Timestamp.from(current.get(0).createdTime()));
        List<String> previous = jdbc.query("""
                select batch_id
                  from ana_report_export_command
                 where status = 'SUCCEEDED'
                   and (created_time < :createdTime or (created_time = :createdTime and command_id < :commandId))
                 order by created_time desc, command_id desc
                 limit 1
                """, queryParams, (rs, rowNum) -> rs.getString("batch_id"));
        return previous.isEmpty() ? null : previous.get(0);
    }

    private String weeklyBaselineSucceededBatchId(String batchId) {
        LocalDate currentReportDate = reportDate(batchId);
        if (currentReportDate == null) {
            String fallback = previousSucceededBatchId(batchId);
            log.info("周报基准批次按上一成功批次回退，batchId={}, reason=reportDateMissing, baselineBatchId={}",
                    batchId, fallback);
            return fallback;
        }
        String targetDate = currentReportDate.minusDays(7).format(DateTimeFormatter.BASIC_ISO_DATE);
        log.info("周报基准批次开始查找，batchId={}, reportDate={}, targetDate={}",
                batchId, currentReportDate.format(DateTimeFormatter.BASIC_ISO_DATE), targetDate);
        List<String> baseline = jdbc.query("""
                select batch_id
                  from ana_report_export_command
                 where status = 'SUCCEEDED'
                   and batch_id <> :batchId
                   and report_date <= :targetDate
                 order by report_date desc, command_id desc
                 limit 1
                """, params(batchId).addValue("targetDate", targetDate), (rs, rowNum) -> rs.getString("batch_id"));
        if (!baseline.isEmpty()) {
            log.info("周报基准批次命中，batchId={}, targetDate={}, direction=before_or_equal, baselineBatchId={}",
                    batchId, targetDate, baseline.get(0));
            return baseline.get(0);
        }
        List<String> fallback = jdbc.query("""
                select batch_id
                  from ana_report_export_command
                 where status = 'SUCCEEDED'
                   and batch_id <> :batchId
                   and report_date > :targetDate
                 order by report_date asc, command_id desc
                 limit 1
                """, params(batchId).addValue("targetDate", targetDate), (rs, rowNum) -> rs.getString("batch_id"));
        String baselineBatchId = fallback.isEmpty() ? null : fallback.get(0);
        log.info("周报基准批次向后查找完成，batchId={}, targetDate={}, direction=after, baselineBatchId={}",
                batchId, targetDate, baselineBatchId);
        return baselineBatchId;
    }

    private LocalDate reportDate(String batchId) {
        List<String> commandDates = jdbc.query("""
                select report_date
                  from ana_report_export_command
                 where batch_id = :batchId
                 order by command_id desc
                 limit 1
                """, params(batchId), (rs, rowNum) -> rs.getString("report_date"));
        if (!commandDates.isEmpty() && commandDates.get(0) != null && !commandDates.get(0).isBlank()) {
            return LocalDate.parse(commandDates.get(0), DateTimeFormatter.BASIC_ISO_DATE);
        }
        List<String> summaryDates = jdbc.query("""
                select report_date
                  from ana_report_export_summary
                 where batch_id = :batchId
                   and report_date is not null
                 limit 1
                """, params(batchId), (rs, rowNum) -> rs.getString("report_date"));
        return summaryDates.isEmpty() || summaryDates.get(0) == null || summaryDates.get(0).isBlank()
                ? null
                : LocalDate.parse(summaryDates.get(0), DateTimeFormatter.BASIC_ISO_DATE);
    }

    private List<SummaryRow> summaryRows(String batchId) {
        return jdbc.query("""
                select batch_id, module_name, covered_528_interface_count, sent_transaction_count,
                       comp_result_1_count, comp_result_2_count, comp_result_3_count, comp_result_4_count,
                       comp_result_8_count, success_rate, field_pass_transaction_count, comparison_pass_rate,
                       issue_total_count, duplicate_issue_count
                  from ana_report_export_summary
                 where batch_id=:batchId
                 order by module_name
                """, params(batchId), (rs, rowNum) -> new SummaryRow(
                rs.getString("batch_id"), rs.getString("module_name"),
                rs.getLong("covered_528_interface_count"), rs.getLong("sent_transaction_count"),
                rs.getLong("comp_result_1_count"), rs.getLong("comp_result_2_count"),
                rs.getLong("comp_result_3_count"), rs.getLong("comp_result_4_count"),
                rs.getLong("comp_result_8_count"), rs.getBigDecimal("success_rate"),
                rs.getLong("field_pass_transaction_count"), rs.getBigDecimal("comparison_pass_rate"),
                rs.getLong("issue_total_count"), rs.getLong("duplicate_issue_count")));
    }

    private List<SummaryRow> summaryRowsWithWeeklyDuplicates(String currentBatchId, String baselineBatchId) {
        List<SummaryRow> rows = summaryRows(currentBatchId);
        Map<String, Long> duplicateIssues = baselineBatchId == null
                ? Map.of()
                : repeatedIssueCountsByCurrentModule(currentBatchId, baselineBatchId);
        List<SummaryRow> result = new ArrayList<>();
        for (SummaryRow row : rows) {
            result.add(row.withDuplicateIssueCount(duplicateIssues.getOrDefault(row.moduleName(), 0L)));
        }
        return result;
    }

    private Map<String, Long> repeatedIssueCountsByCurrentModule(String currentBatchId, String baselineBatchId) {
        Set<String> baselineKeys = issueKeys(baselineBatchId);
        if (baselineKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> repeatedKeysByModule = new HashMap<>();
        for (IssueSnapshot issue : issueSnapshots(currentBatchId, "module")) {
            if (issue.issueKey() != null && baselineKeys.contains(issue.issueKey())) {
                repeatedKeysByModule.computeIfAbsent(issue.dimension(), ignored -> new HashSet<>()).add(issue.issueKey());
            }
        }
        Map<String, Long> result = new HashMap<>();
        repeatedKeysByModule.forEach((module, keys) -> result.put(module, (long) keys.size()));
        return result;
    }

    private Set<String> issueKeys(String batchId) {
        Set<String> result = new HashSet<>();
        jdbc.query(issueSnapshotSql("module"), params(batchId), (RowCallbackHandler) rs -> {
            String issueKey = rs.getString("issue_key");
            if (issueKey != null && !issueKey.isBlank()) {
                result.add(issueKey);
            }
        });
        return result;
    }

    private static Map<String, Long> issueTotalsByModule(List<SummaryRow> rows) {
        Map<String, Long> result = new HashMap<>();
        for (SummaryRow row : rows) {
            result.put(row.moduleName(), row.issueTotalCount());
        }
        return result;
    }

    private void writeModule(SXSSFWorkbook book, String batchId, String module, Styles styles) {
        long started = System.nanoTime();
        SXSSFSheet sheet = book.createSheet(uniqueSheetName(book, module));
        Row header = sheet.createRow(0);
        header.setHeightInPoints(24f);
        for (int i = 0; i < DETAIL_HEADERS.length; i++) {
            cell(header, i, DETAIL_HEADERS[i], styles.detailHeader);
        }
        int[] row = {1};
        int tranRows = streamDetails(sheet, batchId, module, "ana_tran_diff_tracking_export", row, styles);
        int fieldRows = streamDetails(sheet, batchId, module, "ana_field_diff_tracking_export", row, styles);
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, DETAIL_HEADERS.length - 1));
        sheet.createFreezePane(0, 1);
        for (int i = 0; i < DETAIL_HEADERS.length; i++) {
            sheet.setColumnWidth(i, i == 8 || i == 11 || i == 12 ? 9000 : 3600);
        }
        log.info("日报明细Sheet写入完成，batchId={}, module={}, tranRows={}, fieldRows={}, elapsedMs={}",
                batchId, module, tranRows, fieldRows, elapsedMs(started));
    }

    private void writeDelayDistribution(SXSSFWorkbook book, String batchId, String baselineBatchId, String dimension,
                                        boolean weekly, Styles styles) {
        long started = System.nanoTime();
        SXSSFSheet sheet = book.createSheet("问题处理延迟分布");
        LocalDate currentReportDate = reportDate(batchId);
        List<IssueSnapshot> currentIssues = issueSnapshots(batchId, weekly ? "module" : "resolver");
        List<IssueSnapshot> baselineIssues = issueSnapshots(baselineBatchId, weekly ? "module" : "resolver");
        Map<String, DelayDistributionRow> rows = delayDistributionRows(currentIssues, baselineIssues,
                currentReportDate);

        mergedCell(sheet, 0, 0, 0, 9, (weekly ? "周期周报" : "日报明细") + " - 问题处理延迟分布",
                styles.summaryTitleStyle(true));
        cell(sheet.createRow(3), 0, "延期天数分布", styles.summaryManualFillHeaderStyle());
        int nextRow = writeDistributionTable(sheet, 4, dimension,
                new String[] {"延期1天", "延期2天", "延期3天", "延期4天", "延期4天以上"},
                rows, true, styles);

        cell(sheet.createRow(nextRow + 2), 0, "重复次数分布", styles.summaryManualFillHeaderStyle());
        writeDistributionTable(sheet, nextRow + 3, dimension,
                new String[] {"重复1次", "重复2次", "重复3次", "重复4次", "重复4次以上"},
                rows, false, styles);

        for (int i = 0; i < 10; i++) {
            sheet.setColumnWidth(i, i == 9 ? 7600 : 4200);
        }
        sheet.createFreezePane(0, 5);
        log.info("{}处理延迟分布Sheet写入完成，batchId={}, baselineBatchId={}, dimension={}, currentIssueCount={}, baselineIssueCount={}, rowCount={}, currentReportDate={}, delayGraceDays={}, elapsedMs={}",
                weekly ? "周报" : "日报", batchId, baselineBatchId, dimension, currentIssues.size(), baselineIssues.size(),
                rows.size(), currentReportDate, delayGraceDays, elapsedMs(started));
    }

    private int writeDistributionTable(SXSSFSheet sheet, int headerRowIndex, String dimension, String[] bucketHeaders,
                                       Map<String, DelayDistributionRow> rows, boolean delay, Styles styles) {
        Row header = sheet.createRow(headerRowIndex);
        String[] fixedHeaders = {dimension, "交易数量（覆盖交易数）", "问题总量", "问题解决数量"};
        for (int i = 0; i < fixedHeaders.length; i++) {
            cell(header, i, fixedHeaders[i], styles.detailHeader);
        }
        for (int i = 0; i < bucketHeaders.length; i++) {
            cell(header, 4 + i, bucketHeaders[i], styles.detailHeader);
        }
        cell(header, 9, "统计口径", styles.detailHeader);

        int rowIndex = headerRowIndex + 1;
        DelayDistributionRow total = new DelayDistributionRow("合计");
        for (DelayDistributionRow data : rows.values()) {
            writeDistributionDataRow(sheet.createRow(rowIndex++), data, delay, styles.rowStyle(rowIndex - headerRowIndex - 1));
            total.add(data);
        }
        writeDistributionDataRow(sheet.createRow(rowIndex), total, delay, styles.summaryTotalStyle(true));
        return rowIndex;
    }

    private void writeDistributionDataRow(Row row, DelayDistributionRow data, boolean delay, CellStyle style) {
        cell(row, 0, data.dimension(), style);
        cell(row, 1, text(data.transactionCount()), style);
        cell(row, 2, text(data.issueTotal()), style);
        cell(row, 3, text(data.resolvedIssueCount()), style);
        long[] buckets = delay ? data.delayBuckets() : data.repeatBuckets();
        for (int i = 0; i < buckets.length; i++) {
            cell(row, 4 + i, text(buckets[i]), style);
        }
        cell(row, 9, delay ? "当前批次日期 - 首次出现日期（自然日）" : "历史出现次数 + 1", style);
    }

    private List<IssueSnapshot> issueSnapshots(String batchId, String dimensionKind) {
        if (batchId == null || batchId.isBlank()) {
            return List.of();
        }
        return jdbc.query(issueSnapshotSql(dimensionKind), params(batchId), (rs, rowNum) -> issueSnapshot(rs));
    }

    private String issueSnapshotSql(String dimensionKind) {
        String dimensionExpression;
        if ("resolver".equals(dimensionKind)) {
            dimensionExpression = "coalesce(nullif(resolver, ''), nullif(transaction_owner, ''), '未分配')";
        } else {
            dimensionExpression = "coalesce(nullif(module_name, ''), '未归属')";
        }
        return "select " + dimensionExpression + " as dimension, tran_code, issue_key, "
                + "historical_occurrence_count, first_seen_date "
                + "from ana_tran_diff_tracking_export where source_batch_id=:batchId and issue_key is not null "
                + "union all "
                + "select " + dimensionExpression + " as dimension, tran_code, issue_key, "
                + "historical_occurrence_count, first_seen_date "
                + "from ana_field_diff_tracking_export where source_batch_id=:batchId and issue_key is not null";
    }

    private IssueSnapshot issueSnapshot(ResultSet rs) throws java.sql.SQLException {
        java.sql.Date firstSeenDate = rs.getDate("first_seen_date");
        return new IssueSnapshot(
                text(rs.getString("dimension")),
                rs.getString("tran_code"),
                rs.getString("issue_key"),
                rs.getLong("historical_occurrence_count"),
                firstSeenDate == null ? null : firstSeenDate.toLocalDate());
    }

    private Map<String, DelayDistributionRow> delayDistributionRows(List<IssueSnapshot> currentIssues,
                                                                    List<IssueSnapshot> baselineIssues,
                                                                    LocalDate currentReportDate) {
        Map<String, DelayDistributionRow> result = new LinkedHashMap<>();
        Map<String, Long> baselineIssueTotals = issueTotalsByDimension(baselineIssues);
        Map<String, Long> repeatedIssues = repeatedIssueCountsByDimension(currentIssues, baselineIssues);
        currentIssues.stream()
                .map(IssueSnapshot::dimension)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .forEach(dimension -> result.put(dimension, new DelayDistributionRow(dimension)));
        for (IssueSnapshot issue : currentIssues) {
            DelayDistributionRow row = result.computeIfAbsent(issue.dimension(), DelayDistributionRow::new);
            row.addIssue(issue, currentReportDate, delayGraceDays);
        }
        for (DelayDistributionRow row : result.values()) {
            long baselineTotal = baselineIssueTotals.getOrDefault(row.dimension(), 0L);
            long repeated = repeatedIssues.getOrDefault(row.dimension(), 0L);
            row.setResolvedIssueCount(Math.max(0, baselineTotal - repeated));
        }
        return result;
    }

    private Map<String, Long> issueTotalsByDimension(List<IssueSnapshot> issues) {
        Map<String, Set<String>> keysByDimension = new HashMap<>();
        Map<String, Long> fallbackCounts = new HashMap<>();
        for (IssueSnapshot issue : issues) {
            fallbackCounts.merge(issue.dimension(), 1L, Long::sum);
            if (issue.issueKey() != null && !issue.issueKey().isBlank()) {
                keysByDimension.computeIfAbsent(issue.dimension(), ignored -> new HashSet<>()).add(issue.issueKey());
            }
        }
        Map<String, Long> result = new HashMap<>(fallbackCounts);
        keysByDimension.forEach((dimension, keys) -> result.put(dimension, (long) keys.size()));
        return result;
    }

    private Map<String, Long> repeatedIssueCountsByDimension(List<IssueSnapshot> currentIssues,
                                                             List<IssueSnapshot> baselineIssues) {
        Map<String, Set<String>> baselineKeysByDimension = issueKeysByDimension(baselineIssues);
        Map<String, Set<String>> repeatedByDimension = new HashMap<>();
        for (IssueSnapshot issue : currentIssues) {
            Set<String> baselineKeys = baselineKeysByDimension.get(issue.dimension());
            if (baselineKeys != null && issue.issueKey() != null && baselineKeys.contains(issue.issueKey())) {
                repeatedByDimension.computeIfAbsent(issue.dimension(), ignored -> new HashSet<>()).add(issue.issueKey());
            }
        }
        Map<String, Long> result = new HashMap<>();
        repeatedByDimension.forEach((dimension, keys) -> result.put(dimension, (long) keys.size()));
        return result;
    }

    private Map<String, Set<String>> issueKeysByDimension(List<IssueSnapshot> issues) {
        Map<String, Set<String>> result = new HashMap<>();
        for (IssueSnapshot issue : issues) {
            if (issue.issueKey() != null && !issue.issueKey().isBlank()) {
                result.computeIfAbsent(issue.dimension(), ignored -> new HashSet<>()).add(issue.issueKey());
            }
        }
        return result;
    }

    private int streamDetails(SXSSFSheet sheet, String batchId, String module, String table, int[] row, Styles styles) {
        int startRow = row[0];
        String sql = "select module_name,row_no,source_batch_id,tran_code,tran_name,problem_level,registration_date,"
                + "field_name,problem_description,transaction_owner,problem_type,preliminary_analysis,final_solution,"
                + "resolution_date,coordination_required,resolver,tran_seq_no,defect_fix_date,"
                + "historical_occurrence_count,first_seen_date,previous_seen_date from " + table
                + " where source_batch_id=? and module_name=? order by row_no";
        jdbc.getJdbcTemplate().query((PreparedStatementCreator) connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setFetchSize(500);
            statement.setString(1, batchId);
            statement.setString(2, module);
            return statement;
        }, rs -> {
            int dataOrdinal = row[0];
            Row r = sheet.createRow(row[0]++);
            CellStyle rowStyle = styles.rowStyle(dataOrdinal);
            for (int i = 0; i < DETAIL_HEADERS.length; i++) {
                cell(r, i, i == 18 ? "" : text(rs.getObject(i < 18 ? i + 1 : i)), rowStyle);
            }
        });
        return row[0] - startRow;
    }

    private List<String> modules(String batchId) {
        Set<String> result = new LinkedHashSet<>();
        jdbc.query("""
                select module_name from ana_tran_diff_tracking_export where source_batch_id=:batchId
                union
                select module_name from ana_field_diff_tracking_export where source_batch_id=:batchId
                order by module_name
                """, params(batchId), (RowCallbackHandler) rs -> result.add(text(rs.getString(1))));
        return new ArrayList<>(result);
    }

    private MapSqlParameterSource params(String batchId) {
        return new MapSqlParameterSource("batchId", batchId);
    }

    private static void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void mergedCell(SXSSFSheet sheet, int firstRow, int lastRow, int firstCol, int lastCol,
                                   String value, CellStyle style) {
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex) == null ? sheet.createRow(rowIndex) : sheet.getRow(rowIndex);
            for (int colIndex = firstCol; colIndex <= lastCol; colIndex++) {
                Cell cell = row.getCell(colIndex) == null ? row.createCell(colIndex) : row.getCell(colIndex);
                cell.setCellStyle(style);
            }
        }
        sheet.getRow(firstRow).getCell(firstCol).setCellValue(value);
        sheet.addMergedRegion(new CellRangeAddress(firstRow, lastRow, firstCol, lastCol));
    }

    private static void percentCell(Row row, int column, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? 0d : value.doubleValue());
        cell.setCellStyle(style);
    }

    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal previousResolutionRate(Long previousIssueTotal, long duplicateIssueCount) {
        if (previousIssueTotal == null || previousIssueTotal == 0) {
            return BigDecimal.ZERO;
        }
        return rate(previousIssueTotal - duplicateIssueCount, previousIssueTotal);
    }

    private static void blankCells(Row row, int firstColumn, int lastColumn, CellStyle style) {
        for (int column = firstColumn; column <= lastColumn; column++) {
            cell(row, column, "", style);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String uniqueSheetName(SXSSFWorkbook book, String name) {
        String base = name == null || name.isBlank() ? "未配置领域" : name.replaceAll("[\\\\/:*?\\[\\]]", "_");
        base = base.substring(0, Math.min(31, base.length()));
        String candidate = base;
        for (int i = 2; book.getSheet(candidate) != null; i++) {
            candidate = base.substring(0, Math.min(base.length(), 28)) + "-" + i;
        }
        return candidate;
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private record SummaryRow(
            String batchId,
            String moduleName,
            long covered528InterfaceCount,
            long sentTransactionCount,
            long compResult1Count,
            long compResult2Count,
            long compResult3Count,
            long compResult4Count,
            long compResult8Count,
            BigDecimal successRate,
            long fieldPassTransactionCount,
            BigDecimal comparisonPassRate,
            long issueTotalCount,
            long duplicateIssueCount
    ) {
        private SummaryRow withDuplicateIssueCount(long duplicateIssueCount) {
            return new SummaryRow(batchId, moduleName, covered528InterfaceCount, sentTransactionCount,
                    compResult1Count, compResult2Count, compResult3Count, compResult4Count, compResult8Count,
                    successRate, fieldPassTransactionCount, comparisonPassRate, issueTotalCount, duplicateIssueCount);
        }
    }

    private record IssueSnapshot(
            String dimension,
            String tranCode,
            String issueKey,
            long historicalOccurrenceCount,
            LocalDate firstSeenDate
    ) {
    }

    private static final class DelayDistributionRow {
        private final String dimension;
        private final Set<String> tranCodes = new HashSet<>();
        private final long[] delayBuckets = new long[5];
        private final long[] repeatBuckets = new long[5];
        private long issueTotal;
        private long resolvedIssueCount;

        private DelayDistributionRow(String dimension) {
            this.dimension = dimension;
        }

        private void addIssue(IssueSnapshot issue, LocalDate currentReportDate, int delayGraceDays) {
            issueTotal++;
            if (issue.tranCode() != null && !issue.tranCode().isBlank()) {
                tranCodes.add(issue.tranCode());
            }
            int delayBucket = delayBucket(issue.firstSeenDate(), currentReportDate, delayGraceDays);
            if (delayBucket >= 0) {
                delayBuckets[delayBucket]++;
            }
            repeatBuckets[repeatBucket(issue.historicalOccurrenceCount())]++;
        }

        private void add(DelayDistributionRow other) {
            tranCodes.addAll(other.tranCodes);
            issueTotal += other.issueTotal;
            resolvedIssueCount += other.resolvedIssueCount;
            for (int i = 0; i < 5; i++) {
                delayBuckets[i] += other.delayBuckets[i];
                repeatBuckets[i] += other.repeatBuckets[i];
            }
        }

        private void setResolvedIssueCount(long resolvedIssueCount) {
            this.resolvedIssueCount = resolvedIssueCount;
        }

        private String dimension() {
            return dimension;
        }

        private long transactionCount() {
            return tranCodes.size();
        }

        private long issueTotal() {
            return issueTotal;
        }

        private long resolvedIssueCount() {
            return resolvedIssueCount;
        }

        private long[] delayBuckets() {
            return delayBuckets;
        }

        private long[] repeatBuckets() {
            return repeatBuckets;
        }

        private static int delayBucket(LocalDate firstSeenDate, LocalDate currentReportDate, int delayGraceDays) {
            if (firstSeenDate == null || currentReportDate == null) {
                return 4;
            }
            long days = Math.max(0, ChronoUnit.DAYS.between(firstSeenDate, currentReportDate));
            long overdueDays = days - Math.max(0, delayGraceDays);
            if (overdueDays <= 0) {
                return -1;
            }
            return overdueDays >= 5 ? 4 : (int) overdueDays - 1;
        }

        private static int repeatBucket(long historicalOccurrenceCount) {
            long repeatCount = Math.max(1, historicalOccurrenceCount + 1);
            return repeatCount >= 5 ? 4 : (int) repeatCount - 1;
        }
    }

    private record SummaryTotals(
            long covered528InterfaceCount,
            long sentTransactionCount,
            long compResult1Count,
            long compResult2Count,
            long compResult3Count,
            long compResult4Count,
            long compResult8Count,
            long fieldPassTransactionCount,
            long issueTotalCount,
            long duplicateIssueCount
    ) {
        private static SummaryTotals of(List<SummaryRow> rows) {
            return new SummaryTotals(
                    rows.stream().mapToLong(SummaryRow::covered528InterfaceCount).sum(),
                    rows.stream().mapToLong(SummaryRow::sentTransactionCount).sum(),
                    rows.stream().mapToLong(SummaryRow::compResult1Count).sum(),
                    rows.stream().mapToLong(SummaryRow::compResult2Count).sum(),
                    rows.stream().mapToLong(SummaryRow::compResult3Count).sum(),
                    rows.stream().mapToLong(SummaryRow::compResult4Count).sum(),
                    rows.stream().mapToLong(SummaryRow::compResult8Count).sum(),
                    rows.stream().mapToLong(SummaryRow::fieldPassTransactionCount).sum(),
                    rows.stream().mapToLong(SummaryRow::issueTotalCount).sum(),
                    rows.stream().mapToLong(SummaryRow::duplicateIssueCount).sum());
        }
    }

    private record CommandPosition(long commandId, java.time.Instant createdTime) {
    }

    private static final class Styles {
        private static final String TITLE_NAVY = "16365C";
        private static final String TABLE_TEAL = "0E566F";
        private static final String STRIPE_BLUE = "BDE7F4";
        private static final String WHITE = "FFFFFF";
        private static final String PREVIOUS_TITLE_GREEN = "B7D7C0";
        private static final String PREVIOUS_MAIN_GREEN = "C6E0B4";
        private static final String PREVIOUS_SUB_GREEN = "E2F0D9";
        private static final String CURRENT_TITLE_PINK = "F4CCCC";
        private static final String CURRENT_HEADER_PINK = "FCE4D6";
        private static final String SUMMARY_ISSUE_YELLOW = "FFF2CC";
        private static final String SUMMARY_MANUAL_FILL_YELLOW = "FFF8E1";
        private static final String SUMMARY_TOTAL_BLUE = "EEF2F7";
        private static final String CURRENT_SUMMARY_TOTAL_PINK = "F8F3F0";
        private final CellStyle detailHeader;
        private final CellStyle previousSummaryTitle;
        private final CellStyle previousSummaryMainHeader;
        private final CellStyle previousSummarySubHeader;
        private final CellStyle currentSummaryTitle;
        private final CellStyle currentSummaryHeader;
        private final CellStyle summaryIssueHeader;
        private final CellStyle summaryManualFillHeader;
        private final CellStyle summaryManualFillBody;
        private final CellStyle summaryBody;
        private final CellStyle summaryPercent;
        private final CellStyle previousSummaryTotal;
        private final CellStyle previousSummaryTotalPercent;
        private final CellStyle currentSummaryTotal;
        private final CellStyle currentSummaryTotalPercent;
        private final CellStyle oddBody;
        private final CellStyle evenBody;
        private final CellStyle oddPercent;
        private final CellStyle evenPercent;

        private Styles(SXSSFWorkbook book) {
            detailHeader = headerStyle(book, TABLE_TEAL);
            previousSummaryTitle = summaryHeaderStyle(book, PREVIOUS_TITLE_GREEN);
            previousSummaryMainHeader = summaryHeaderStyle(book, PREVIOUS_MAIN_GREEN);
            previousSummarySubHeader = summaryHeaderStyle(book, PREVIOUS_SUB_GREEN);
            currentSummaryTitle = summaryHeaderStyle(book, CURRENT_TITLE_PINK);
            currentSummaryHeader = summaryHeaderStyle(book, CURRENT_HEADER_PINK);
            summaryIssueHeader = summaryHeaderStyle(book, SUMMARY_ISSUE_YELLOW);
            summaryManualFillHeader = summaryHeaderStyle(book, SUMMARY_MANUAL_FILL_YELLOW);
            summaryManualFillBody = style(book, SUMMARY_MANUAL_FILL_YELLOW);
            summaryBody = style(book, WHITE);
            summaryPercent = style(book, WHITE);
            previousSummaryTotal = style(book, SUMMARY_TOTAL_BLUE);
            previousSummaryTotalPercent = style(book, SUMMARY_TOTAL_BLUE);
            currentSummaryTotal = style(book, CURRENT_SUMMARY_TOTAL_PINK);
            currentSummaryTotalPercent = style(book, CURRENT_SUMMARY_TOTAL_PINK);
            oddBody = style(book, STRIPE_BLUE);
            evenBody = style(book, WHITE);
            oddPercent = style(book, STRIPE_BLUE);
            evenPercent = style(book, WHITE);
            DataFormat format = book.createDataFormat();
            summaryPercent.setDataFormat(format.getFormat("0.00%"));
            previousSummaryTotalPercent.setDataFormat(format.getFormat("0.00%"));
            currentSummaryTotalPercent.setDataFormat(format.getFormat("0.00%"));
            oddPercent.setDataFormat(format.getFormat("0.00%"));
            evenPercent.setDataFormat(format.getFormat("0.00%"));
        }

        private CellStyle rowStyle(int oneBasedDataRow) {
            return oneBasedDataRow % 2 == 1 ? oddBody : evenBody;
        }

        private CellStyle percentStyle(int oneBasedDataRow) {
            return oneBasedDataRow % 2 == 1 ? oddPercent : evenPercent;
        }

        private CellStyle summaryTitleStyle(boolean current) {
            return current ? currentSummaryTitle : previousSummaryTitle;
        }

        private CellStyle summaryMainHeaderStyle(boolean current) {
            return current ? currentSummaryHeader : previousSummaryMainHeader;
        }

        private CellStyle summarySubHeaderStyle(boolean current) {
            return current ? currentSummaryHeader : previousSummarySubHeader;
        }

        private CellStyle summaryIssueHeaderStyle() {
            return summaryIssueHeader;
        }

        private CellStyle summaryManualFillHeaderStyle() {
            return summaryManualFillHeader;
        }

        private CellStyle summaryManualFillStyle() {
            return summaryManualFillBody;
        }

        private CellStyle summaryBodyStyle() {
            return summaryBody;
        }

        private CellStyle summaryPercentStyle() {
            return summaryPercent;
        }

        private CellStyle summaryTotalStyle(boolean current) {
            return current ? currentSummaryTotal : previousSummaryTotal;
        }

        private CellStyle summaryTotalPercentStyle(boolean current) {
            return current ? currentSummaryTotalPercent : previousSummaryTotalPercent;
        }

        private static CellStyle headerStyle(SXSSFWorkbook book, String color) {
            return headerStyle(book, color, IndexedColors.WHITE.getIndex());
        }

        private static CellStyle summaryHeaderStyle(SXSSFWorkbook book, String color) {
            return headerStyle(book, color, IndexedColors.BLACK.getIndex());
        }

        private static CellStyle headerStyle(SXSSFWorkbook book, String color, short fontColor) {
            CellStyle value = style(book, color);
            Font font = book.createFont();
            font.setBold(true);
            font.setColor(fontColor);
            value.setFont(font);
            value.setVerticalAlignment(VerticalAlignment.CENTER);
            value.setBorderTop(BorderStyle.MEDIUM);
            value.setBorderBottom(BorderStyle.MEDIUM);
            return value;
        }

        private static CellStyle style(SXSSFWorkbook book, String color) {
            CellStyle value = book.createCellStyle();
            value.setWrapText(true);
            value.setAlignment(HorizontalAlignment.CENTER);
            value.setVerticalAlignment(VerticalAlignment.CENTER);
            value.setBorderBottom(BorderStyle.THIN);
            value.setBorderTop(BorderStyle.THIN);
            value.setBorderLeft(BorderStyle.THIN);
            value.setBorderRight(BorderStyle.THIN);
            if (color != null) {
                ((XSSFCellStyle) value).setFillForegroundColor(rgb(color));
                value.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            return value;
        }

        private static XSSFColor rgb(String hex) {
            return new XSSFColor(new byte[] {
                    (byte) Integer.parseInt(hex.substring(0, 2), 16),
                    (byte) Integer.parseInt(hex.substring(2, 4), 16),
                    (byte) Integer.parseInt(hex.substring(4, 6), 16)
            }, null);
        }
    }
}
