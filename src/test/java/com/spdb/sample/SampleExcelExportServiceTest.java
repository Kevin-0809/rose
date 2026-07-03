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
    void streamsFieldDiffExportAsHorizontalReviewSheet() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleQueryService queryService = mock(SampleQueryService.class);
        SampleFieldDiffRow row = new SampleFieldDiffRow(
                1L, "20260609", "B20260609", "A001", "S001", "bizjson",
                "HUOBDH", "CurrencyId", "CurrencyId", "币种", "MAPPED",
                "SEQ001", "111", "222", "张三", 12L
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
            assertThat(rowText(header)).isEqualTo("业务日期,批次号,交易码,服务码,报文类型,SOP字段名,SOAP字段名,BizJSON字段名,字段中文名,映射状态,样例流水号,528值,CCBS值,责任人,影响交易笔数,审核人,是否修复,差异分类,差异说明");
            assertThat(header.getLastCellNum()).isEqualTo((short) 19);
            assertThat(rowText(header)).doesNotContain("原字段名", "标准字段名", "字段序号", "字段数", "来源表");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("20260609");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("B20260609");
            assertThat(sheet.getRow(2).getCell(5).getStringCellValue()).isEqualTo("HUOBDH");
            assertThat(sheet.getRow(2).getCell(9).getStringCellValue()).isEqualTo("已映射");
            assertThat(sheet.getRow(2).getCell(10).getStringCellValue()).isEqualTo("SEQ001");
            assertThat(sheet.getRow(2).getCell(11).getStringCellValue()).isEqualTo("111");
            assertThat(sheet.getRow(2).getCell(12).getStringCellValue()).isEqualTo("222");
            assertThat(sheet.getRow(2).getCell(13).getStringCellValue()).isEqualTo("张三");
            assertThat(sheet.getRow(2).getCell(14).getNumericCellValue()).isEqualTo(12);
            assertThat(sheet.getRow(2).getCell(15).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(2).getCell(16).getStringCellValue()).isEqualTo("否");
            assertThat(sheet.getRow(2).getCell(17).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(2).getCell(18).getStringCellValue()).isEmpty();
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
    void streamsTransactionDiffExportSplitByResponseCodeCategories() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleDetailRow origSuccessDestFail = transactionDiffRow("SEQ_528_OK_CCBS_FAIL", "000000000000", "C0002");
        SampleDetailRow origFailDestSuccess = transactionDiffRow("SEQ_528_FAIL_CCBS_OK", "E0001", "AAAAAAA");
        SampleDetailRow bothSuccess = transactionDiffRow("SEQ_BOTH_OK", "000000000000", "AAAAAAA");
        SampleDetailRow bothFail = transactionDiffRow("SEQ_BOTH_FAIL", "E0001", "C0002");
        SampleQueryService queryService = new SampleQueryService(null) {
            @Override
            public void streamTransactionDiffExport(SampleSearchCriteria criteria, SampleDetailConsumer consumer) {
                consumer.accept(origSuccessDestFail);
                consumer.accept(origFailDestSuccess);
                consumer.accept(bothSuccess);
                consumer.accept(bothFail);
            }
        };

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamTransactionDiffExport(queryService, new SampleSearchCriteria(null, null, null, null, null, null, null, null, null, null, null), out);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(workbook.getSheetName(0)).isEqualTo("528\u6210\u529fCCBS\u5931\u8d25");
            assertThat(workbook.getSheetName(1)).isEqualTo("528\u5931\u8d25CCBS\u6210\u529f");
            assertThat(workbook.getSheetName(2)).isEqualTo("\u4e8c\u8005\u90fd\u6210\u529f");
            assertThat(workbook.getSheetName(3)).isEqualTo("\u4e8c\u8005\u90fd\u5931\u8d25");

            Sheet firstSheet = workbook.getSheet("528\u6210\u529fCCBS\u5931\u8d25");
            assertThat(firstSheet).isNotNull();
            assertThat(firstSheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("528\u6210\u529fCCBS\u5931\u8d25");
            Row header = firstSheet.getRow(1);
            assertThat(header.getLastCellNum()).isEqualTo((short) 13);
            assertThat(header.getCell(5).getStringCellValue()).isNotBlank();
            assertThat(header.getCell(7).getStringCellValue()).contains("528");
            assertThat(header.getCell(9).getStringCellValue()).contains("CCBS");
            assertThat(firstSheet.getRow(2).getCell(5).getStringCellValue()).isEqualTo("SEQ_528_OK_CCBS_FAIL");
            assertThat(firstSheet.getRow(2).getCell(7).getStringCellValue()).isEqualTo("000000000000");
            assertThat(firstSheet.getRow(2).getCell(9).getStringCellValue()).isEqualTo("C0002");
            assertThat(firstSheet.getRow(2).getCell(12).getNumericCellValue()).isEqualTo(12);
            assertThat(firstSheet.getRow(3)).isNull();

            assertSheetOnlyContains(workbook, "528\u5931\u8d25CCBS\u6210\u529f", "SEQ_528_FAIL_CCBS_OK");
            assertSheetOnlyContains(workbook, "\u4e8c\u8005\u90fd\u6210\u529f", "SEQ_BOTH_OK");
            assertSheetOnlyContains(workbook, "\u4e8c\u8005\u90fd\u5931\u8d25", "SEQ_BOTH_FAIL");
        }
    }

    private SampleDetailRow transactionDiffRow(String tranSeqNo, String origErrorCode, String destErrorCode) {
        return new SampleDetailRow(
                1L, 2L, "B20260609", "20260609", "RETURN_CODE", 1, "CONFIGURED", "S001&bizjson",
                "S001", "bizjson", "A001", "8", null, null, null, null,
                tranSeqNo, "owner", 12L, 0,
                origErrorCode, "528 response", destErrorCode, "CCBS response",
                "return code mismatch", "tss_retcode_comp", tranSeqNo
        );
    }

    private void assertSheetOnlyContains(XSSFWorkbook workbook, String sheetName, String tranSeqNo) {
        Sheet sheet = workbook.getSheet(sheetName);
        assertThat(sheet).isNotNull();
        assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo(sheetName);
        assertThat(sheet.getRow(2).getCell(5).getStringCellValue()).isEqualTo(tranSeqNo);
        assertThat(sheet.getRow(3)).isNull();
    }

    @Test
    void streamsServiceReportWithCountsAndPercentColumnsForLeadership() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleQueryService queryService = mock(SampleQueryService.class);
        SamplingServiceReportRow row = new SamplingServiceReportRow(
                "BATCH_RPT", "20260611", "A001", "S001", "交易一", "张三",
                10L, 1L, 2L, 1L, 4L, 2L, 4L, 3L, 2L, 1L, 3L, 5L
        );
        doAnswer(invocation -> {
            SamplingServiceReportConsumer consumer = invocation.getArgument(1);
            consumer.accept(row);
            return null;
        }).when(queryService).streamServiceReport(any(), any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamServiceReport(queryService, new SamplingSummarySearchCriteria("BATCH_RPT", "20260611"), out);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("服务码汇报");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("采样服务码维度汇报");
            Row header = sheet.getRow(1);
            assertThat(rowText(header)).contains("服务码", "发起交易数", "通过率", "响应码问题占比", "问题字段数");
            assertThat(sheet.getRow(2).getCell(3).getStringCellValue()).isEqualTo("S001");
            assertThat(sheet.getRow(2).getCell(6).getNumericCellValue()).isEqualTo(10);
            assertThat(sheet.getRow(2).getCell(8).getNumericCellValue()).isEqualTo(0.4d);
            assertThat(sheet.getRow(2).getCell(20).getNumericCellValue()).isEqualTo(0.2d);
            assertThat(sheet.getRow(2).getCell(26).getNumericCellValue()).isEqualTo(5);
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
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
