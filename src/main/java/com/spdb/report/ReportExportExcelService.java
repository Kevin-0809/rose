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
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ReportExportExcelService {
    private static final String[] DETAIL_HEADERS = {"领域", "序号", "批次", "交易码", "交易名称", "问题级别", "登记日期",
            "字段名", "问题描述", "交易负责人", "问题类型", "初步问题分析", "最终处理方案", "解决日期", "需协同组",
            "解决人员", "流水号", "备注", "缺陷修复日期", "历史出现次数", "首次出现日期", "上次出现日期"};
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
        Row first = sheet.createRow(0);
        Row second = sheet.createRow(1);
        first.setHeightInPoints(24f);
        second.setHeightInPoints(22f);
        String[] fixed = {"批次", "领域", "覆盖528接口", "发送交易量"};
        for (int i = 0; i < fixed.length; i++) {
            mergedCell(sheet, 0, 1, i, i, fixed[i], styles.summaryHeader);
        }
        mergedCell(sheet, 0, 0, 4, 7, "发送统计", styles.summaryHeader);
        String[] stats = {"528成功/CCBS失败", "528失败/CCBS成功", "二者均失败", "二者均成功"};
        for (int i = 0; i < stats.length; i++) {
            cell(second, i + 4, stats[i], styles.detailHeader);
        }
        mergedCell(sheet, 0, 1, 8, 8, "成功率", styles.summaryHeader);
        mergedCell(sheet, 0, 1, 9, 9, "差异字段数", styles.summaryHeader);
        int[] row = {2};
        jdbc.query("""
                select batch_id, module_name, covered_528_interface_count, sent_transaction_count,
                       comp_result_1_count, comp_result_2_count, comp_result_3_count, comp_result_4_count,
                       success_rate, diff_528_field_count
                  from ana_report_export_summary
                 where batch_id=:batchId
                 order by module_name
                """, params(batchId), rs -> {
            int dataOrdinal = row[0] - 1;
            Row r = sheet.createRow(row[0]++);
            CellStyle rowStyle = styles.rowStyle(dataOrdinal);
            for (int i = 0; i < 8; i++) {
                cell(r, i, text(rs.getObject(i + 1)), rowStyle);
            }
            percentCell(r, 8, rs.getBigDecimal("success_rate"), styles.percentStyle(row[0]));
            cell(r, 9, text(rs.getObject("diff_528_field_count")), rowStyle);
        });
        for (int i = 0; i < 10; i++) {
            sheet.setColumnWidth(i, i == 1 ? 4600 : 3600);
        }
        sheet.createFreezePane(0, 2);
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
                cell(r, i, i == 17 ? "" : text(rs.getObject(i < 17 ? i + 1 : i)), rowStyle);
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
