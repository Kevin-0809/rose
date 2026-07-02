package com.spdb.config;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionListWorkbookParserTest {

    private final TransactionListWorkbookParser parser = new TransactionListWorkbookParser();

    @Test
    void parsesAntiCorruptionTransactionCodesAndOwnersFromRegistrationWorkbook() {
        try (Workbook workbook = workbook()) {
            List<TransactionListEntry> entries = parser.parse(workbook);

            assertThat(entries).extracting(TransactionListEntry::tranCode)
                    .containsExactly("A666", "C025");
            assertThat(entries).extracting(TransactionListEntry::moduleName)
                    .containsExactly("存款", "贷款");
            assertThat(entries).extracting(TransactionListEntry::owner)
                    .containsExactly("张三/李四", "王五");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void skipsBlankCodesAndDeduplicatesByFirstCodeOccurrence() {
        try (Workbook workbook = workbook()) {
            Sheet sheet = workbook.getSheetAt(0);
            row(sheet, 4, "理财", "", "", "A666", "", "", "", "赵六", "钱七");

            List<TransactionListEntry> entries = parser.parse(workbook);

            assertThat(entries).extracting(TransactionListEntry::tranCode)
                    .containsExactly("A666", "C025");
            assertThat(entries.get(0).moduleName()).isEqualTo("存款");
            assertThat(entries.get(0).owner()).isEqualTo("张三/李四");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    static Workbook workbook() {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("接口映射表");
        row(sheet, 0, "浦发银行 涉密计算机禁止存储");
        row(sheet, 1, "业务领域", "新核心交易码", "交易名称", "防腐528交易码", "528交易名称",
                "返回报文是否存在数组", "排序字段", "开发负责人", "业务负责人");
        row(sheet, 2, "存款", "C025", "对公明细查询交易", "A666", "对公余额发生明细查询",
                "是", "HostSeqNo", "张三", "李四");
        row(sheet, 3, "贷款", "C026", "贷款查询", "C025", "贷款查询",
                "否", "", "王五", "");
        return workbook;
    }

    private static void row(Sheet sheet, int rowIndex, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }
}
