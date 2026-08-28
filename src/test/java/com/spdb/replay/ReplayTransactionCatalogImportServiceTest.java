package com.spdb.replay;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayTransactionCatalogImportServiceTest {
    private NamedParameterJdbcTemplate jdbc;
    private ReplayTransactionCatalogImportService service;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:replay_import;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().execute("drop table if exists ana_replay_transaction_catalog");
        jdbc.getJdbcTemplate().execute("create table ana_replay_transaction_catalog (tran_code varchar(200) primary key, tran_name varchar(200), business_domain varchar(200), batch_type varchar(200), new_core_tran_code varchar(200), new_tran_name varchar(200), replay_required varchar(200), original_service_scene_code varchar(200), new_service_scene_code varchar(200), latest_transaction_date varchar(200), created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp)");
        service = new ReplayTransactionCatalogImportService(new ReplayTransactionCatalogWorkbookParser(), jdbc,
                new DataSourceTransactionManager(dataSource));
        jdbc.getJdbcTemplate().update("insert into ana_replay_transaction_catalog (tran_code, tran_name) values ('OLD','旧数据')");
    }

    @Test
    void replacesCatalogOnlyAfterWholeWorkbookValidated() throws Exception {
        byte[] bytes = workbookBytes("NEW", "", "新", "领域", "查询", "NC", "新名", "是", "OS", "NS", "20260826");
        assertThat(service.importWorkbook(bytes)).isEqualTo(1);
        var stored = jdbc.getJdbcTemplate().queryForList("select tran_code,tran_name from ana_replay_transaction_catalog order by tran_code");
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).get("tran_code")).isEqualTo("NEW");
        assertThat(stored.get(0).get("tran_name")).isEqualTo("新");
    }

    @Test
    void invalidOrEmptyWorkbookKeepsOldCatalog() throws Exception {
        byte[] invalid = workbookBytes("NEW", "", "新", "领域", "查询", "NC", "新名", "是", "OS", "NS", "20260230",
                "SECOND");
        assertThatThrownBy(() -> service.importWorkbook(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.getJdbcTemplate().queryForObject("select count(*) from ana_replay_transaction_catalog where tran_code='OLD'", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> service.importWorkbook(workbookBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.getJdbcTemplate().queryForObject("select count(*) from ana_replay_transaction_catalog", Integer.class)).isEqualTo(1);
    }

    private byte[] workbookBytes(String... values) throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("清单");
            String[] headers = {"528交易码", "交易后缀", "528交易名称", "业务领域（沙箱）", "批次", "新核心交易码", "交易名称", "是否需要参与回放", "原服务场景码", "新服务场景码", "最近交易日期"};
            var header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            if (values.length > 0) {
                var row = sheet.createRow(1);
                for (int i = 0; i < Math.min(values.length, headers.length); i++) row.createCell(i).setCellValue(values[i]);
            }
            try (var output = new ByteArrayOutputStream()) { workbook.write(output); return output.toByteArray(); }
        }
    }
}
