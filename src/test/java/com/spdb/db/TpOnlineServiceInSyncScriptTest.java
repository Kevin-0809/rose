package com.spdb.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class TpOnlineServiceInSyncScriptTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tp_online_service_sync;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("create alias if not exists split_part for \"com.spdb.db.TpOnlineServiceInSyncScriptTest.splitPart\"");
        jdbc.execute("drop table if exists tp_online_service_in");
        jdbc.execute("drop table if exists ana_tran_catalog");
        jdbc.execute("drop table if exists tss_tran_comp");
        jdbc.execute("create table tss_tran_comp(dest_trcd varchar(200))");
        jdbc.execute("create table ana_tran_catalog(tran_code varchar(32), service_code varchar(200))");
        jdbc.execute("create table tp_online_service_in(tran_code varchar(32), esf_service_code varchar(200))");
    }

    @Test
    void syncScriptUsesUpdateThenInsertWithoutConflictConstraint() throws Exception {
        String sql = Files.readString(Path.of("db/sync_tp_online_service_in.sql"));

        assertThat(sql).contains("tp_online_service_in");
        assertThat(sql).contains("tss_tran_comp");
        assertThat(sql).contains("ana_tran_catalog");
        assertThat(sql).contains("update tp_online_service_in t");
        assertThat(sql).contains("replace(t.esf_service_code, '.', '') = c.service_code");
        assertThat(sql).contains("where not exists");
        assertThat(sql).doesNotContain("on conflict");
    }

    @Test
    void syncScriptInsertsObservedCatalogServiceWithDottedEsfCode() throws Exception {
        jdbc.update("insert into tss_tran_comp(dest_trcd) values (?)", "S030030014FcyCollCrspBnkLkgQry&bzjson");
        jdbc.update("insert into ana_tran_catalog(tran_code, service_code) values (?, ?)",
                "A825", "S030030014FcyCollCrspBnkLkgQry");

        executeSyncScript();

        assertThat(countTargetRows("A825", "S03003001.4FcyCollCrspBnkLkgQry")).isEqualTo(1);
    }

    @Test
    void syncScriptUpdatesExistingUndottedServiceCode() throws Exception {
        jdbc.update("insert into tss_tran_comp(dest_trcd) values (?)", "S030030014FcyCollCrspBnkLkgQry&bzjson");
        jdbc.update("insert into ana_tran_catalog(tran_code, service_code) values (?, ?)",
                "A825", "S030030014FcyCollCrspBnkLkgQry");
        jdbc.update("insert into tp_online_service_in(tran_code, esf_service_code) values (?, ?)",
                "A825", "S030030014FcyCollCrspBnkLkgQry");

        executeSyncScript();

        assertThat(countTargetRows("A825", "S03003001.4FcyCollCrspBnkLkgQry")).isEqualTo(1);
        assertThat(countTargetRows("A825", "S030030014FcyCollCrspBnkLkgQry")).isZero();
    }

    @Test
    void syncScriptDeletesEquivalentUndottedRowWhenDottedRowAlreadyExists() throws Exception {
        jdbc.update("insert into tss_tran_comp(dest_trcd) values (?)", "S030030014FcyCollCrspBnkLkgQry&bzjson");
        jdbc.update("insert into ana_tran_catalog(tran_code, service_code) values (?, ?)",
                "A825", "S030030014FcyCollCrspBnkLkgQry");
        jdbc.update("insert into tp_online_service_in(tran_code, esf_service_code) values (?, ?)",
                "A825", "S030030014FcyCollCrspBnkLkgQry");
        jdbc.update("insert into tp_online_service_in(tran_code, esf_service_code) values (?, ?)",
                "A825", "S03003001.4FcyCollCrspBnkLkgQry");

        executeSyncScript();

        assertThat(countTargetRows("A825", "S03003001.4FcyCollCrspBnkLkgQry")).isEqualTo(1);
        assertThat(countTargetRows("A825", "S030030014FcyCollCrspBnkLkgQry")).isZero();
    }

    @Test
    void syncScriptIsRepeatableWithoutDuplicateTargetRows() throws Exception {
        jdbc.update("insert into tss_tran_comp(dest_trcd) values (?)", "S030030014FcyCollCrspBnkLkgQry&bzjson");
        jdbc.update("insert into ana_tran_catalog(tran_code, service_code) values (?, ?)",
                "A825", "S030030014FcyCollCrspBnkLkgQry");

        executeSyncScript();
        executeSyncScript();

        assertThat(countTargetRows("A825", "S03003001.4FcyCollCrspBnkLkgQry")).isEqualTo(1);
    }

    @Test
    void syncScriptIgnoresDestinationsWithoutMessageTypeBlankValuesAndCatalogMisses() throws Exception {
        jdbc.update("insert into tss_tran_comp(dest_trcd) values (?)", "S030030014FcyCollCrspBnkLkgQry");
        jdbc.update("insert into tss_tran_comp(dest_trcd) values (?)", "");
        jdbc.update("insert into tss_tran_comp(dest_trcd) values (?)", (String) null);
        jdbc.update("insert into tss_tran_comp(dest_trcd) values (?)", "S999999994UnknownService&bzjson");
        jdbc.update("insert into ana_tran_catalog(tran_code, service_code) values (?, ?)",
                "A825", "S030030014FcyCollCrspBnkLkgQry");

        executeSyncScript();

        Integer rowCount = jdbc.queryForObject("select count(*) from tp_online_service_in", Integer.class);
        assertThat(rowCount).isZero();
    }

    private void executeSyncScript() throws Exception {
        String sql = Files.readString(Path.of("db/sync_tp_online_service_in.sql"));
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            return null;
        });
    }

    private Integer countTargetRows(String tranCode, String esfServiceCode) {
        return jdbc.queryForObject("""
                select count(*)
                from tp_online_service_in
                where tran_code = ?
                  and esf_service_code = ?
                """, Integer.class, tranCode, esfServiceCode);
    }

    public static String splitPart(String value, String delimiter, int field) {
        if (value == null || delimiter == null || delimiter.isEmpty() || field < 1) {
            return null;
        }
        String[] parts = value.split(java.util.regex.Pattern.quote(delimiter), -1);
        return field <= parts.length ? parts[field - 1] : "";
    }
}
