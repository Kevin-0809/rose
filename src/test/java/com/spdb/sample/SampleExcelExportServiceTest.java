package com.spdb.sample;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class SampleExcelExportServiceTest {

    @Test
    void exportsSampleDetailsWithSeparateFieldNameColumnsAndReadableLayout() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleDetailRow row = new SampleDetailRow(
                1L, 2L, "B20260609", "FIELD_DIFF", 1, "S001&bizjson",
                "S001", "bizjson", "A001", "4", "acctNo", "soapAcctNo",
                "account.no", "账号", "528001", null, "CCBS001", null, "SEQ001", "张三", 12L, "字段取值不一致"
        );

        byte[] bytes = service.exportDetails(List.of(row));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("采样明细");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("采样明细导出");
            Row header = sheet.getRow(1);
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("SOP字段名");
            assertThat(header.getCell(5).getStringCellValue()).isEqualTo("SOAP字段名");
            assertThat(header.getCell(6).getStringCellValue()).isEqualTo("BizJSON字段名");
            assertThat(header.getCell(8).getStringCellValue()).isEqualTo("528响应码");
            assertThat(header.getCell(9).getStringCellValue()).isEqualTo("528响应描述");
            assertThat(header.getCell(10).getStringCellValue()).isEqualTo("CCBS响应码");
            assertThat(header.getCell(11).getStringCellValue()).isEqualTo("CCBS响应描述");
            assertThat(sheet.getRow(2).getCell(4).getStringCellValue()).isEqualTo("acctNo");
            assertThat(sheet.getRow(2).getCell(5).getStringCellValue()).isEqualTo("soapAcctNo");
            assertThat(sheet.getRow(2).getCell(6).getStringCellValue()).isEqualTo("account.no");
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getColumnWidth(6)).isGreaterThanOrEqualTo(18 * 256);
        }
    }

    @Test
    void streamsSampleDetailsAsReadableWorkbookWithoutPrebuiltList() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleQueryService queryService = mock(SampleQueryService.class);
        SampleDetailRow row = new SampleDetailRow(
                1L, 2L, "B20260609", "FIELD_DIFF", 1, "S001&bizjson",
                "S001", "bizjson", "A001", "4", "acctNo", "soapAcctNo",
                "account.no", "账号", "528001", null, "CCBS001", null, "SEQ001", "张三", 12L, "字段取值不一致"
        );
        doAnswer(invocation -> {
            SampleDetailConsumer consumer = invocation.getArgument(1);
            consumer.accept(row);
            return null;
        }).when(queryService).streamDetails(any(), any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamDetails(queryService, new SampleSearchCriteria(null, null, null, null, null, null, null, null), out);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("采样明细");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("采样明细导出");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("SOP字段名");
            assertThat(sheet.getRow(2).getCell(4).getStringCellValue()).isEqualTo("acctNo");
            assertThat(sheet.getRow(2).getCell(12).getStringCellValue()).isEqualTo("SEQ001");
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
        }
    }

    @Test
    void exportsReturnCodeDetailsWithDescriptionsAndBlankFieldMapping() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleDetailRow row = new SampleDetailRow(
                1L, 2L, "B20260609", "RETURN_CODE", 1, "S001&bizjson",
                "S001", "bizjson", "A001", "8", null, null,
                null, null, "E0001", "528余额不足", "C0002", "CCBS余额不足",
                "SEQ001", "张三", 12L, "响应码不一致"
        );

        byte[] bytes = service.exportDetails(List.of(row));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("采样明细");
            assertThat(sheet.getRow(2).getCell(4).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(2).getCell(8).getStringCellValue()).isEqualTo("E0001");
            assertThat(sheet.getRow(2).getCell(9).getStringCellValue()).isEqualTo("528余额不足");
            assertThat(sheet.getRow(2).getCell(10).getStringCellValue()).isEqualTo("C0002");
            assertThat(sheet.getRow(2).getCell(11).getStringCellValue()).isEqualTo("CCBS余额不足");
            assertThat(sheet.getRow(2).getCell(12).getStringCellValue()).isEqualTo("SEQ001");
        }
    }

    @Test
    void exportsSampleGroupsWithStyledTitleAndCoreColumns() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleGroupRow row = new SampleGroupRow(
                10L, "B20260609", "RETURN_CODE", "S002&bizjson", "S002",
                "bizjson", "A002", "4", "returnCode", "returnCode", "returnCode",
                "返回码", "李四", 88L, 50, "响应码不一致"
        );

        byte[] bytes = service.exportGroups(List.of(row));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("采样分组");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("采样分组导出");
            Row header = sheet.getRow(1);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("类型");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("SOP字段名");
            assertThat(header.getCell(5).getStringCellValue()).isEqualTo("SOAP字段名");
            assertThat(header.getCell(6).getStringCellValue()).isEqualTo("BizJSON字段名");
            assertThat(sheet.getRow(2).getCell(7).getStringCellValue()).isEqualTo("返回码");
            assertThat(sheet.getRow(2).getCell(8).getStringCellValue()).isEqualTo("李四");
            assertThat(sheet.getColumnWidth(2)).isGreaterThanOrEqualTo(14 * 256);
        }
    }
}
