package com.spdb.replay;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayTransactionCatalogWorkbookParserTest {
    private static final String[] HEADERS = {
            "528交易码", "交易后缀", "528交易名称", "业务领域（沙箱）", "批次",
            "新核心交易码", "交易名称", "是否需要参与回放", "原服务场景码", "新服务场景码", "最近交易日期"
    };

    @Test
    void parsesElevenColumnsAndNormalizesValues() throws Exception {
        try (var workbook = workbook()) {
            var row = workbook.getSheetAt(0).createRow(1);
            values(row, "ABC", "01", "支付", "零售", "查询-批次1", "NC", "新支付", "是", "OS", "NS", "20260826");
            var second = workbook.getSheetAt(0).createRow(2);
            values(second, "XYZ", "", "查询", "对公", "动账", "", "", "否", "", "", "");

            List<ReplayTransactionCatalogForm> parsed = new ReplayTransactionCatalogWorkbookParser().parse(workbook);

            assertThat(parsed).extracting(ReplayTransactionCatalogForm::tranCode)
                    .containsExactly("ABC-01", "XYZ");
            assertThat(parsed.get(0).batchType()).isEqualTo("查询");
            assertThat(parsed.get(1).batchType()).isEqualTo("动账");
            assertThat(parsed.get(0).latestTransactionDate()).isEqualTo("20260826");
            assertThat(parsed.get(0).replayRequired()).isEqualTo("是");
            assertThat(parsed.get(1).replayRequired()).isEqualTo("否");
        }
    }

    @Test
    void rejectsEmptyRowsDuplicateCodesWrongHeaderInvalidDateAndReplayValue() throws Exception {
        try (var workbook = workbook()) {
            workbook.getSheetAt(0).createRow(1);
            var row = workbook.getSheetAt(0).createRow(2);
            values(row, "VALID", "", "n", "d", "查询", "", "", "", "", "", "");
            assertThat(new ReplayTransactionCatalogWorkbookParser().parse(workbook))
                    .extracting(ReplayTransactionCatalogForm::tranCode).containsExactly("VALID");
        }
        try (var workbook = workbook()) {
            var row = workbook.getSheetAt(0).createRow(1);
            values(row, "ABC", "", "n", "d", "查询", "", "", "是", "", "", "20260230");
            assertThatThrownBy(() -> new ReplayTransactionCatalogWorkbookParser().parse(workbook))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("第2行");
        }
        try (var workbook = workbook()) {
            workbook.getSheetAt(0).getRow(0).getCell(3).setCellValue("错误表头");
            assertThatThrownBy(() -> new ReplayTransactionCatalogWorkbookParser().parse(workbook))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("表头");
        }
        try (var workbook = workbook()) {
            var row = workbook.getSheetAt(0).createRow(1);
            values(row, "ABC", "", "n", "d", "查询", "", "", "maybe", "", "", "20260826");
            assertThatThrownBy(() -> new ReplayTransactionCatalogWorkbookParser().parse(workbook))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("第2行");
        }
        try (var workbook = workbook()) {
            var row = workbook.getSheetAt(0).createRow(1);
            values(row, "ABC", "", "n", "d", "查询", "", "", "是", "", "", "20260826");
            var duplicate = workbook.getSheetAt(0).createRow(2);
            values(duplicate, "ABC", "", "n2", "d", "查询", "", "", "否", "", "", "20260826");
            assertThatThrownBy(() -> new ReplayTransactionCatalogWorkbookParser().parse(workbook))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复");
        }
    }

    @Test
    void convertsNumericExcelDateAndRejectsNumericCodeAndOversizedText() throws Exception {
        try (var workbook = workbook()) {
            var row = workbook.getSheetAt(0).createRow(1);
            values(row, "DATE", "", "n", "d", "查询", "", "", "", "", "", "");
            row.getCell(10).setCellValue(Date.from(java.time.LocalDate.of(2026, 8, 26).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()));
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyyMMdd"));
            row.getCell(10).setCellStyle(style);
            assertThat(new ReplayTransactionCatalogWorkbookParser().parse(workbook).get(0).latestTransactionDate()).isEqualTo("20260826");
        }
        try (var workbook = workbook()) {
            var row = workbook.getSheetAt(0).createRow(1);
            values(row, "123", "", "n", "d", "查询", "", "", "", "", "", "");
            row.getCell(0).setCellValue(123);
            assertThatThrownBy(() -> new ReplayTransactionCatalogWorkbookParser().parse(workbook))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("第2行");
        }
        try (var workbook = workbook()) {
            var row = workbook.getSheetAt(0).createRow(1);
            values(row, "LONG", "", "x".repeat(201), "d", "查询", "", "", "", "", "", "");
            assertThatThrownBy(() -> new ReplayTransactionCatalogWorkbookParser().parse(workbook))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("第2行");
        }
    }

    private XSSFWorkbook workbook() {
        var workbook = new XSSFWorkbook();
        var sheet = workbook.createSheet("清单");
        var header = sheet.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) header.createCell(i).setCellValue(HEADERS[i]);
        return workbook;
    }

    private void values(org.apache.poi.ss.usermodel.Row row, String... values) {
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }
}
