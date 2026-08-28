package com.spdb.replay;

import com.spdb.web.PageRequestParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayTransactionCatalogServiceTest {
    private NamedParameterJdbcTemplate jdbc;
    private ReplayTransactionCatalogService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:replay_catalog_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().execute("drop table if exists ana_replay_transaction_catalog");
        jdbc.getJdbcTemplate().execute("""
                create table ana_replay_transaction_catalog (
                    tran_code varchar(200) primary key,
                    tran_name varchar(200), business_domain varchar(200), batch_type varchar(200),
                    new_core_tran_code varchar(200), new_tran_name varchar(200), replay_required varchar(200),
                    original_service_scene_code varchar(200), new_service_scene_code varchar(200),
                    latest_transaction_date varchar(200), created_at timestamp default current_timestamp,
                    updated_at timestamp default current_timestamp)
                """);
        service = new ReplayTransactionCatalogService(jdbc);
    }

    @Test
    void savesAndQueriesByTranCode() {
        service.save(form(" T001 ", "交易一", "支付", "查询", "是", "20260826"), null);
        assertThat(service.find("T001").tranName()).isEqualTo("交易一");
        assertThat(service.search(new ReplayTransactionCatalogSearch("T001", null, null, null, null), PageRequestParams.of(1, 20)).total()).isEqualTo(1);
    }

    @Test
    void supportsFiveFiltersAndPaging() {
        service.save(form("T001", "交易一", "支付", "查询", "是", "20260826"), null);
        service.save(form("T002", "交易二", "清算", "动账", "否", "20260825"), null);
        assertThat(service.search(new ReplayTransactionCatalogSearch(null, "交易", "支付", "查询", "是"), PageRequestParams.of(1, 20)).rows())
                .extracting(ReplayTransactionCatalogRow::tranCode).containsExactly("T001");
    }

    @Test
    void editCannotChangePrimaryKeyAndDeleteRemovesRow() {
        service.save(form("T001", "交易一", "支付", "查询", "是", "20260826"), null);
        service.save(form("T999", "新名称", "支付", "查询", "否", "20260826"), "T001");
        assertThat(service.find("T001").tranName()).isEqualTo("新名称");
        assertThat(service.find("T999")).isNull();
        service.delete("T001");
        assertThat(service.find("T001")).isNull();
    }

    @Test
    void validatesReplayRequiredBatchTypeAndDate() {
        assertThatThrownBy(() -> service.save(form("T1", "n", "d", "其他", "是", "20260826"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(form("T1", "n", "d", "查询", "maybe", "20260826"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(form("T1", "n", "d", "查询", "是", "20260230"), null)).isInstanceOf(IllegalArgumentException.class);
        service.save(form("T1", "n", "d", "", "", ""), null);
    }

    @Test
    void clampsExtremelyLargePageToLastPageBeforeLoadingRows() {
        for (int i = 1; i <= 21; i++) {
            service.save(form("T%03d".formatted(i), "交易" + i, "支付", "查询", "是", "20260826"), null);
        }

        var result = service.search(new ReplayTransactionCatalogSearch(null, null, null, null, null),
                PageRequestParams.of(Integer.MAX_VALUE, 20));
        assertThat(result.rows()).extracting(ReplayTransactionCatalogRow::tranCode).containsExactly("T021");
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    private ReplayTransactionCatalogForm form(String code, String name, String domain, String batch, String required, String date) {
        return new ReplayTransactionCatalogForm(code, name, domain, batch, "NC", "新", required, "OS", "NS", date, null);
    }
}
