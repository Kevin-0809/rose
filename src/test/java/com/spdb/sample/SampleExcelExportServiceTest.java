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
                1L, 2L, "B20260609", "20260609", "FIELD_DIFF", 1, "CONFIGURED", "S001&bizjson",
                "S001", "bizjson", "A001", "4", "HUOBDH,FAB251", "CurrencyId,FcyCollCrspBnkLkg",
                "CurrencyId,FcyCollCrspBnkLkg", "币种,联动信息", "SEQ001", "张三", 12L, 2,
                null, null, null, null, "字段取值不一致", "tss_field_comp", "SEQ001"
        );

        byte[] bytes = service.exportDetails(List.of(row));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("采样明细");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("采样明细导出");
            Row header = sheet.getRow(1);
            assertThat(header.getCell(6).getStringCellValue()).isEqualTo("流水号");
            assertThat(header.getCell(7).getStringCellValue()).isEqualTo("SOP字段名");
            assertThat(header.getCell(8).getStringCellValue()).isEqualTo("SOAP字段名");
            assertThat(header.getCell(9).getStringCellValue()).isEqualTo("BizJSON字段名");
            assertThat(header.getCell(10).getStringCellValue()).isEqualTo("字段中文名");
            assertThat(header.getCell(11).getStringCellValue()).isEqualTo("字段数");
            assertThat(header.getCell(12).getStringCellValue()).isEqualTo("528响应码");
            assertThat(header.getCell(13).getStringCellValue()).isEqualTo("528响应描述");
            assertThat(header.getCell(14).getStringCellValue()).isEqualTo("CCBS响应码");
            assertThat(header.getCell(15).getStringCellValue()).isEqualTo("CCBS响应描述");
            assertThat(sheet.getRow(2).getCell(6).getStringCellValue()).isEqualTo("SEQ001");
            assertThat(sheet.getRow(2).getCell(7).getStringCellValue()).isEqualTo("HUOBDH,FAB251");
            assertThat(sheet.getRow(2).getCell(10).getStringCellValue()).isEqualTo("币种,联动信息");
            assertThat(sheet.getRow(2).getCell(11).getNumericCellValue()).isEqualTo(2);
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getColumnWidth(6)).isGreaterThanOrEqualTo(18 * 256);
        }
    }

    @Test
    void streamsSampleDetailsAsReadableWorkbookWithoutPrebuiltList() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleQueryService queryService = mock(SampleQueryService.class);
        SampleDetailRow row = new SampleDetailRow(
                1L, 2L, "B20260609", "20260609", "FIELD_DIFF", 1, "CONFIGURED", "S001&bizjson",
                "S001", "bizjson", "A001", "4", "HUOBDH", "CurrencyId",
                "CurrencyId", "币种", "SEQ001", "张三", 12L, 2,
                null, null, null, null, "字段取值不一致", "tss_field_comp", "SEQ001"
        );
        doAnswer(invocation -> {
            SampleDetailConsumer consumer = invocation.getArgument(1);
            consumer.accept(row);
            return null;
        }).when(queryService).streamDetails(any(), any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamDetails(queryService, new SampleSearchCriteria(null, null, null, null, null, null, null, null, null, null, null), out);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("采样明细");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("采样明细导出");
            assertThat(sheet.getRow(1).getCell(6).getStringCellValue()).isEqualTo("流水号");
            assertThat(sheet.getRow(2).getCell(6).getStringCellValue()).isEqualTo("SEQ001");
            assertThat(sheet.getRow(2).getCell(18).getStringCellValue()).isEqualTo("tss_field_comp");
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
        }
    }

    @Test
    void streamsFieldDiffExportAsOneSheetWithSampleAndFieldColumns() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleQueryService queryService = mock(SampleQueryService.class);
        SampleFieldDiffExportRow row = new SampleFieldDiffExportRow(
                1L, 1001L, 2L, "B20260609", "20260609", "FIELD_DIFF", 1,
                "CONFIGURED", "S001&bizjson", "S001", "bizjson", "A001", "4",
                "HUOBDH,FAB251", "CurrencyId,FcyCollCrspBnkLkg",
                "CurrencyId,FcyCollCrspBnkLkg", "币种,联动信息", "SEQ001", "张三",
                12L, 2, null, null, null, null, "字段取值不一致", "tss_field_comp",
                "SEQ001", "CurrencyId", "currency_id", "币种", "111", "222", "MAPPED", 1
        );
        doAnswer(invocation -> {
            SampleFieldDiffExportConsumer consumer = invocation.getArgument(1);
            consumer.accept(row);
            return null;
        }).when(queryService).streamFieldDiffExport(any(), any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamFieldDiffExport(queryService, new SampleSearchCriteria(null, null, null, null, null, null, null, null, null, null, null), out);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = workbook.getSheet("字段级差异明细");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("字段级差异合并导出");
            Row header = sheet.getRow(1);
            assertThat(header.getCell(6).getStringCellValue()).isEqualTo("流水号");
            assertThat(header.getCell(7).getStringCellValue()).isEqualTo("SOP字段名");
            assertThat(header.getCell(10).getStringCellValue()).isEqualTo("字段中文名");
            assertThat(header.getCell(11).getStringCellValue()).isEqualTo("字段数");
            assertThat(header.getCell(12).getStringCellValue()).isEqualTo("原字段名");
            assertThat(header.getCell(13).getStringCellValue()).isEqualTo("标准字段名");
            assertThat(header.getCell(15).getStringCellValue()).isEqualTo("528字段值");
            assertThat(header.getCell(16).getStringCellValue()).isEqualTo("CCBS字段值");
            assertThat(header.getCell(19).getStringCellValue()).isEqualTo("数量");
            assertThat(header.getCell(20).getStringCellValue()).isEqualTo("来源表");
            assertThat(header.getCell(21).getStringCellValue()).isEqualTo("责任人");
            assertThat(header.getLastCellNum()).isEqualTo((short) 22);
            assertThat(rowText(header)).doesNotContain("原因", "528响应码", "528响应描述", "CCBS响应码", "CCBS响应描述");
            assertThat(sheet.getRow(2).getCell(6).getStringCellValue()).isEqualTo("SEQ001");
            assertThat(sheet.getRow(2).getCell(7).getStringCellValue()).isEqualTo("HUOBDH,FAB251");
            assertThat(sheet.getRow(2).getCell(12).getStringCellValue()).isEqualTo("CurrencyId");
            assertThat(sheet.getRow(2).getCell(13).getStringCellValue()).isEqualTo("currency_id");
            assertThat(sheet.getRow(2).getCell(15).getStringCellValue()).isEqualTo("111");
            assertThat(sheet.getRow(2).getCell(16).getStringCellValue()).isEqualTo("222");
            assertThat(sheet.getRow(2).getCell(21).getStringCellValue()).isEqualTo("张三");
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
        }
    }

    private String rowText(Row row) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(row.getCell(i).getStringCellValue());
        }
        return text.toString();
    }

    @Test
    void exportsReturnCodeDetailsWithDescriptionsAndBlankFieldMapping() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleDetailRow row = new SampleDetailRow(
                1L, 2L, "B20260609", "20260609", "RETURN_CODE", 1, "CONFIGURED", "S001&bizjson",
                "S001", "bizjson", "A001", "8", null, null, null, null,
                "SEQ001", "张三", 12L, 0,
                "E0001", "528余额不足", "C0002", "CCBS余额不足",
                "响应码不一致", "tss_retcode_comp", "SEQ001"
        );

        byte[] bytes = service.exportDetails(List.of(row));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("采样明细");
            assertThat(sheet.getRow(2).getCell(12).getStringCellValue()).isEqualTo("E0001");
            assertThat(sheet.getRow(2).getCell(13).getStringCellValue()).isEqualTo("528余额不足");
            assertThat(sheet.getRow(2).getCell(14).getStringCellValue()).isEqualTo("C0002");
            assertThat(sheet.getRow(2).getCell(15).getStringCellValue()).isEqualTo("CCBS余额不足");
            assertThat(sheet.getRow(2).getCell(6).getStringCellValue()).isEqualTo("SEQ001");
        }
    }

    @Test
    void streamsTransactionDiffExportWithReturnCodeColumnsAndTransactionGrain() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleQueryService queryService = mock(SampleQueryService.class);
        SampleDetailRow row = new SampleDetailRow(
                1L, 2L, "B20260609", "20260609", "RETURN_CODE", 1, "CONFIGURED", "S001&bizjson",
                "S001", "bizjson", "A001", "8", null, null, null, null,
                "SEQ001", "张三", 12L, 0,
                "E0001", "528余额不足", "C0002", "CCBS余额不足",
                "响应码不一致", "tss_retcode_comp", "SEQ001"
        );
        doAnswer(invocation -> {
            SampleDetailConsumer consumer = invocation.getArgument(1);
            consumer.accept(row);
            return null;
        }).when(queryService).streamTransactionDiffExport(any(), any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamTransactionDiffExport(queryService, new SampleSearchCriteria(null, null, null, null, null, null, null, null, null, null, null), out);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("交易级差异");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("交易级差异导出");
            Row header = sheet.getRow(1);
            assertThat(rowText(header)).isEqualTo("业务日期,批次,交易码,服务码,报文类型,流水号,交易结果,528响应码,528响应描述,CCBS响应码,CCBS响应描述,责任人,数量");
            assertThat(header.getLastCellNum()).isEqualTo((short) 13);
            assertThat(rowText(header)).doesNotContain("配置状态", "SOP字段名", "SOAP字段名", "BizJSON字段名",
                    "字段中文名", "字段数", "来源表", "原因");
            assertThat(sheet.getRow(2).getCell(5).getStringCellValue()).isEqualTo("SEQ001");
            assertThat(sheet.getRow(2).getCell(6).getStringCellValue()).isEqualTo("8");
            assertThat(sheet.getRow(2).getCell(7).getStringCellValue()).isEqualTo("E0001");
            assertThat(sheet.getRow(2).getCell(8).getStringCellValue()).isEqualTo("528余额不足");
            assertThat(sheet.getRow(2).getCell(9).getStringCellValue()).isEqualTo("C0002");
            assertThat(sheet.getRow(2).getCell(10).getStringCellValue()).isEqualTo("CCBS余额不足");
            assertThat(sheet.getRow(2).getCell(12).getNumericCellValue()).isEqualTo(12);
        }
    }

    @Test
    void exportsSampleGroupsWithStyledTitleAndCoreColumns() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleGroupRow row = new SampleGroupRow(
                10L, "B20260609", "20260609", "RETURN_CODE", "CONFIGURED", "MAPPED",
                "returnCode:E1->E2", "HASH", "returnCode", "bizjson",
                "S002&bizjson", "S002", "bizjson", "A002", "4", "李四",
                88L, 88L, 0L, 50, "响应码不一致"
        );

        byte[] bytes = service.exportGroups(List.of(row));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("采样分组");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("采样分组导出");
            Row header = sheet.getRow(1);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("业务日期");
            assertThat(header.getCell(7).getStringCellValue()).isEqualTo("语义字段");
            assertThat(header.getCell(8).getStringCellValue()).isEqualTo("涉及报文");
            assertThat(sheet.getRow(2).getCell(7).getStringCellValue()).isEqualTo("returnCode");
            assertThat(sheet.getRow(2).getCell(9).getStringCellValue()).isEqualTo("李四");
            assertThat(sheet.getColumnWidth(2)).isGreaterThanOrEqualTo(14 * 256);
        }
    }
}
