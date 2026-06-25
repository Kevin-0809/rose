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
        String retcodeSeed = seedLower.substring(
                seedLower.indexOf("insert into tss_retcode_comp"),
                seedLower.indexOf("-- 005_seed_ana_samples_from_tss.sql")
        );

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
    void sampleGroupStoresSemanticGroupingFields() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8).toLowerCase();

        assertThat(ddl).contains("orig_cdate varchar(8)");
        assertThat(ddl).contains("config_status varchar(32)");
        assertThat(ddl).contains("mapping_status varchar(32)");
        assertThat(ddl).contains("semantic_signature varchar(2000)");
        assertThat(ddl).contains("semantic_signature_hash varchar(32)");
        assertThat(ddl).contains("semantic_field_names varchar(1000)");
        assertThat(ddl).contains("message_types varchar(200)");
        assertThat(ddl).contains("affected_tran_count bigint");
        assertThat(ddl).contains("affected_field_count bigint");
    }

    @Test
    void ddlCanUpgradeExistingSemanticSamplingTables() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8).toLowerCase();

        assertThat(ddl).contains("alter table ana_sample_group add column if not exists orig_cdate varchar(8)");
        assertThat(ddl).contains("alter table ana_sample_group add column if not exists config_status varchar(32)");
        assertThat(ddl).contains("alter table ana_sample_group add column if not exists mapping_status varchar(32)");
        assertThat(ddl).contains("alter table ana_sample_group add column if not exists semantic_signature varchar(2000)");
        assertThat(ddl).contains("alter table ana_sample_group add column if not exists semantic_signature_hash varchar(32)");
        assertThat(ddl).contains("alter table ana_sample_group add column if not exists semantic_field_names varchar(1000)");
        assertThat(ddl).contains("alter table ana_sample_group add column if not exists message_types varchar(200)");
        assertThat(ddl).contains("alter table ana_sample_group add column if not exists affected_tran_count bigint");
        assertThat(ddl).contains("alter table ana_sample_group add column if not exists affected_field_count bigint");
        assertThat(ddl).contains("alter table ana_sample_group alter column sop_field_name drop not null");
        assertThat(ddl).contains("update ana_sample_group set sample_type = 'return_code' where sample_type = 'tran_result'");
        assertThat(ddl).contains("alter table ana_sample_group drop constraint if exists ck_ana_sample_group_type");
        assertThat(ddl).contains("alter table ana_sample_group add constraint ck_ana_sample_group_type check (sample_type in ('return_code', 'field_diff'))");
        assertThat(ddl).contains("alter table ana_sample_detail add column if not exists field_count integer");
        assertThat(ddl).contains("alter table ana_sample_detail alter column sop_field_name drop not null");
        assertThat(ddl).contains("update ana_sample_detail set sample_type = 'return_code' where sample_type = 'tran_result'");
        assertThat(ddl).contains("alter table ana_sample_detail drop constraint if exists ck_ana_sample_detail_type");
        assertThat(ddl).contains("alter table ana_sample_detail add constraint ck_ana_sample_detail_type check (sample_type in ('return_code', 'field_diff'))");
        assertThat(ddl).contains("alter table ana_sampling_summary add column if not exists tran_issue_count bigint");
    }

    @Test
    void sampleDetailFieldTableExists() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8).toLowerCase();

        assertThat(ddl).contains("create table if not exists ana_sample_detail_field");
        assertThat(ddl).contains("field_detail_id bigserial primary key");
        assertThat(ddl).contains("sample_id bigint not null");
        assertThat(ddl).contains("raw_field_name varchar(200)");
        assertThat(ddl).contains("std_field_name varchar(200)");
        assertThat(ddl).contains("mapping_status varchar(32)");
        assertThat(ddl).contains("idx_ana_sample_detail_field_sample");
    }
}
