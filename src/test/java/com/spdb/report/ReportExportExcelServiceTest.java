package com.spdb.report;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReportExportExcelServiceTest {
    private JdbcTemplate jdbc;
    private ReportExportExcelService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:report_excel_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        service = new ReportExportExcelService(new NamedParameterJdbcTemplate(dataSource));
        jdbc.execute("create table ana_report_export_summary(batch_id varchar(64), report_date varchar(8), module_name varchar(100), covered_528_interface_count bigint, sent_transaction_count bigint, comp_result_1_count bigint, comp_result_2_count bigint, comp_result_3_count bigint, comp_result_4_count bigint, comp_result_8_count bigint, success_rate decimal(12,8), diff_528_field_count bigint)");
        jdbc.execute("create table ana_tran_diff_tracking_export(source_batch_id varchar(64), module_name varchar(100), row_no bigint, tran_code varchar(32), tran_name varchar(200), transaction_owner varchar(100), tran_seq_no varchar(64), problem_level varchar(100), registration_date varchar(8), field_name varchar(500), problem_description varchar(2000), problem_type varchar(100), preliminary_analysis varchar(2000), final_solution varchar(2000), resolution_date varchar(8), coordination_required varchar(100), resolver varchar(100), defect_fix_date varchar(8), historical_occurrence_count bigint not null default 0, first_seen_date date, previous_seen_date date)");
        jdbc.execute("create table ana_field_diff_tracking_export(source_batch_id varchar(64), module_name varchar(100), row_no bigint, tran_code varchar(32), tran_name varchar(200), transaction_owner varchar(100), tran_seq_no varchar(64), problem_level varchar(100), registration_date varchar(8), field_name varchar(500), problem_description varchar(2000), problem_type varchar(100), preliminary_analysis varchar(2000), final_solution varchar(2000), resolution_date varchar(8), coordination_required varchar(100), resolver varchar(100), defect_fix_date varchar(8), orig_field_value varchar(2000), dest_field_value varchar(2000), historical_occurrence_count bigint not null default 0, first_seen_date date, previous_seen_date date)");
    }

    @Test
    void streamsSummaryAndUnifiedDomainDetailsWithIssueHistoryWithoutFieldValues() throws Exception {
        jdbc.update("insert into ana_report_export_summary values ('RPT1','20260727','支付',1,10,1,2,3,4,5,0.7,6)");
        jdbc.update("""
                insert into ana_tran_diff_tracking_export(source_batch_id,module_name,row_no,tran_code,tran_name,
                    problem_level,registration_date,field_name,problem_description,historical_occurrence_count,
                    first_seen_date,previous_seen_date)
                values ('RPT1','支付',1,'T1','交易一','交易级','20260727','528失败/CCBS成功','交易描述',3,?,?)
                """, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-20"));
        jdbc.update("""
                insert into ana_field_diff_tracking_export(source_batch_id,module_name,row_no,tran_code,tran_name,
                    problem_level,registration_date,field_name,problem_description,orig_field_value,dest_field_value,
                    historical_occurrence_count,first_seen_date,previous_seen_date)
                values ('RPT1','支付',1,'T1','交易一','字段级','20260727','A | B','528：有值；CCBS：无值','secret','',2,?,?)
                """, LocalDate.parse("2026-07-02"), LocalDate.parse("2026-07-18"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        service.stream("RPT1", output);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("汇总信息");
            assertThat(workbook.getSheetAt(0).getRow(2).getCell(8).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(workbook.getSheetAt(0).getRow(2).getCell(8).getNumericCellValue()).isEqualTo(0.7d);
            assertThat(workbook.getSheetAt(0).getRow(2).getCell(8).getCellStyle().getDataFormatString()).isEqualTo("0.00%");
            assertThat(workbook.getSheet("支付").getPaneInformation().isFreezePane()).isTrue();
            assertThat(workbook.getSheet("支付").getRow(0).getCell(18).getStringCellValue()).isEqualTo("历史出现次数");
            assertThat(workbook.getSheet("支付").getRow(1).getCell(5).getStringCellValue()).isEqualTo("交易级");
            assertThat(workbook.getSheet("支付").getRow(1).getCell(18).getStringCellValue()).isEqualTo("3");
            assertThat(workbook.getSheet("支付").getRow(1).getCell(19).getStringCellValue()).isEqualTo("2026-07-01");
            assertThat(workbook.getSheet("支付").getRow(1).getCell(20).getStringCellValue()).isEqualTo("2026-07-20");
            assertThat(workbook.getSheet("支付").getRow(2).getCell(8).getStringCellValue()).isEqualTo("528：有值；CCBS：无值");
            assertThat(workbook.getSheet("支付").getRow(2).getCell(18).getStringCellValue()).isEqualTo("2");
        }
        assertThat(output.toString()).doesNotContain("secret");
    }
}
