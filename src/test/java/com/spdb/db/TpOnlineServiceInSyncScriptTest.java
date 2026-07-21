package com.spdb.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TpOnlineServiceInSyncScriptTest {

    @Test
    void syncScriptBuildsTpOnlineServiceInFromTranCompAndCatalog() throws Exception {
        String sql = Files.readString(Path.of("db/sync_tp_online_service_in.sql"));

        assertThat(sql).contains("tp_online_service_in");
        assertThat(sql).contains("tss_tran_comp");
        assertThat(sql).contains("ana_tran_catalog");
        assertThat(sql).contains("split_part(trim(dest_trcd), '&', 1)");
        assertThat(sql).contains("substring(c.service_code from 1 for 8) || '.' || substring(c.service_code from 9)");
        assertThat(sql).contains("update tp_online_service_in t");
        assertThat(sql).contains("replace(t.esf_service_code, '.', '') = c.service_code");
        assertThat(sql).contains("where not exists");
        assertThat(sql).contains("where position('&' in trim(dest_trcd)) > 0");
    }
}
