package com.spdb.sample;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
class SampleExcelExportServiceTest {


    @Test
    void streamsFieldDiffExportAsHorizontalReviewSheet() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SampleFieldDiffRow row = new SampleFieldDiffRow(
                "20260609", "B20260609", "A001", "S001", "bizjson",
                "HUOBDH", "CurrencyId", "CurrencyId", "币种", "MAPPED",
                "SEQ001", "111", "222", "张三", 12L
        );
        SampleFieldDiffRow secondRow = new SampleFieldDiffRow(
                "20260609", "B20260609", "A002", "S002", "soap",
                "FAB251", "AcctNo", "acctNo", "账号", "UNMAPPED",
                "SEQ002", "6214", "6215", "李四", 3L
        );
        SampleQueryService queryService = new SampleQueryService(null) {
            @Override
            public void streamFieldDiffExport(SampleSearchCriteria criteria, SampleFieldDiffExportConsumer consumer) {
                consumer.accept(row);
                consumer.accept(secondRow);
            }
        };

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
            assertThat(workbook.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex()).getFontHeightInPoints())
                    .isEqualTo((short) 11);
            assertThat(workbook.getFontAt(header.getCell(0).getCellStyle().getFontIndex()).getFontHeightInPoints())
                    .isEqualTo((short) 9);
            assertThat(workbook.getFontAt(sheet.getRow(2).getCell(0).getCellStyle().getFontIndex()).getFontHeightInPoints())
                    .isEqualTo((short) 8);
            assertAllThinBorders(header.getCell(0).getCellStyle());
            assertAllThinBorders(sheet.getRow(2).getCell(0).getCellStyle());
            assertThat(sheet.getRow(2).getCell(0).getCellStyle().getFillForegroundColor())
                    .isEqualTo(IndexedColors.WHITE.getIndex());
            assertThat(sheet.getRow(3).getCell(0).getCellStyle().getFillForegroundColor())
                    .isNotEqualTo(sheet.getRow(2).getCell(0).getCellStyle().getFillForegroundColor());
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

    private void assertAllThinBorders(CellStyle style) {
        assertThat(style.getBorderTop()).isEqualTo(BorderStyle.THIN);
        assertThat(style.getBorderRight()).isEqualTo(BorderStyle.THIN);
        assertThat(style.getBorderBottom()).isEqualTo(BorderStyle.THIN);
        assertThat(style.getBorderLeft()).isEqualTo(BorderStyle.THIN);
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
    void streamsTransactionDiffExportAsUtf8TxtZipSplitByResponseCodeCategories() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService(
                Clock.fixed(Instant.parse("2026-07-06T02:14:00Z"), ZoneId.of("Asia/Shanghai"))
        );
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

            @Override
            public void streamTransactionSuccessStats(SampleSearchCriteria criteria, TransactionSuccessStatConsumer consumer) {
            }

            @Override
            public void streamLeadershipServiceReport(SamplingSummarySearchCriteria criteria, LeadershipServiceReportConsumer consumer) {
            }

            @Override
            public void streamModuleOwnerConfigs(ModuleOwnerConfigConsumer consumer) {
            }
        };

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamTransactionDiffExport(queryService, new SampleSearchCriteria(null, null, null, null, null, null, null, null, null, null, null), out);

        Map<String, String> entries = unzipUtf8(out.toByteArray());
        assertThat(entries.keySet()).containsExactly(
                "transdiff_stat_202607061014.txt",
                "transdiff_ccbs_202607061014.txt",
                "transdiff_cbsp_202607061014.txt",
                "transdiff_both_202607061014.txt",
                "transdiff_success_202607061014.txt",
                "leadership_summary_202607061014.xlsx"
        );
        assertThat(entries.get("transdiff_stat_202607061014.txt")).isEqualTo("""
                类型!数量
                528成功CCBS失败!1
                CCBS成功528失败!1
                528与CCBS均失败但错误码不一致!1
                """);
        assertThat(entries.get("transdiff_ccbs_202607061014.txt")).contains("""
                业务日期!批次!交易码!服务码!报文类型!流水号!交易结果!528响应码!528响应描述!CCBS响应码!CCBS响应描述!责任人!数量
                20260609!B20260609!A001!S001!bizjson!SEQ_528_OK_CCBS_FAIL!8!000000000000!528 response!C0002!CCBS response!owner!12
                """);
        assertThat(entries.get("transdiff_cbsp_202607061014.txt")).contains("SEQ_528_FAIL_CCBS_OK!8!E0001!528 response!AAAAAAA");
        assertThat(entries.get("transdiff_both_202607061014.txt")).contains("SEQ_BOTH_FAIL!8!E0001!528 response!C0002");
        assertThat(entries.get("transdiff_both_202607061014.txt")).doesNotContain("SEQ_BOTH_OK");
    }

    @Test
    void streamsTransactionDiffExportWithSuccessStatFile() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService(
                Clock.fixed(Instant.parse("2026-07-06T02:14:00Z"), ZoneId.of("Asia/Shanghai"))
        );
        SampleQueryService queryService = new SampleQueryService(null) {
            @Override
            public void streamTransactionDiffExport(SampleSearchCriteria criteria, SampleDetailConsumer consumer) {
            }

            @Override
            public void streamTransactionSuccessStats(SampleSearchCriteria criteria, TransactionSuccessStatConsumer consumer) {
                consumer.accept(new TransactionSuccessStatRow(
                        "20260703", "BATCH_01", "C000", "aaa", "bzjson",
                        1000L, 34L, 34000L, 10L, 10000L, 7L, 3L,
                        "存款", "张三,李四"
                ));
            }

            @Override
            public void streamLeadershipServiceReport(SamplingSummarySearchCriteria criteria, LeadershipServiceReportConsumer consumer) {
            }

            @Override
            public void streamModuleOwnerConfigs(ModuleOwnerConfigConsumer consumer) {
            }
        };

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamTransactionDiffExport(queryService, new SampleSearchCriteria(null, null, null, null, null, null, null, null, null, null, null), out);

        Map<String, String> entries = unzipUtf8(out.toByteArray());
        assertThat(entries.keySet()).contains("transdiff_success_202607061014.txt");
        assertThat(entries.get("transdiff_success_202607061014.txt")).isEqualTo("""
                业务日期!批次!交易码!服务码!报文类型!成功数量!接口字段总数!比对字段总数!差异字段数!比对字段差异总数!单字段差异>=1%!单字段差异<1%!领域!责任人
                20260703!BATCH_01!C000!aaa!bzjson!1000!34!34000!10!10000!7!3!存款!张三,李四
                """);
    }

    @Test
    void streamsTransactionDiffExportWithLeadershipWorkbookForExecutives() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService(
                Clock.fixed(Instant.parse("2026-07-06T02:14:00Z"), ZoneId.of("Asia/Shanghai"))
        );
        SampleQueryService queryService = new SampleQueryService(null) {
            @Override
            public void streamTransactionDiffExport(SampleSearchCriteria criteria, SampleDetailConsumer consumer) {
                consumer.accept(transactionDiffRow("SEQ_528_OK_CCBS_FAIL", "000000000000", "C0002"));
                consumer.accept(transactionDiffRow("SEQ_528_FAIL_CCBS_OK", "E0001", "AAAAAAA"));
                consumer.accept(transactionDiffRow("SEQ_BOTH_FAIL", "E0001", "C0002"));
            }

            @Override
            public void streamTransactionSuccessStats(SampleSearchCriteria criteria, TransactionSuccessStatConsumer consumer) {
                consumer.accept(new TransactionSuccessStatRow(
                        "20260703", "BATCH_01", "C000", "aaa", "bizjson",
                        1000L, 34L, 34000L, 10L, 10000L, 7L, 3L,
                        "存款", "张三"
                ));
            }

            @Override
            public void streamLeadershipServiceReport(SamplingSummarySearchCriteria criteria, LeadershipServiceReportConsumer consumer) {
                consumer.accept(new LeadershipServiceReportRow(
                        "BATCH_01", "20260703", "C000", "aaa", "交易一", "存款", "张三",
                        1000L, 5L, 4L, 3L, 900L, 88L, 900L, 9L, 7L, 12L, 860L, 10L
                ));
            }

            @Override
            public void streamModuleOwnerConfigs(ModuleOwnerConfigConsumer consumer) {
                consumer.accept(new ModuleOwnerConfigRow("存款", "张三", "李四", "核心存款交易", "启用"));
            }
        };

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamTransactionDiffExport(queryService, new SampleSearchCriteria(null, null, null, null, null, null, null, null, null, null, null), out);

        Map<String, byte[]> entries = unzipBytes(out.toByteArray());
        assertThat(entries.keySet()).contains(
                "transdiff_stat_202607061014.txt",
                "transdiff_ccbs_202607061014.txt",
                "transdiff_cbsp_202607061014.txt",
                "transdiff_both_202607061014.txt",
                "transdiff_success_202607061014.txt",
                "leadership_summary_202607061014.xlsx"
        );
        assertThat(new String(entries.get("transdiff_stat_202607061014.txt"), StandardCharsets.UTF_8)).startsWith("类型!数量\n");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(entries.get("leadership_summary_202607061014.xlsx")))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(6);
            assertThat(workbook.getSheetName(0)).isEqualTo("领导总览");
            assertThat(workbook.getSheet("责任人看板")).isNotNull();
            assertThat(workbook.getSheet("领域看板")).isNotNull();
            assertThat(workbook.getSheet("领域负责人配置")).isNotNull();
            assertThat(workbook.getSheet("服务码明细")).isNotNull();
            assertThat(workbook.getSheet("字段差异摘要")).isNotNull();

            Sheet summary = workbook.getSheet("领导总览");
            assertThat(summary.getRow(0).getCell(0).getStringCellValue()).isEqualTo("回放差异领导汇总报表");
            assertThat(summary.getRow(3).getCell(0).getStringCellValue()).contains("发起交易数", "1,000");
            assertThat(summary.getRow(6).getCell(0).getStringCellValue()).isEqualTo("本批结论");

            Sheet config = workbook.getSheet("领域负责人配置");
            assertThat(rowText(config.getRow(3))).contains("领域", "主负责人", "备份负责人", "状态");
            assertThat(config.getRow(4).getCell(0).getStringCellValue()).isEqualTo("存款");
            assertThat(config.getRow(4).getCell(1).getStringCellValue()).isEqualTo("张三");

            Sheet serviceSheet = workbook.getSheet("服务码明细");
            assertThat(rowText(serviceSheet.getRow(3))).contains("领域", "责任人", "字段差异流水数");
            assertThat(serviceSheet.getPaneInformation().isFreezePane()).isTrue();
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

    private Map<String, String> unzipUtf8(byte[] bytes) throws Exception {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private Map<String, byte[]> unzipBytes(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    @Test
    void streamsServiceReportWithCountsAndPercentColumnsForLeadership() throws Exception {
        SampleExcelExportService service = new SampleExcelExportService();
        SamplingServiceReportRow row = new SamplingServiceReportRow(
                "BATCH_RPT", "20260611", "A001", "S001", "交易一", "张三",
                10L, 1L, 2L, 1L, 4L, 2L, 4L, 3L, 2L, 1L, 3L, 5L
        );
        SampleQueryService queryService = new SampleQueryService(null) {
            @Override
            public void streamServiceReport(SamplingSummarySearchCriteria criteria, SamplingServiceReportConsumer consumer) {
                consumer.accept(row);
            }
        };

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

}
