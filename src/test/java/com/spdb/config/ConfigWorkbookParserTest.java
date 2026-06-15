package com.spdb.config;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigWorkbookParserTest {

    private final ConfigWorkbookParser parser = new ConfigWorkbookParser();

    @Test
    void parsesTransactionAndOutputFieldMappingsFromA825Workbook() throws Exception {
        try (Workbook workbook = a825Workbook()) {
            ParsedConfigImport parsed = parser.parse(workbook, "A825+comm+张三.xlsx",
                    "S030030014FcyCollCrspBnkLkgQry", null, null);

            assertThat(parsed.tran().tranCode()).isEqualTo("A825");
            assertThat(parsed.tran().serviceCode()).isEqualTo("S030030014FcyCollCrspBnkLkgQry");
            assertThat(parsed.tran().tranName()).isEqualTo("外币托收代理行联动查询");
            assertThat(parsed.tran().moduleName()).isEqualTo("");
            assertThat(parsed.tran().owner()).isEqualTo("");

            List<ParsedFieldImport> fields = parsed.fields();
            assertThat(fields).extracting(ParsedFieldImport::sopFieldName)
                    .containsExactly("HUOBDH", "FAB251", "TUOSFS", "DLZJMC");
            assertThat(fields).extracting(ParsedFieldImport::bizjsonFieldName)
                    .containsExactly("CurrencyId", "FcyCollCrspBnkLkgInfo", "RcrMtd", "AgentCtfType");
            assertThat(fields).extracting(ParsedFieldImport::soapFieldName)
                    .containsExactly("CurrencyId", "FcyCollCrspBnkLkgInfo", "RcrMtd", "AgentCtfType");
            assertThat(fields).extracting(ParsedFieldImport::stdFieldName)
                    .containsExactly("HUOBDH", "FAB251", "TUOSFS", "DLZJMC");
            assertThat(fields).extracting(ParsedFieldImport::remark)
                    .allSatisfy(remark -> assertThat(remark).contains("输出"));
            assertThat(fields).extracting(ParsedFieldImport::remark)
                    .anySatisfy(remark -> assertThat(remark).contains("数组"));
        }
    }

    @Test
    void convertsLowerCamelTargetToUpperCamelSoapAndKeepsBizjsonAsProvided() {
        assertThat(ConfigWorkbookParser.toSoapFieldName("currencyId")).isEqualTo("CurrencyId");
        assertThat(ConfigWorkbookParser.toSoapFieldName("CurrencyId")).isEqualTo("CurrencyId");
    }

    @Test
    void ignoresFilenameForModuleAndUsesExplicitOwner() {
        try (Workbook workbook = a825Workbook()) {
            ParsedConfigImport parsed = parser.parse(workbook, "A825-loan-张伟.xlsx",
                    "S030030014FcyCollCrspBnkLkgQry", null, "张伟");

            assertThat(parsed.tran().moduleName()).isEqualTo("");
            assertThat(parsed.tran().owner()).isEqualTo("张伟");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void derivesServiceCodeFromServiceNameAndOperationNameWhenServiceCodeIsBlank() throws Exception {
        try (Workbook workbook = a825Workbook()) {
            ParsedConfigImport parsed = parser.parse(workbook, "A825.xlsx", "", null, "");

            assertThat(parsed.tran().serviceCode()).isEqualTo("S030030014FcyCollCrspBnkLkgQry");
            assertThat(parsed.fields()).extracting(ParsedFieldImport::serviceCode)
                    .containsOnly("S030030014FcyCollCrspBnkLkgQry");
            assertThat(parsed.tran().owner()).isEqualTo("");
        }
    }

    @Test
    void parsesAllNonIndexSheetsFromRestoredWorkbook() throws Exception {
        List<ParsedConfigImport> parsed = parser.parseAll(Path.of("11_22_restored.xlsx"),
                "11_22_restored.xlsx", "", "", "");

        assertThat(parsed).hasSize(3);
        assertThat(parsed).extracting(item -> item.tran().tranCode())
                .containsExactly("A825", "A826", "A827");
        assertThat(parsed).extracting(item -> item.tran().serviceCode())
                .containsExactly(
                        "S030030014FcyCollCrspBnkLkgQry",
                        "S030030015FcyCollCrspBnkLkgQry",
                        "S030030016FcyCollCrspBnkLkgQry"
                );
        assertThat(parsed).allSatisfy(item -> {
            assertThat(item.tran().moduleName()).isEqualTo("");
            assertThat(item.tran().owner()).isEqualTo("");
            assertThat(item.fields()).hasSize(4);
        });
    }

    @Test
    void removesWhitespaceInsideTargetFieldNames() {
        assertThat(ConfigWorkbookParser.normalizeFieldName("FcyCollCrspBnkLkg\nInfo")).isEqualTo("FcyCollCrspBnkLkgInfo");
        assertThat(ConfigWorkbookParser.toSoapFieldName("fcyCollCrspBnkLkg\nInfo")).isEqualTo("FcyCollCrspBnkLkgInfo");
    }

    static Workbook a825Workbook() {
        Workbook workbook = new XSSFWorkbook();
        workbook.createSheet("INDEX");
        Sheet sheet = workbook.createSheet("A825");
        row(sheet, 0, "交易码", "A825", "", "", "", "", "", "", "", "", "服务名称", "托收信息查询(S030030014)");
        row(sheet, 1, "交易名称", "外币托收代理行联动查询", "", "", "", "", "", "", "", "", "服务操作名称", "FcyCollCrspBnkLkgQry(外币托收代理行联动查询)");
        row(sheet, 4, "原始接口", "", "", "", "", "", "", "", "", "", "SPDBSI");
        row(sheet, 5, "英文名称", "中文名称", "数据类型", "是否必输", "取值类型", "枚举值", "范围", "含义", "备注", "", "英文名称", "数据类型", "中文名称", "备注");
        row(sheet, 6, "输入");
        row(sheet, 7, "HUOBDH", "货币代号", "char(2)", "", "", "", "", "", "0A8251", "", "CurrencyId", "string(3)", "货币代码", "0A8251");
        row(sheet, 8, "输出");
        row(sheet, 9, "HUOBDH", "货币代号", "char(2)", "", "", "", "", "", "0A8252", "", "CurrencyId", "string(3)", "货币代码", "0A8252");
        row(sheet, 10, "FAB251", "", "array", "", "", "", "", "", "Start", "", "FcyCollCrspBnkLkgInfo", "Array", "外币托收代理行联动数组", "Start");
        row(sheet, 11, "TUOSFS", "托收方式", "char(1)", "", "", "", "", "", "", "", "RcrMtd", "string(3)", "托收方式", "");
        row(sheet, 12, "DLZJMC", "基金代理证件类型名称", "char(20)", "", "", "", "", "", "", "", "AgentCtfType", "string(40)", "代理人证件类型", "");
        row(sheet, 13, "FAB251", "", "array", "", "", "", "", "", "End", "", "FcyCollCrspBnkLkgInfo", "Array", "外币托收代理行联动数组", "End");
        return workbook;
    }

    private static void row(Sheet sheet, int rowIndex, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }
}
