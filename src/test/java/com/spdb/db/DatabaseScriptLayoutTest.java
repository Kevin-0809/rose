package com.spdb.db;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseScriptLayoutTest {

    @Test
    void dbFolderContainsCurrentDdlAndSeedSqlEntrypoints() throws Exception {
        List<String> sqlFiles;
        try (var paths = Files.walk(Path.of("db"))) {
            sqlFiles = paths
                    .filter(path -> path.toString().endsWith(".sql"))
                    .map(path -> Path.of("db").relativize(path).toString())
                    .sorted()
                    .toList();
        }

        assertThat(sqlFiles).contains("ddl.sql", "seed.sql");
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

    @Test
    void databaseScriptsDoNotUseTriggersOrFunctions() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8).toLowerCase();
        String seed = Files.readString(Path.of("db/seed.sql"), StandardCharsets.UTF_8).toLowerCase();
        String scripts = ddl + "\n" + seed;

        assertThat(scripts).doesNotContain("create trigger");
        assertThat(scripts).doesNotContain("drop trigger");
        assertThat(scripts).doesNotContain("returns trigger");
        assertThat(scripts).doesNotContain("execute function");
        assertThat(scripts).doesNotContain("execute procedure");
        assertThat(scripts).doesNotContain("create or replace function");
        assertThat(scripts).doesNotContain("create function");
        assertThat(scripts).doesNotContain("create or replace procedure");
        assertThat(scripts).doesNotContain("create procedure");
        assertThat(scripts).doesNotContain("language plpgsql");
    }

    @Test
    void ddlAvoidsUstoreOnlyIndexSyntaxForExistingTables() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8).toLowerCase();

        assertThat(ddl).doesNotContain("using ubtree");
    }

    @Test
    void ddlContainsMigrationStateTables() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"));

        assertThat(ddl).contains("create table if not exists ana_migration_command");
        assertThat(ddl).contains("source_data_source varchar(64) not null default 'bxds'");
        assertThat(ddl).contains("target_data_source varchar(64) not null default 'primary'");
        assertThat(ddl).contains("add column if not exists source_data_source varchar(64) not null default 'bxds'");
        assertThat(ddl).contains("add column if not exists target_data_source varchar(64) not null default 'primary'");
        assertThat(ddl).contains("create table if not exists ana_migration_shard");
        assertThat(ddl).contains("ck_ana_migration_command_status");
        assertThat(ddl).contains("ck_ana_migration_shard_status");
        assertThat(ddl).contains("idx_ana_migration_shard_command_status");
        assertThat(ddl).doesNotContain("target_schema varchar");
        assertThat(ddl).doesNotContain("comment on column ana_migration_command.target_schema");
    }

    @Test
    void retcodeComparisonTableHasChineseCommentsAndSeedData() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8);
        String ddlLower = ddl.toLowerCase();
        String seedLower = Files.readString(Path.of("db/seed.sql"), StandardCharsets.UTF_8).toLowerCase();
        String retcodeSeed = seedLower.substring(seedLower.indexOf("insert into tss_retcode_comp"));

        assertThat(ddlLower).contains("create table if not exists tss_retcode_comp");
        assertThat(ddlLower).doesNotContain("retcode_id");
        assertThat(ddlLower).contains("mesg_seq varchar(64) primary key");
        assertThat(ddlLower).contains("service_code varchar(200) not null");
        assertThat(ddlLower).doesNotContain("tss_retcode_comp.comp_date");
        assertThat(ddlLower).doesNotContain("tss_retcode_comp.conv_index");
        assertThat(ddlLower).doesNotContain("tss_retcode_comp.conv_cindex");
        assertThat(ddlLower).doesNotContain("tss_retcode_comp.source_table");
        assertThat(ddlLower).contains("orig_error_code varchar(64)");
        assertThat(ddlLower).contains("orig_error_desc varchar(500)");
        assertThat(ddlLower).contains("dest_error_code varchar(64)");
        assertThat(ddlLower).contains("dest_error_desc varchar(500)");

        assertThat(ddl).contains("comment on table tss_retcode_comp is '响应码差异登记表'");
        assertThat(ddl).doesNotContain("comment on column tss_retcode_comp.retcode_id");
        assertThat(ddl).contains("comment on column tss_retcode_comp.mesg_seq is '流水号'");
        assertThat(ddl).contains("comment on column tss_retcode_comp.service_code is '服务码，带报文类型'");
        assertThat(ddl).contains("comment on column tss_retcode_comp.orig_error_code is '528错误码'");
        assertThat(ddl).contains("comment on column tss_retcode_comp.orig_error_desc is '528错误描述'");
        assertThat(ddl).contains("comment on column tss_retcode_comp.dest_error_code is 'CCBS错误码'");
        assertThat(ddl).contains("comment on column tss_retcode_comp.dest_error_desc is 'CCBS错误描述'");

        assertThat(seedLower).contains("delete from tss_retcode_comp");
        assertThat(seedLower).contains("insert into tss_retcode_comp");
        assertThat(retcodeSeed).doesNotContain("comp_date");
        assertThat(seedLower).doesNotContain("'tss_tran_comp' as source_table");
        assertThat(retcodeSeed).contains("from tss_tran_comp t");
        assertThat(retcodeSeed).contains("t.comp_result <> '4'");
        assertThat(retcodeSeed).doesNotContain("then '00000000000'");
        assertThat(retcodeSeed).doesNotContain("528处理成功");
        assertThat(retcodeSeed).doesNotContain("ccbs处理成功");
    }

    @Test
    void legacySamplingTablesAreRemovedFromDdlAndSeed() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8).toLowerCase();
        String seed = Files.readString(Path.of("db/seed.sql"), StandardCharsets.UTF_8).toLowerCase();
        String scripts = ddl + "\n" + seed;

        assertThat(scripts)
                .doesNotContain("ana_sample_group")
                .doesNotContain("ana_sample_detail")
                .doesNotContain("ana_sample_detail_field")
                .doesNotContain("ana_sampling_candidate");
        assertThat(scripts)
                .doesNotContain("idx_ana_sample_group")
                .doesNotContain("idx_ana_sample_detail")
                .doesNotContain("idx_ana_sampling_candidate");
        assertThat(ddl).contains("alter table ana_sampling_summary add column if not exists tran_issue_count bigint");
    }

    @Test
    void samplingResultTablesServePagesDirectly() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8).toLowerCase();

        assertThat(ddl).contains("create table if not exists ana_tran_diff_result");
        assertThat(ddl).contains("create table if not exists ana_field_diff_result");
        assertThat(ddl).contains("sample_tran_seq_no varchar(64)");
        assertThat(ddl).contains("affected_tran_count bigint");
        assertThat(ddl).contains("idx_ana_tran_diff_result_query");
        assertThat(ddl).contains("idx_ana_field_diff_result_query");
    }

    @Test
    void ddlContainsModuleOwnerConfigForLeadershipDashboard() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8);
        String ddlLower = ddl.toLowerCase();

        assertThat(ddlLower).contains("create table if not exists ana_module_owner_config");
        assertThat(ddlLower).contains("module_name varchar(100) not null");
        assertThat(ddlLower).contains("primary_owner varchar(100)");
        assertThat(ddlLower).contains("backup_owner varchar(100)");
        assertThat(ddlLower).contains("constraint uk_ana_module_owner_config unique (module_name)");
        assertThat(ddlLower).contains("idx_ana_module_owner_config_status");
        assertThat(ddl).contains("comment on table ana_module_owner_config is '领域负责人配置表'");
    }
    @Test
    void ddlContainsTransactionListImportTaskTable() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8).toLowerCase();

        assertThat(ddl).contains("create table if not exists ana_transaction_list_import_task");
        assertThat(ddl).contains("task_id bigint generated by default as identity primary key");
        assertThat(ddl).contains("original_filename varchar(255)");
        assertThat(ddl).contains("list_file_path varchar(1000) not null");
        assertThat(ddl).contains("total_count integer not null default 0");
        assertThat(ddl).contains("completed_batch_count integer not null default 0");
        assertThat(ddl).contains("failure_message varchar(4000)");
        assertThat(ddl).contains("ck_ana_transaction_list_import_task_status");
        assertThat(ddl).contains("idx_ana_transaction_list_import_task_status");
    }
}
