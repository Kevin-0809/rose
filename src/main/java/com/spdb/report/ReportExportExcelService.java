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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReportExportExcelService {
    private static final String[] DETAIL_HEADERS = {"领域", "序号", "批次", "交易码", "交易名称", "问题级别", "登记日期",
            "字段名", "问题描述", "交易负责人", "问题类型", "初步问题分析", "最终处理方案", "解决日期", "需协同组",
            "解决人员", "流水号", "缺陷修复日期", "备注", "历史出现次数", "首次出现日期", "上次出现日期"};
    private final NamedParameterJdbcTemplate jdbc;

    public ReportExportExcelService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void stream(String batchId, OutputStream output) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(200);
        workbook.setCompressTempFiles(true);
        try {
            Styles styles = new Styles(workbook);
            writeSummary(workbook, batchId, styles);
            for (String module : modules(batchId)) {
                writeModule(workbook, batchId, module, styles);
            }
            workbook.write(output);
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }

    private void writeSummary(SXSSFWorkbook book, String batchId, Styles styles) {
        SXSSFSheet sheet = book.createSheet("汇总信息");
        List<SummaryRow> currentRows = summaryRows(batchId);
        String previousBatchId = previousSucceededBatchId(batchId);
        List<SummaryRow> previousRows = previousBatchId == null ? List.of() : summaryRows(previousBatchId);

        int nextRow = writeSummarySection(sheet, 0, "上一批次", previousRows, Map.of(), false, styles);
        writeSummarySection(sheet, nextRow + 2, "本批次", currentRows, issueTotalsByModule(previousRows), true, styles);

        for (int i = 0; i < 21; i++) {
            sheet.setColumnWidth(i, i == 1 ? 4600 : 3600);
        }
        sheet.createFreezePane(0, 2);
    }

    private int writeSummarySection(SXSSFSheet sheet, int startRow, String title, List<SummaryRow> rows,
                                    Map<String, Long> previousIssueTotals, boolean current, Styles styles) {
        int lastColumn = current ? 20 : 17;
        Row titleRow = sheet.createRow(startRow);
        titleRow.setHeightInPoints(24f);
        mergedCell(sheet, startRow, startRow, 0, lastColumn, title, styles.summaryHeader);

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
        mainHeader.setHeightInPoints(22f);
        subHeader.setHeightInPoints(22f);

        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 0, 0, "批次", styles.detailHeader);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 1, 1, "领域", styles.detailHeader);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 2, 2, "覆盖528接口", styles.detailHeader);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 3, 3, "发送交易量", styles.detailHeader);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex, 4, 8, "交易状态分类统计", styles.detailHeader);

        String[] statusHeaders = {"528成功/CCBS失败", "528失败/CCBS成功", "二者均失败响应码一致",
                "二者均失败响应码不一致", "二者均成功"};
        for (int i = 0; i < statusHeaders.length; i++) {
            cell(subHeader, 4 + i, statusHeaders[i], styles.detailHeader);
        }

        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 9, 9, "成功率", styles.detailHeader);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 10, 10, "比对通过率", styles.detailHeader);
        mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 11, 11, "问题总数", styles.detailHeader);

        if (current) {
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 12, 12, "重复问题", styles.detailHeader);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 13, 13, "重复率", styles.detailHeader);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 14, 14, "上轮问题解决率", styles.detailHeader);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex, 15, 19,
                    "已解决问题分类统计（待验证）", styles.detailHeader);
            writeSolvedIssueSubHeaders(subHeader, 15, styles);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 20, 20, "问题解决进度", styles.detailHeader);
        } else {
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex, 12, 16,
                    "已解决问题分类统计（待验证）", styles.detailHeader);
            writeSolvedIssueSubHeaders(subHeader, 12, styles);
            mergedCell(sheet, mainHeaderRowIndex, mainHeaderRowIndex + 1, 17, 17, "问题解决进度", styles.detailHeader);
        }
    }

    private void writeSolvedIssueSubHeaders(Row subHeader, int firstColumn, Styles styles) {
        String[] solvedHeaders = {"迁移问题", "防腐问题", "功能问题", "新核心下线", "其他问题"};
        for (int i = 0; i < solvedHeaders.length; i++) {
            cell(subHeader, firstColumn + i, solvedHeaders[i], styles.detailHeader);
        }
    }

    private void writeSummaryDataRow(Row excelRow, SummaryRow row, Long previousIssueTotal,
                                     boolean current, int dataOrdinal, Styles styles) {
        CellStyle rowStyle = styles.rowStyle(dataOrdinal);
        cell(excelRow, 0, row.batchId(), rowStyle);
        cell(excelRow, 1, row.moduleName(), rowStyle);
        cell(excelRow, 2, text(row.covered528InterfaceCount()), rowStyle);
        cell(excelRow, 3, text(row.sentTransactionCount()), rowStyle);
        cell(excelRow, 4, text(row.compResult1Count()), rowStyle);
        cell(excelRow, 5, text(row.compResult2Count()), rowStyle);
        cell(excelRow, 6, text(row.compResult3Count()), rowStyle);
        cell(excelRow, 7, text(row.compResult8Count()), rowStyle);
        cell(excelRow, 8, text(row.compResult4Count()), rowStyle);
        percentCell(excelRow, 9, row.successRate(), styles.percentStyle(dataOrdinal));
        percentCell(excelRow, 10, row.comparisonPassRate(), styles.percentStyle(dataOrdinal));
        cell(excelRow, 11, text(row.issueTotalCount()), rowStyle);
        if (current) {
            cell(excelRow, 12, text(row.duplicateIssueCount()), rowStyle);
            percentCell(excelRow, 13, rate(row.duplicateIssueCount(), row.issueTotalCount()), styles.percentStyle(dataOrdinal));
            percentCell(excelRow, 14, previousResolutionRate(previousIssueTotal, row.duplicateIssueCount()), styles.percentStyle(dataOrdinal));
            blankCells(excelRow, 15, 20, rowStyle);
        } else {
            blankCells(excelRow, 12, 17, rowStyle);
        }
    }

    private void writeSummaryTotalRow(Row excelRow, List<SummaryRow> rows, Map<String, Long> previousIssueTotals,
                                      boolean current, int dataOrdinal, Styles styles) {
        CellStyle rowStyle = styles.rowStyle(dataOrdinal);
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
                totals.sentTransactionCount()), styles.percentStyle(dataOrdinal));
        percentCell(excelRow, 10, rate(totals.fieldPassTransactionCount() + totals.compResult3Count(),
                totals.sentTransactionCount()), styles.percentStyle(dataOrdinal));
        cell(excelRow, 11, text(totals.issueTotalCount()), rowStyle);
        if (current) {
            long previousIssueTotal = previousIssueTotals.values().stream().mapToLong(Long::longValue).sum();
            cell(excelRow, 12, text(totals.duplicateIssueCount()), rowStyle);
            percentCell(excelRow, 13, rate(totals.duplicateIssueCount(), totals.issueTotalCount()),
                    styles.percentStyle(dataOrdinal));
            percentCell(excelRow, 14, previousResolutionRate(previousIssueTotal, totals.duplicateIssueCount()),
                    styles.percentStyle(dataOrdinal));
            blankCells(excelRow, 15, 20, rowStyle);
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

    private static Map<String, Long> issueTotalsByModule(List<SummaryRow> rows) {
        Map<String, Long> result = new HashMap<>();
        for (SummaryRow row : rows) {
            result.put(row.moduleName(), row.issueTotalCount());
        }
        return result;
    }

    private void writeModule(SXSSFWorkbook book, String batchId, String module, Styles styles) {
        SXSSFSheet sheet = book.createSheet(uniqueSheetName(book, module));
        Row header = sheet.createRow(0);
        header.setHeightInPoints(24f);
        for (int i = 0; i < DETAIL_HEADERS.length; i++) {
            cell(header, i, DETAIL_HEADERS[i], styles.detailHeader);
        }
        int[] row = {1};
        streamDetails(sheet, batchId, module, "ana_tran_diff_tracking_export", row, styles);
        streamDetails(sheet, batchId, module, "ana_field_diff_tracking_export", row, styles);
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, DETAIL_HEADERS.length - 1));
        sheet.createFreezePane(0, 1);
        for (int i = 0; i < DETAIL_HEADERS.length; i++) {
            sheet.setColumnWidth(i, i == 8 || i == 11 || i == 12 ? 9000 : 3600);
        }
    }

    private void streamDetails(SXSSFSheet sheet, String batchId, String module, String table, int[] row, Styles styles) {
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
        private final CellStyle summaryHeader;
        private final CellStyle detailHeader;
        private final CellStyle oddBody;
        private final CellStyle evenBody;
        private final CellStyle oddPercent;
        private final CellStyle evenPercent;

        private Styles(SXSSFWorkbook book) {
            summaryHeader = headerStyle(book, TITLE_NAVY);
            detailHeader = headerStyle(book, TABLE_TEAL);
            oddBody = style(book, STRIPE_BLUE);
            evenBody = style(book, WHITE);
            oddPercent = style(book, STRIPE_BLUE);
            evenPercent = style(book, WHITE);
            DataFormat format = book.createDataFormat();
            oddPercent.setDataFormat(format.getFormat("0.00%"));
            evenPercent.setDataFormat(format.getFormat("0.00%"));
        }

        private CellStyle rowStyle(int oneBasedDataRow) {
            return oneBasedDataRow % 2 == 1 ? oddBody : evenBody;
        }

        private CellStyle percentStyle(int oneBasedDataRow) {
            return oneBasedDataRow % 2 == 1 ? oddPercent : evenPercent;
        }

        private static CellStyle headerStyle(SXSSFWorkbook book, String color) {
            CellStyle value = style(book, color);
            Font font = book.createFont();
            font.setBold(true);
            font.setColor(IndexedColors.WHITE.getIndex());
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
