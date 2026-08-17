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
    private static final String[] BASE_DETAIL_HEADERS = {"领域", "序号", "批次", "交易码", "交易名称", "问题级别", "登记日期",
            "字段名", "问题描述", "交易负责人", "问题类型", "初步问题分析", "最终处理方案", "解决日期", "需协同组",
            "解决人员", "流水号", "缺陷修复日期", "备注", "该问题出现在的交易笔数", "issue_id", "issue_key",
            "历史出现次数", "首次出现日期", "上次出现日期"};
    private static final String[] DAILY_DETAIL_HEADERS = {"领域", "序号", "批次", "交易码", "交易名称", "问题级别", "登记日期",
            "字段名", "问题描述", "交易负责人", "问题类型", "初步问题分析", "最终处理方案", "解决日期", "需协同组",
            "解决人员", "流水号", "全局流水号", "缺陷修复日期", "备注", "该问题出现在的交易笔数", "issue_id", "issue_key",
            "历史出现次数", "首次出现日期", "上次出现日期"};
    private static final String SUCCESS_RATE_FORMULA = "成功率 =（二者均失败响应码一致 + 二者均成功）÷（发送交易量 − 响应码忽略）";
    private static final String COMPARISON_PASS_RATE_FORMULA = "比对通过率 =（二者均成功且无字段差异交易数 + 二者均失败响应码一致）÷（发送交易量 − 响应码忽略）";
    private static final String RESOLUTION_RATE_FORMULA = "上轮问题解决率 =（上一批次问题总数 − 上一批次未解决问题数量）÷ 上一批次问题总数";
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
        streamDaily(batchId, output, false);
    }

    public void streamRawDaily(String batchId, OutputStream output) throws IOException {
        streamDaily(batchId, output, true);
    }

    private void streamDaily(String batchId, OutputStream output, boolean rawFieldValues) throws IOException {
        long started = System.nanoTime();
        log.info("{}导出开始，batchId={}, delayGraceDays={}", rawFieldValues ? "未脱敏日报" : "日报", batchId, delayGraceDays);
        SXSSFWorkbook workbook = new SXSSFWorkbook(200);
        workbook.setCompressTempFiles(true);
        try {
            Styles styles = new Styles(workbook);
            String previousBatchId = previousSucceededBatchId(batchId);
            List<String> modules = modules(batchId);
            log.info("日报导出批次信息已加载，batchId={}, previousBatchId={}, moduleCount={}",
                    batchId, previousBatchId, modules.size());
            writeSummary(workbook, batchId, previousBatchId, false, styles);
            writeInterfaceSummary(workbook, batchId, styles);
            for (String module : modules) {
                writeModule(workbook, batchId, module, rawFieldValues, styles);
            }
            writeDelayDistribution(workbook, batchId, previousSucceededBatchId(batchId), "解决人员", false, styles);
            workbook.write(output);
            log.info("{}导出完成，batchId={}, previousBatchId={}, sheetCount={}, elapsedMs={}",
                    rawFieldValues ? "未脱敏日报" : "日报", batchId, previousBatchId, workbook.getNumberOfSheets(), elapsedMs(started));
        } catch (IOException | RuntimeException e) {
            log.error("{}导出失败，batchId={}, elapsedMs={}", rawFieldValues ? "未脱敏日报" : "日报", batchId, elapsedMs(started), e);
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

    public void streamFullIssueList(String batchId, OutputStream output) throws IOException {
        long started = System.nanoTime();
        log.info("全量问题清单导出开始，batchId={}", batchId);
        SXSSFWorkbook workbook = new SXSSFWorkbook(200);
        workbook.setCompressTempFiles(true);
        try {
            Styles styles = new Styles(workbook);
            int rowCount = writeFullIssueList(workbook, batchId, styles);
            workbook.write(output);
            log.info("全量问题清单导出完成，batchId={}, rowCount={}, elapsedMs={}", batchId, rowCount, elapsedMs(started));
        } catch (IOException | RuntimeException e) {
            log.error("全量问题清单导出失败，batchId={}, elapsedMs={}", batchId, elapsedMs(started), e);
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
        List<SummaryRow> currentRows = summaryRows(batchId, weekly);
        List<SummaryRow> previousRows = previousBatchId == null ? List.of() : summaryRows(previousBatchId);
        log.info("{}汇总Sheet数据已加载，batchId={}, previousBatchId={}, currentRowCount={}, previousRowCount={}",
                weekly ? "周报" : "日报", batchId, previousBatchId, currentRows.size(), previousRows.size());

        String previousTitle = weekly ? "周期周报 - 上周期" : "上一批次";
        int nextRow = writeSummarySection(sheet, 0, previousBatchId, previousTitle, previousRows, false, styles);
        writeSummarySection(sheet, nextRow + 2, batchId, "本批次", currentRows, true, styles);

        for (int i = 0; i < 20; i++) {
            sheet.setColumnWidth(i, i == 1 ? 4600 : 3600);
        }
    }

    private int writeSummarySection(SXSSFSheet sheet, int startRow, String batchId, String title, List<SummaryRow> rows,
                                    boolean current, Styles styles) {
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
            writeSummaryDataRow(sheet.createRow(rowIndex++), row, current, dataOrdinal++, styles);
        }
        writeSummaryTotalRow(sheet.createRow(rowIndex), rows, current, dataOrdinal, styles);
        int formulaRows = writeSummaryFormulas(sheet, rowIndex + 1, current, styles);
        return rowIndex + formulaRows;
    }

    private int writeSummaryFormulas(SXSSFSheet sheet, int startRow, boolean current, Styles styles) {
        int lastColumn = current ? 20 : 18;
        StringBuilder formulas = new StringBuilder(SUCCESS_RATE_FORMULA)
                .append('\n')
                .append(COMPARISON_PASS_RATE_FORMULA);
        if (current) {
            formulas.append('\n').append(RESOLUTION_RATE_FORMULA);
        }
        mergedCell(sheet, startRow, startRow, 0, lastColumn, formulas.toString(), styles.summaryFormulaStyle());
        sheet.getRow(startRow).setHeightInPoints(current ? 45f : 30f);
        return 1;
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
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex, 4, 9, "交易状态分类统计", mainStyle);

        String[] statusHeaders = {"528成功/CCBS失败", "528失败/CCBS成功", "二者均失败响应码一致",
                "二者均失败响应码不一致", "二者均成功"};
        for (int i = 0; i < statusHeaders.length; i++) {
            cell(subHeader, 4 + i, statusHeaders[i], subStyle);
        }
        cell(subHeader, 9, "响应码忽略", subStyle);

        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 10, 10, "成功率", mainStyle);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 11, 11, "比对通过率", mainStyle);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 12, 12, "问题总数", issueStyle);

        if (current) {
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 13, 13, "上一批次未解决问题数量", issueStyle);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 14, 14, "上轮问题解决率", issueStyle);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex, 15, 19,
                    "上一批次已解决问题分类统计（待验证）", manualFillStyle);
            writeSolvedIssueSubHeaders(subHeader, 15, manualFillStyle);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 20, 20, "问题解决进度", manualFillStyle);
        } else {
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex, 13, 17,
                    "已解决问题分类统计（待验证）", manualFillStyle);
            writeSolvedIssueSubHeaders(subHeader, 13, manualFillStyle);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 18, 18, "问题解决进度", manualFillStyle);
        }
    }

    private void writeSolvedIssueSubHeaders(Row subHeader, int firstColumn, CellStyle style) {
        String[] solvedHeaders = {"迁移问题", "防腐问题", "功能问题", "新核心下线", "其他问题"};
        for (int i = 0; i < solvedHeaders.length; i++) {
            cell(subHeader, firstColumn + i, solvedHeaders[i], style);
        }
    }

    private void writeSummaryDataRow(Row excelRow, SummaryRow row,
                                     boolean current, int dataOrdinal, Styles styles) {
        CellStyle rowStyle = styles.summaryBodyStyle();
        cell(excelRow, 0, row.batchId(), rowStyle);
        cell(excelRow, 1, row.moduleName(), rowStyle);
        numericCell(excelRow, 2, row.covered528InterfaceCount(), rowStyle);
        numericCell(excelRow, 3, row.sentTransactionCount(), rowStyle);
        numericCell(excelRow, 4, row.compResult2Count(), rowStyle);
        numericCell(excelRow, 5, row.compResult1Count(), rowStyle);
        numericCell(excelRow, 6, row.compResult3Count(), rowStyle);
        numericCell(excelRow, 7, row.compResult8Count(), rowStyle);
        numericCell(excelRow, 8, row.compResult4Count(), rowStyle);
        numericCell(excelRow, 9, row.compResult5Count(), rowStyle);
        percentCell(excelRow, 10, row.successRate(), styles.summaryPercentStyle());
        percentCell(excelRow, 11, row.comparisonPassRate(), styles.summaryPercentStyle());
        numericCell(excelRow, 12, row.issueTotalCount(), rowStyle);
        if (current) {
            blankCells(excelRow, 13, 14, rowStyle);
            blankCells(excelRow, 15, 20, styles.summaryManualFillStyle());
        } else {
            blankCells(excelRow, 13, 18, styles.summaryManualFillStyle());
        }
    }

    private void writeSummaryTotalRow(Row excelRow, List<SummaryRow> rows,
                                      boolean current, int dataOrdinal, Styles styles) {
        CellStyle rowStyle = styles.summaryTotalStyle(current);
        SummaryTotals totals = SummaryTotals.of(rows);
        cell(excelRow, 0, "", rowStyle);
        cell(excelRow, 1, "合计", rowStyle);
        numericCell(excelRow, 2, totals.covered528InterfaceCount(), rowStyle);
        numericCell(excelRow, 3, totals.sentTransactionCount(), rowStyle);
        numericCell(excelRow, 4, totals.compResult2Count(), rowStyle);
        numericCell(excelRow, 5, totals.compResult1Count(), rowStyle);
        numericCell(excelRow, 6, totals.compResult3Count(), rowStyle);
        numericCell(excelRow, 7, totals.compResult8Count(), rowStyle);
        numericCell(excelRow, 8, totals.compResult4Count(), rowStyle);
        numericCell(excelRow, 9, totals.compResult5Count(), rowStyle);
        long effectiveTotal = Math.max(0, totals.sentTransactionCount() - totals.compResult5Count());
        percentCell(excelRow, 10, rate(totals.compResult3Count() + totals.compResult4Count(),
                effectiveTotal), styles.summaryTotalPercentStyle(current));
        percentCell(excelRow, 11, rate(totals.fieldPassTransactionCount() + totals.compResult3Count(),
                effectiveTotal), styles.summaryTotalPercentStyle(current));
        numericCell(excelRow, 12, totals.issueTotalCount(), rowStyle);
        if (current) {
            blankCells(excelRow, 13, 14, rowStyle);
            blankCells(excelRow, 15, 20, rowStyle);
        } else {
            blankCells(excelRow, 13, 18, rowStyle);
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
        return summaryRows(batchId, false);
    }

    private List<SummaryRow> summaryRows(String batchId, boolean weekly) {
        return jdbc.query("""
                select batch_id, module_name, covered_528_interface_count, sent_transaction_count,
                       comp_result_1_count, comp_result_2_count, comp_result_3_count, comp_result_4_count,
                       comp_result_8_count, comp_result_5_count, success_rate, field_pass_transaction_count, comparison_pass_rate,
                       issue_total_count,
                       case when :weekly then weekly_duplicate_issue_count else daily_duplicate_issue_count end duplicate_issue_count
                  from ana_report_export_summary
                 where batch_id=:batchId
                 order by module_name
                """, params(batchId).addValue("weekly", weekly), (rs, rowNum) -> new SummaryRow(
                rs.getString("batch_id"), rs.getString("module_name"),
                rs.getLong("covered_528_interface_count"), rs.getLong("sent_transaction_count"),
                rs.getLong("comp_result_1_count"), rs.getLong("comp_result_2_count"),
                rs.getLong("comp_result_3_count"), rs.getLong("comp_result_4_count"),
                rs.getLong("comp_result_8_count"), rs.getLong("comp_result_5_count"), rs.getBigDecimal("success_rate"),
                rs.getLong("field_pass_transaction_count"), rs.getBigDecimal("comparison_pass_rate"),
                rs.getLong("issue_total_count"), rs.getLong("duplicate_issue_count")));
    }

    private void writeInterfaceSummary(SXSSFWorkbook book, String batchId, Styles styles) {
        long started = System.nanoTime();
        SXSSFSheet sheet = book.createSheet("接口比对明细");
        String[] headers = {"批次号", "交易码", "S码", "交易描述", "开发负责人", "行内负责人", "领域", "发送交易量",
                "528成功/CCBS失败", "528失败/CCBS成功", "二者均失败响应码一致", "二者均失败响应码不一致",
                "二者均成功", "响应码忽略", "交易成功率", "接口比对通过率"};
        writeDetailHeader(sheet, headers, styles);
        List<InterfaceSummaryRow> rows = interfaceSummaryRows(batchId);
        int rowIndex = 1;
        for (InterfaceSummaryRow row : rows) {
            Row excelRow = sheet.createRow(rowIndex++);
            CellStyle rowStyle = styles.rowStyle(rowIndex - 1);
            cell(excelRow, 0, row.batchId(), rowStyle);
            cell(excelRow, 1, row.tranCode(), rowStyle);
            cell(excelRow, 2, row.serviceCode(), rowStyle);
            cell(excelRow, 3, row.tranName(), rowStyle);
            cell(excelRow, 4, row.owner(), rowStyle);
            cell(excelRow, 5, row.internalOwner(), rowStyle);
            cell(excelRow, 6, row.moduleName(), rowStyle);
            numericCell(excelRow, 7, row.sentTransactionCount(), rowStyle);
            numericCell(excelRow, 8, row.compResult1Count(), rowStyle);
            numericCell(excelRow, 9, row.compResult2Count(), rowStyle);
            numericCell(excelRow, 10, row.compResult3Count(), rowStyle);
            numericCell(excelRow, 11, row.compResult8Count(), rowStyle);
            numericCell(excelRow, 12, row.compResult4Count(), rowStyle);
            numericCell(excelRow, 13, row.compResult5Count(), rowStyle);
            percentCell(excelRow, 14, row.successRate(), styles.percentStyle(rowIndex - 1));
            percentCell(excelRow, 15, row.comparisonPassRate(), styles.percentStyle(rowIndex - 1));
        }
        writeInterfaceSummaryTotalRow(sheet.createRow(rowIndex), rows, styles);
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headers.length - 1));
        sheet.createFreezePane(0, 1);
        for (int i = 0; i < headers.length; i++) {
            sheet.setColumnWidth(i, (i == 0 || i == 2 || i == 3 || i == 6) ? 5600 : 3600);
        }
        log.info("接口比对明细Sheet写入完成，batchId={}, rowCount={}, elapsedMs={}",
                batchId, rows.size(), elapsedMs(started));
    }

    private void writeInterfaceSummaryTotalRow(Row excelRow, List<InterfaceSummaryRow> rows, Styles styles) {
        CellStyle rowStyle = styles.summaryTotalStyle(true);
        long total = rows.stream().mapToLong(InterfaceSummaryRow::sentTransactionCount).sum();
        long one = rows.stream().mapToLong(InterfaceSummaryRow::compResult1Count).sum();
        long two = rows.stream().mapToLong(InterfaceSummaryRow::compResult2Count).sum();
        long three = rows.stream().mapToLong(InterfaceSummaryRow::compResult3Count).sum();
        long four = rows.stream().mapToLong(InterfaceSummaryRow::compResult4Count).sum();
        long eight = rows.stream().mapToLong(InterfaceSummaryRow::compResult8Count).sum();
        long five = rows.stream().mapToLong(InterfaceSummaryRow::compResult5Count).sum();
        long fieldPass = rows.stream().mapToLong(InterfaceSummaryRow::fieldPassTransactionCount).sum();
        long effectiveTotal = Math.max(0, total - five);
        cell(excelRow, 0, "", rowStyle);
        cell(excelRow, 1, "", rowStyle);
        cell(excelRow, 2, "合计", rowStyle);
        cell(excelRow, 3, "", rowStyle);
        cell(excelRow, 4, "", rowStyle);
        cell(excelRow, 5, "", rowStyle);
        cell(excelRow, 6, "", rowStyle);
        numericCell(excelRow, 7, total, rowStyle);
        numericCell(excelRow, 8, one, rowStyle);
        numericCell(excelRow, 9, two, rowStyle);
        numericCell(excelRow, 10, three, rowStyle);
        numericCell(excelRow, 11, eight, rowStyle);
        numericCell(excelRow, 12, four, rowStyle);
        numericCell(excelRow, 13, five, rowStyle);
        percentCell(excelRow, 14, rate(three + four, effectiveTotal), styles.summaryTotalPercentStyle(true));
        percentCell(excelRow, 15, rate(fieldPass + three, effectiveTotal), styles.summaryTotalPercentStyle(true));
    }

    private List<InterfaceSummaryRow> interfaceSummaryRows(String batchId) {
        return jdbc.query("""
                select batch_id, tran_code, service_code, tran_name, owner, internal_owner, module_name,
                       sent_transaction_count, comp_result_1_count, comp_result_2_count, comp_result_3_count,
                       comp_result_4_count, comp_result_8_count, comp_result_5_count, field_pass_transaction_count,
                       success_rate, comparison_pass_rate
                  from ana_report_export_interface_summary
                 where batch_id = :batchId
                 order by service_code
                """, params(batchId), (rs, rowNum) -> new InterfaceSummaryRow(
                rs.getString("batch_id"), rs.getString("tran_code"), rs.getString("service_code"),
                rs.getString("tran_name"), rs.getString("owner"), rs.getString("internal_owner"),
                rs.getString("module_name"), rs.getLong("sent_transaction_count"),
                rs.getLong("comp_result_1_count"), rs.getLong("comp_result_2_count"),
                rs.getLong("comp_result_3_count"), rs.getLong("comp_result_4_count"),
                rs.getLong("comp_result_8_count"), rs.getLong("comp_result_5_count"),
                rs.getLong("field_pass_transaction_count"), rs.getBigDecimal("success_rate"),
                rs.getBigDecimal("comparison_pass_rate")));
    }

    private void writeModule(SXSSFWorkbook book, String batchId, String module, boolean rawFieldValues, Styles styles) {
        long started = System.nanoTime();
        SXSSFSheet sheet = book.createSheet(uniqueSheetName(book, module));
        writeDetailHeader(sheet, DAILY_DETAIL_HEADERS, styles);
        int[] row = {1};
        int tranRows = streamDetails(sheet, batchId, module, "ana_tran_diff_tracking_export", row, rawFieldValues, true, styles);
        int fieldRows = streamDetails(sheet, batchId, module, "ana_field_diff_tracking_export", row, rawFieldValues, true, styles);
        finishDetailSheet(sheet, DAILY_DETAIL_HEADERS.length);
        log.info("{}明细Sheet写入完成，batchId={}, module={}, tranRows={}, fieldRows={}, elapsedMs={}",
                rawFieldValues ? "未脱敏日报" : "日报", batchId, module, tranRows, fieldRows, elapsedMs(started));
    }

    private int writeFullIssueList(SXSSFWorkbook book, String batchId, Styles styles) {
        SXSSFSheet sheet = book.createSheet("全量问题清单");
        writeDetailHeader(sheet, BASE_DETAIL_HEADERS, styles);
        int[] row = {1};
        streamAllDetails(sheet, batchId, row, styles);
        finishDetailSheet(sheet, BASE_DETAIL_HEADERS.length);
        return row[0] - 1;
    }

    private void writeDetailHeader(SXSSFSheet sheet, String[] headers, Styles styles) {
        Row header = sheet.createRow(0);
        header.setHeightInPoints(24f);
        for (int i = 0; i < headers.length; i++) {
            cell(header, i, headers[i], styles.detailHeader);
        }
    }

    private void finishDetailSheet(SXSSFSheet sheet, int headerCount) {
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headerCount - 1));
        sheet.createFreezePane(0, 1);
        for (int i = 0; i < headerCount; i++) {
            sheet.setColumnWidth(i, i == 8 || i == 11 || i == 12 ? 9000 : 3600);
        }
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

        for (int i = 0; i < 9; i++) {
            sheet.setColumnWidth(i, i == 8 ? 7600 : 4200);
        }
        sheet.createFreezePane(0, 5);
        log.info("{}处理延迟分布Sheet写入完成，batchId={}, baselineBatchId={}, dimension={}, currentIssueCount={}, baselineIssueCount={}, rowCount={}, currentReportDate={}, delayGraceDays={}, elapsedMs={}",
                weekly ? "周报" : "日报", batchId, baselineBatchId, dimension, currentIssues.size(), baselineIssues.size(),
                rows.size(), currentReportDate, delayGraceDays, elapsedMs(started));
    }

    private int writeDistributionTable(SXSSFSheet sheet, int headerRowIndex, String dimension, String[] bucketHeaders,
                                       Map<String, DelayDistributionRow> rows, boolean delay, Styles styles) {
        Row header = sheet.createRow(headerRowIndex);
        String[] fixedHeaders = {dimension, "交易数量（覆盖交易数）", "未解决问题数量"};
        for (int i = 0; i < fixedHeaders.length; i++) {
            cell(header, i, fixedHeaders[i], styles.detailHeader);
        }
        for (int i = 0; i < bucketHeaders.length; i++) {
            cell(header, 3 + i, bucketHeaders[i], styles.detailHeader);
        }
        cell(header, 8, "统计口径", styles.detailHeader);

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
        cell(row, 2, text(data.unresolvedIssueCount()), style);
        long[] buckets = delay ? data.delayBuckets() : data.repeatBuckets();
        for (int i = 0; i < buckets.length; i++) {
            cell(row, 3 + i, text(buckets[i]), style);
        }
        cell(row, 8, delay ? "本批次重复问题：当前批次日期 - 首次出现日期（自然日）" : "本批次重复问题：历史出现次数 + 1", style);
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
        Map<String, Set<String>> baselineKeysByDimension = issueKeysByDimension(baselineIssues);
        List<IssueSnapshot> unresolvedIssues = currentIssues.stream()
                .filter(issue -> issue.issueKey() != null
                        && baselineKeysByDimension.getOrDefault(issue.dimension(), Set.of()).contains(issue.issueKey()))
                .toList();
        unresolvedIssues.stream()
                .map(IssueSnapshot::dimension)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .forEach(dimension -> result.put(dimension, new DelayDistributionRow(dimension)));
        for (IssueSnapshot issue : unresolvedIssues) {
            DelayDistributionRow row = result.computeIfAbsent(issue.dimension(), DelayDistributionRow::new);
            row.addIssue(issue, currentReportDate, delayGraceDays);
        }
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

    private int streamDetails(SXSSFSheet sheet, String batchId, String module, String table, int[] row,
                              boolean rawFieldValues, boolean dailyDetail, Styles styles) {
        int startRow = row[0];
        String sql = "select detail.module_name,detail.row_no,detail.source_batch_id,detail.tran_code,detail.tran_name,detail.problem_level,detail.registration_date,"
                + "detail.field_name,detail.problem_description,detail.transaction_owner,detail.problem_type,detail.preliminary_analysis,detail.final_solution,"
                + "detail.resolution_date,detail.coordination_required,detail.resolver,detail.tran_seq_no,"
                + (dailyDetail ? "(select request.global_seq_no from msg_flow_log_request request "
                + "where request.trans_id = right(detail.tran_seq_no, 26) "
                + "order by request.txn_time asc nulls last, request.source_ip asc limit 1) as global_seq_no," : "")
                + "detail.defect_fix_date,"
                + "detail.affected_tran_count,detail.issue_id,detail.issue_key,detail.historical_occurrence_count,detail.first_seen_date,detail.previous_seen_date"
                + (rawFieldValues && "ana_field_diff_tracking_export".equals(table) ? ",detail.orig_field_value,detail.dest_field_value" : "")
                + " from " + table + " detail"
                + " where detail.source_batch_id=? and detail.module_name=? order by detail.row_no";
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
            boolean rawFieldRow = rawFieldValues && "ana_field_diff_tracking_export".equals(table);
            writeDetailRowCells(r, rs, rawFieldRow, dailyDetail, rowStyle);
        });
        return row[0] - startRow;
    }

    private void streamAllDetails(SXSSFSheet sheet, String batchId, int[] row, Styles styles) {
        String sql = """
                select module_name,row_no,source_batch_id,tran_code,tran_name,problem_level,registration_date,
                       field_name,problem_description,transaction_owner,problem_type,preliminary_analysis,final_solution,
                       resolution_date,coordination_required,resolver,tran_seq_no,defect_fix_date,
                       affected_tran_count,issue_id,issue_key,historical_occurrence_count,first_seen_date,previous_seen_date
                  from ana_tran_diff_tracking_export
                 where source_batch_id=?
                union all
                select module_name,row_no,source_batch_id,tran_code,tran_name,problem_level,registration_date,
                       field_name,problem_description,transaction_owner,problem_type,preliminary_analysis,final_solution,
                       resolution_date,coordination_required,resolver,tran_seq_no,defect_fix_date,
                       affected_tran_count,issue_id,issue_key,historical_occurrence_count,first_seen_date,previous_seen_date
                  from ana_field_diff_tracking_export
                 where source_batch_id=?
                 order by row_no, module_name
                """;
        jdbc.getJdbcTemplate().query((PreparedStatementCreator) connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setFetchSize(500);
            statement.setString(1, batchId);
            statement.setString(2, batchId);
            return statement;
        }, (RowCallbackHandler) rs -> writeDetailRow(sheet, row, rs, false, styles));
    }

    private void writeDetailRow(SXSSFSheet sheet, int[] row, ResultSet rs, boolean rawFieldRow, Styles styles)
            throws java.sql.SQLException {
        int dataOrdinal = row[0];
        Row r = sheet.createRow(row[0]++);
        CellStyle rowStyle = styles.rowStyle(dataOrdinal);
        writeDetailRowCells(r, rs, rawFieldRow, false, rowStyle);
    }

    private void writeDetailRowCells(Row row, ResultSet rs, boolean rawFieldRow, boolean dailyDetail, CellStyle rowStyle)
            throws java.sql.SQLException {
        String description = rawFieldRow
                ? rawFieldDescription(rs.getString("orig_field_value"), rs.getString("dest_field_value"))
                : text(rs.getObject("problem_description"));
        List<String> values = new ArrayList<>(dailyDetail ? DAILY_DETAIL_HEADERS.length : BASE_DETAIL_HEADERS.length);
        values.add(text(rs.getObject("module_name")));
        values.add(text(rs.getObject("row_no")));
        values.add(text(rs.getObject("source_batch_id")));
        values.add(text(rs.getObject("tran_code")));
        values.add(text(rs.getObject("tran_name")));
        values.add(text(rs.getObject("problem_level")));
        values.add(text(rs.getObject("registration_date")));
        values.add(text(rs.getObject("field_name")));
        values.add(description);
        values.add(text(rs.getObject("transaction_owner")));
        values.add(text(rs.getObject("problem_type")));
        values.add(text(rs.getObject("preliminary_analysis")));
        values.add(text(rs.getObject("final_solution")));
        values.add(text(rs.getObject("resolution_date")));
        values.add(text(rs.getObject("coordination_required")));
        values.add(text(rs.getObject("resolver")));
        values.add(text(rs.getObject("tran_seq_no")));
        if (dailyDetail) {
            values.add(text(rs.getObject("global_seq_no")));
        }
        values.add(text(rs.getObject("defect_fix_date")));
        values.add("");
        values.add(text(rs.getObject("affected_tran_count")));
        values.add(text(rs.getObject("issue_id")));
        values.add(text(rs.getObject("issue_key")));
        values.add(text(rs.getObject("historical_occurrence_count")));
        values.add(text(rs.getObject("first_seen_date")));
        values.add(text(rs.getObject("previous_seen_date")));
        for (int i = 0; i < values.size(); i++) {
            cell(row, i, values.get(i), rowStyle);
        }
    }

    private static String rawFieldDescription(String origFieldValue, String destFieldValue) {
        return "528字段值：" + text(origFieldValue) + "；CCBS字段值：" + text(destFieldValue);
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

    private static void numericCell(Row row, int column, long value, CellStyle style) {
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
        BigDecimal scaled = (value == null ? BigDecimal.ZERO : value).setScale(4, RoundingMode.HALF_UP);
        cell.setCellValue(scaled.doubleValue());
        cell.setCellStyle(style);
    }

    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP);
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
            long compResult5Count,
            BigDecimal successRate,
            long fieldPassTransactionCount,
            BigDecimal comparisonPassRate,
            long issueTotalCount,
            long duplicateIssueCount
    ) {
    }

    private record InterfaceSummaryRow(
            String batchId,
            String tranCode,
            String serviceCode,
            String tranName,
            String owner,
            String internalOwner,
            String moduleName,
            long sentTransactionCount,
            long compResult1Count,
            long compResult2Count,
            long compResult3Count,
            long compResult4Count,
            long compResult8Count,
            long compResult5Count,
            long fieldPassTransactionCount,
            BigDecimal successRate,
            BigDecimal comparisonPassRate
    ) {
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
        private long unresolvedIssueCount;

        private DelayDistributionRow(String dimension) {
            this.dimension = dimension;
        }

        private void addIssue(IssueSnapshot issue, LocalDate currentReportDate, int delayGraceDays) {
            unresolvedIssueCount++;
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
            unresolvedIssueCount += other.unresolvedIssueCount;
            for (int i = 0; i < 5; i++) {
                delayBuckets[i] += other.delayBuckets[i];
                repeatBuckets[i] += other.repeatBuckets[i];
            }
        }

        private String dimension() {
            return dimension;
        }

        private long transactionCount() {
            return tranCodes.size();
        }

        private long unresolvedIssueCount() {
            return unresolvedIssueCount;
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
            long compResult5Count,
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
                    rows.stream().mapToLong(SummaryRow::compResult5Count).sum(),
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
        private final CellStyle summaryFormula;

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
            summaryFormula = formulaStyle(book);
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

        private CellStyle summaryFormulaStyle() {
            return summaryFormula;
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

        private static CellStyle formulaStyle(SXSSFWorkbook book) {
            CellStyle value = book.createCellStyle();
            Font font = book.createFont();
            font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            font.setFontHeightInPoints((short) 9);
            value.setFont(font);
            value.setWrapText(true);
            value.setAlignment(HorizontalAlignment.LEFT);
            value.setVerticalAlignment(VerticalAlignment.CENTER);
            value.setBorderBottom(BorderStyle.THIN);
            value.setBorderTop(BorderStyle.THIN);
            value.setBorderLeft(BorderStyle.THIN);
            value.setBorderRight(BorderStyle.THIN);
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
