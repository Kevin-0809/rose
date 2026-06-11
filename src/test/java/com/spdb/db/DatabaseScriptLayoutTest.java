package com.spdb.db;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseScriptLayoutTest {

    @Test
    void dbFolderOnlyContainsCurrentDdlAndSeedSqlEntrypoints() throws Exception {
        List<String> sqlFiles;
        try (var paths = Files.walk(Path.of("db"))) {
            sqlFiles = paths
                    .filter(path -> path.toString().endsWith(".sql"))
                    .map(path -> Path.of("db").relativize(path).toString())
                    .sorted()
                    .toList();
        }

        assertThat(sqlFiles).containsExactly("ddl.sql", "seed.sql");
    }

    @Test
    void seedScriptContainsOnlyDataManipulation() throws Exception {
        String seed = Files.readString(Path.of("db/seed.sql"), StandardCharsets.UTF_8).toLowerCase();

        assertThat(seed).doesNotContain("create table");
        assertThat(seed).doesNotContain("create temporary table");
        assertThat(seed).doesNotContain("alter table");
        assertThat(seed).doesNotContain("create index");
        assertThat(seed).doesNotContain("drop table");
    }

    @Test
    void ddlScriptDoesNotContainSeedData() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8);

        assertThat(ddl).doesNotContain("TEST_SEED");
        assertThat(ddl).doesNotContain("BATCH_20260608_SEED");
        assertThat(ddl.toLowerCase()).doesNotContain("insert into");
        assertThat(ddl.toLowerCase()).doesNotContain("delete from");
    }
}
