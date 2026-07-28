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
    void ddlSupportsTransactionCodeMigrationCommands() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql")).toLowerCase();

        assertThat(ddl).contains("command_type varchar(32) not null default 'time_range'");
        assertThat(ddl).contains("check (command_type in ('time_range','sql','tran_code'))");
        assertThat(ddl).contains("tran_codes text");
        assertThat(ddl).contains("sample_size integer");
        assertThat(ddl).contains("add column if not exists tran_codes text");
        assertThat(ddl).contains("add column if not exists sample_size integer");
        assertThat(ddl).contains("tran_code varchar(32)");
        assertThat(ddl).contains("add column if not exists tran_code varchar(32)");
        assertThat(ddl).contains("drop constraint if exists ck_ana_migration_command_tran_code_parameters");
        assertThat(ddl).contains("constraint ck_ana_migration_command_tran_code_parameters");
        assertThat(ddl).contains("command_type <> 'tran_code' or (tran_codes is not null and btrim(tran_codes) <> '' and sample_size is not null and sample_size > 0 and lookback_days is not null and lookback_days > 0)");
    }

    @Test
    void manualReportExportScriptCreatesTheReportExportTables() throws Exception {
        String rawSql = Files.readString(Path.of("db/manual-create-ana-report-export.sql"), StandardCharsets.UTF_8);
        String sql = rawSql.toLowerCase();

        assertThat(sql).contains("create table if not exists ana_report_export_command");
        assertThat(sql).contains("create table if not exists ana_report_export_summary");
        List<String> summaryColumns = List.of(
                "field_pass_transaction_count bigint not null default 0",
                "comparison_pass_rate numeric(12,8) not null default 0",
                "transaction_issue_count bigint not null default 0",
                "field_issue_count bigint not null default 0",
                "issue_total_count bigint not null default 0",
                "duplicate_issue_count bigint not null default 0");
        summaryColumns.forEach(column -> assertThat(sql).contains(column));
        List<String> summaryMigrationColumns = List.of(
                "alter table ana_report_export_summary\nadd column if not exists field_pass_transaction_count bigint not null default 0;",
                "alter table ana_report_export_summary\nadd column if not exists comparison_pass_rate numeric(12,8) not null default 0;",
                "alter table ana_report_export_summary\nadd column if not exists transaction_issue_count bigint not null default 0;",
                "alter table ana_report_export_summary\nadd column if not exists field_issue_count bigint not null default 0;",
                "alter table ana_report_export_summary\nadd column if not exists issue_total_count bigint not null default 0;",
                "alter table ana_report_export_summary\nadd column if not exists duplicate_issue_count bigint not null default 0;");
        summaryMigrationColumns.forEach(column -> assertThat(sql).contains(column));
        assertThat(rawSql).contains("comment on column ana_report_export_summary.success_rate is '成功率';");
        assertThat(rawSql).contains("comment on column ana_report_export_summary.field_pass_transaction_count is '二者均成功且无字段差异交易数';");
        assertThat(rawSql).contains("comment on column ana_report_export_summary.comparison_pass_rate is '比对通过率';");
        assertThat(rawSql).contains("comment on column ana_report_export_summary.transaction_issue_count is '交易级差异总数';");
        assertThat(rawSql).contains("comment on column ana_report_export_summary.field_issue_count is '字段级差异总数';");
        assertThat(rawSql).contains("comment on column ana_report_export_summary.issue_total_count is '问题总数';");
        assertThat(rawSql).contains("comment on column ana_report_export_summary.duplicate_issue_count is '重复问题数';");
        assertThat(sql).contains("idx_ana_report_export_command_status");
        assertThat(sql).contains("idx_ana_report_export_summary_batch");
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
    void ddlContainsTransactionDiffTrackingExportTableAndRemediationColumns() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8);
        String ddlLower = ddl.toLowerCase().replace("\r\n", "\n");
        String createTable = "create table if not exists ana_tran_diff_tracking_export";
        int tableStart = ddlLower.indexOf(createTable);
        int tableEnd = ddlLower.indexOf(");", tableStart);

        assertThat(tableStart).isGreaterThanOrEqualTo(0);
        assertThat(tableEnd).isGreaterThan(tableStart);
        String tableBlock = ddlLower.substring(tableStart, tableEnd + 2);
        List<String> expectedColumns = List.of(
                "export_id bigserial primary key",
                "export_timestamp timestamp not null",
                "source_batch_id varchar(64) not null",
                "business_date varchar(8) not null",
                "row_no bigint not null",
                "service_code varchar(200) not null",
                "orig_error_code varchar(64)",
                "dest_error_code varchar(64)",
                "tran_code varchar(32)",
                "tran_name varchar(200)",
                "module_name varchar(100)",
                "orig_error_desc varchar(500)",
                "dest_error_desc varchar(500)",
                "transaction_owner varchar(100)",
                "tran_seq_no varchar(64)",
                "problem_level varchar(100)",
                "registration_date varchar(8)",
                "field_name varchar(500)",
                "problem_description text",
                "problem_type varchar(100)",
                "preliminary_analysis text",
                "final_solution text",
                "resolution_date varchar(8)",
                "coordination_required varchar(100)",
                "resolver varchar(100)",
                "defect_fix_date varchar(8)",
                "created_at timestamp not null default current_timestamp",
                "updated_at timestamp not null default current_timestamp");
        expectedColumns.forEach(column -> assertThat(tableBlock).contains(column));

        List<String> columnNames = List.of(
                "export_id", "export_timestamp", "source_batch_id", "business_date", "row_no", "service_code",
                "orig_error_code", "dest_error_code", "tran_code", "tran_name", "module_name", "orig_error_desc",
                "dest_error_desc", "transaction_owner", "tran_seq_no", "problem_level", "registration_date",
                "field_name", "problem_description", "problem_type", "preliminary_analysis", "final_solution",
                "resolution_date", "coordination_required", "resolver", "defect_fix_date", "created_at", "updated_at");
        assertThat(ddl).containsPattern("comment on table ana_tran_diff_tracking_export is '[\\p{IsHan}][^']*';");
        columnNames.forEach(column -> assertThat(ddl).containsPattern(
                "comment on column ana_tran_diff_tracking_export\\." + column + " is '[\\p{IsHan}][^']*';"));

        assertThat(ddlLower).contains("""
                create unique index if not exists uk_ana_tran_diff_tracking_export_batch_issue
                on ana_tran_diff_tracking_export(source_batch_id, service_code, orig_error_code, dest_error_code);""");
        assertThat(ddlLower).contains("""
                create index if not exists idx_ana_tran_diff_tracking_export_time
                on ana_tran_diff_tracking_export(export_timestamp desc);""");
    }

    @Test
    void ddlContainsFieldDiffTrackingExportTable() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8);
        String ddlLower = ddl.toLowerCase().replace("\r\n", "\n");
        String createTable = "create table if not exists ana_field_diff_tracking_export";
        int tableStart = ddlLower.indexOf(createTable);
        int tableEnd = ddlLower.indexOf(");", tableStart);

        assertThat(tableStart).isGreaterThanOrEqualTo(0);
        assertThat(tableEnd).isGreaterThan(tableStart);
        String tableBlock = ddlLower.substring(tableStart, tableEnd + 2);
        List<String> expectedColumns = List.of(
                "export_id bigserial primary key",
                "export_timestamp timestamp not null",
                "source_batch_id varchar(64) not null",
                "business_date varchar(8) not null",
                "row_no bigint not null",
                "service_code varchar(200) not null",
                "tran_code varchar(32)",
                "tran_name varchar(200)",
                "module_name varchar(100)",
                "sop_field_name varchar(200)",
                "soap_field_name varchar(200)",
                "bizjson_field_name varchar(200)",
                "field_cn_name varchar(200)",
                "mapping_status varchar(32)",
                "orig_field_value varchar(2000)",
                "dest_field_value varchar(2000)",
                "transaction_owner varchar(100)",
                "tran_seq_no varchar(64)",
                "problem_level varchar(100)",
                "registration_date varchar(8)",
                "field_name varchar(500)",
                "problem_description text",
                "problem_type varchar(100)",
                "preliminary_analysis text",
                "final_solution text",
                "resolution_date varchar(8)",
                "coordination_required varchar(100)",
                "resolver varchar(100)",
                "defect_fix_date varchar(8)",
                "created_at timestamp not null default current_timestamp",
                "updated_at timestamp not null default current_timestamp");
        expectedColumns.forEach(column -> assertThat(tableBlock).contains(column));

        List<String> remediationColumns = List.of(
                "transaction_owner", "tran_seq_no", "problem_level", "registration_date", "field_name",
                "problem_description", "problem_type", "preliminary_analysis", "final_solution", "resolution_date",
                "coordination_required", "resolver", "defect_fix_date");
        remediationColumns.forEach(column -> assertThat(tableBlock).doesNotContainPattern(column + "[^,]*not null"));

        List<String> columnNames = List.of(
                "export_id", "export_timestamp", "source_batch_id", "business_date", "row_no", "service_code",
                "tran_code", "tran_name", "module_name", "sop_field_name", "soap_field_name", "bizjson_field_name",
                "field_cn_name", "mapping_status", "orig_field_value", "dest_field_value", "transaction_owner",
                "tran_seq_no", "problem_level", "registration_date", "field_name", "problem_description",
                "problem_type", "preliminary_analysis", "final_solution", "resolution_date", "coordination_required",
                "resolver", "defect_fix_date", "created_at", "updated_at");
        assertThat(ddl).containsPattern("comment on table ana_field_diff_tracking_export is '[^']*[\\p{IsHan}][^']*';");
        columnNames.forEach(column -> assertThat(ddl).containsPattern(
                "comment on column ana_field_diff_tracking_export\\." + column + " is '[^']*[\\p{IsHan}][^']*';"));

        assertThat(ddlLower).contains("""
                create index if not exists idx_ana_field_diff_tracking_export_source
                on ana_field_diff_tracking_export(source_batch_id, service_code, soap_field_name);""");
        assertThat(ddlLower).contains("""
                create unique index if not exists uk_ana_field_diff_tracking_export_batch_issue
                on ana_field_diff_tracking_export(source_batch_id, service_code, issue_key);""");
        assertThat(ddlLower).contains("""
                create index if not exists idx_ana_field_diff_tracking_export_time
                on ana_field_diff_tracking_export(export_timestamp desc);""");
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

    @Test
    void ddlContainsBatchDomainReportTablesAndIndexes() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8).toLowerCase();

        assertThat(ddl).contains("create table if not exists ana_batch_domain_report_command");
        assertThat(ddl).contains("create table if not exists ana_batch_domain_transaction_stat");
        assertThat(ddl).contains("create table if not exists ana_batch_domain_field_stat");
        assertThat(ddl).contains("create table if not exists ana_batch_report_gap");
        assertThat(ddl).contains("batch_id varchar(64) not null");
        assertThat(ddl).contains("check (status in ('pending','running','succeeded','failed'))");
        assertThat(ddl).contains("module_name varchar(100) not null");
        assertThat(ddl).contains("covered_service_count bigint not null default 0");
        assertThat(ddl).contains("sent_transaction_count bigint not null default 0");
        assertThat(ddl).contains("comp_result_1_count bigint not null default 0");
        assertThat(ddl).contains("comp_result_2_count bigint not null default 0");
        assertThat(ddl).contains("comp_result_3_count bigint not null default 0");
        assertThat(ddl).contains("comp_result_4_count bigint not null default 0");
        assertThat(ddl).contains("comp_result_8_count bigint not null default 0");
        assertThat(ddl).contains("total_field_count bigint not null default 0");
        assertThat(ddl).contains("diff_field_count bigint not null default 0");
        assertThat(ddl).contains("no_diff_field_count bigint not null default 0");
        assertThat(ddl).contains("gap_type varchar(32) not null");
        assertThat(ddl).contains("service_code varchar(200)");
        assertThat(ddl).contains("message_type varchar(32)");
        assertThat(ddl).contains("field_key varchar(500)");
        assertThat(ddl).contains("affected_count bigint not null default 0");
        assertThat(ddl).contains("constraint uk_ana_batch_domain_transaction_stat unique (batch_id, module_name)");
        assertThat(ddl).contains("constraint uk_ana_batch_domain_field_stat unique (batch_id, module_name)");
        assertThat(ddl).contains("idx_ana_batch_report_gap_batch_type");
        assertThat(ddl).contains("idx_msg_flow_log_response_report_time_trans");
        assertThat(ddl).contains("on msg_flow_log_response(response_time, trans_id)");
    }

    @Test
    void ddlContainsReportExportCommandAndSummaryTables() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8);
        String ddlLower = ddl.toLowerCase().replace("\r\n", "\n");

        assertThat(ddlLower).contains("create table if not exists ana_report_export_command");
        assertThat(ddlLower).contains("command_id bigserial primary key");
        assertThat(ddlLower).contains("batch_id varchar(64) not null");
        assertThat(ddlLower).contains("report_date varchar(8) not null");
        assertThat(ddlLower).contains("status varchar(32) not null default 'pending'");
        assertThat(ddlLower).contains("constraint uk_ana_report_export_command_batch unique (batch_id)");
        assertThat(ddlLower).contains("constraint ck_ana_report_export_command_status");
        assertThat(ddlLower).contains("check (status in ('pending','running','succeeded','failed'))");
        assertThat(ddlLower).contains("started_time timestamp");
        assertThat(ddlLower).contains("ended_time timestamp");
        assertThat(ddlLower).contains("error_message varchar(4000)");

        assertThat(ddlLower).contains("create table if not exists ana_report_export_summary");
        assertThat(ddlLower).contains("summary_id bigserial primary key");
        assertThat(ddlLower).contains("module_name varchar(100) not null");
        List<String> summaryColumns = List.of(
                "covered_528_interface_count bigint not null default 0",
                "sent_transaction_count bigint not null default 0",
                "comp_result_1_count bigint not null default 0",
                "comp_result_2_count bigint not null default 0",
                "comp_result_3_count bigint not null default 0",
                "comp_result_4_count bigint not null default 0",
                "comp_result_8_count bigint not null default 0",
                "diff_528_field_count bigint not null default 0",
                "success_rate numeric(12,8) not null default 0",
                "field_pass_transaction_count bigint not null default 0",
                "comparison_pass_rate numeric(12,8) not null default 0",
                "transaction_issue_count bigint not null default 0",
                "field_issue_count bigint not null default 0",
                "issue_total_count bigint not null default 0",
                "duplicate_issue_count bigint not null default 0",
                "constraint uk_ana_report_export_summary unique (batch_id, module_name)");
        summaryColumns.forEach(column -> assertThat(ddlLower).contains(column));
        List<String> summaryMigrationColumns = List.of(
                "add column if not exists field_pass_transaction_count bigint not null default 0",
                "add column if not exists comparison_pass_rate numeric(12,8) not null default 0",
                "add column if not exists transaction_issue_count bigint not null default 0",
                "add column if not exists field_issue_count bigint not null default 0",
                "add column if not exists issue_total_count bigint not null default 0",
                "add column if not exists duplicate_issue_count bigint not null default 0");
        summaryMigrationColumns.forEach(column -> assertThat(ddlLower).contains(column));

        List<String> commandColumns = List.of(
                "command_id", "batch_id", "report_date", "status", "started_time", "ended_time",
                "error_message", "created_time", "updated_at");
        List<String> summaryColumnNames = List.of(
                "summary_id", "batch_id", "report_date", "module_name", "covered_528_interface_count",
                "sent_transaction_count", "comp_result_1_count", "comp_result_2_count", "comp_result_3_count",
                "comp_result_4_count", "comp_result_8_count", "diff_528_field_count", "success_rate",
                "field_pass_transaction_count", "comparison_pass_rate", "transaction_issue_count",
                "field_issue_count", "issue_total_count", "duplicate_issue_count",
                "created_time", "updated_at");
        assertThat(ddl).containsPattern("comment on table ana_report_export_command is '[\\p{IsHan}][^']*';");
        assertThat(ddl).containsPattern("comment on table ana_report_export_summary is '[\\p{IsHan}][^']*';");
        commandColumns.forEach(column -> assertThat(ddl).containsPattern(
                "comment on column ana_report_export_command\\." + column + " is '[\\p{IsHan}][^']*';"));
        summaryColumnNames.forEach(column -> assertThat(ddl).containsPattern(
                "comment on column ana_report_export_summary\\." + column + " is '[\\p{IsHan}][^']*';"));
        assertThat(ddl).contains("comment on column ana_report_export_summary.success_rate is '成功率';");
        assertThat(ddl).contains("comment on column ana_report_export_summary.field_pass_transaction_count is '二者均成功且无字段差异交易数';");
        assertThat(ddl).contains("comment on column ana_report_export_summary.comparison_pass_rate is '比对通过率';");
        assertThat(ddl).contains("comment on column ana_report_export_summary.transaction_issue_count is '交易级差异总数';");
        assertThat(ddl).contains("comment on column ana_report_export_summary.field_issue_count is '字段级差异总数';");
        assertThat(ddl).contains("comment on column ana_report_export_summary.issue_total_count is '问题总数';");
        assertThat(ddl).contains("comment on column ana_report_export_summary.duplicate_issue_count is '重复问题数';");

        assertThat(ddlLower).contains("""
                create index if not exists idx_ana_report_export_command_status
                on ana_report_export_command(status, created_time desc);""");
        assertThat(ddlLower).contains("""
                create index if not exists idx_ana_report_export_summary_batch
                on ana_report_export_summary(batch_id, module_name);""");
    }
    @Test
    void ddlContainsDiffIssueLedgerAndComments() throws Exception {
        String ddl = Files.readString(Path.of("db/ddl.sql"), StandardCharsets.UTF_8);
        String ddlLower = ddl.toLowerCase().replace("\r\n", "\n");

        assertThat(ddlLower).contains("create table if not exists ana_diff_issue");
        assertThat(ddlLower).contains("issue_key varchar(600) not null unique");
        assertThat(ddlLower).contains("check (issue_level in ('transaction','field'))");
        assertThat(ddlLower).contains("issue_level varchar(16) not null");
        assertThat(ddlLower).contains("issue_status varchar(16) not null default 'open'");
        assertThat(ddlLower).contains("check (issue_status in ('open','resolved','ignored'))");
        assertThat(ddlLower).contains("first_seen_date date not null");
        assertThat(ddlLower).contains("last_seen_date date not null");
        assertThat(ddlLower).contains("first_seen_batch_id varchar(64) not null");
        assertThat(ddlLower).contains("last_seen_batch_id varchar(64) not null");
        assertThat(ddlLower).contains("occurrence_batch_count bigint not null default 1");
        assertThat(ddlLower).contains("idx_ana_diff_issue_status_last_seen");
        assertThat(ddlLower).contains("idx_ana_diff_issue_service_field");

        List<String> issueColumns = List.of(
                "issue_id", "issue_key", "issue_level", "service_code", "tran_code", "tran_name", "module_name",
                "transaction_owner", "orig_error_code", "dest_error_code", "normalized_source_field_name", "problem_type",
                "problem_description", "preliminary_analysis", "final_solution", "issue_status", "coordination_required",
                "resolver", "resolution_date", "defect_fix_date", "first_seen_date", "last_seen_date", "first_seen_batch_id",
                "last_seen_batch_id", "occurrence_batch_count", "created_at", "updated_at");
        assertThat(ddl).containsPattern("comment on table ana_diff_issue is '[\\p{IsHan}][^']*';");
        issueColumns.forEach(column -> assertThat(ddl).containsPattern(
                "comment on column ana_diff_issue\\." + column + " is '[\\p{IsHan}][^']*';"));

        List<String> trackingColumns = List.of(
                "issue_id bigint", "issue_key varchar(600)", "historical_occurrence_count bigint not null default 0",
                "first_seen_date date", "previous_seen_date date");
        List<String> trackingColumnNames = List.of(
                "issue_id", "issue_key", "historical_occurrence_count", "first_seen_date", "previous_seen_date");
        List<String> trackingTables = List.of("ana_tran_diff_tracking_export", "ana_field_diff_tracking_export");
        trackingTables.forEach(table -> {
            int tableStart = ddlLower.indexOf("create table if not exists " + table);
            int tableEnd = ddlLower.indexOf(");", tableStart);
            assertThat(tableStart).isGreaterThanOrEqualTo(0);
            assertThat(tableEnd).isGreaterThan(tableStart);
            String tableBlock = ddlLower.substring(tableStart, tableEnd + 2);
            trackingColumns.forEach(column -> assertThat(tableBlock).contains(column));
            assertThat(tableBlock).contains("issue_id bigint");
            trackingColumnNames.forEach(column -> assertThat(ddl).containsPattern(
                    "comment on column " + table + "\\." + column + " is '[\\p{IsHan}][^']*';"));
        });

        assertThat(ddlLower).doesNotContain("foreign key (issue_id) references ana_diff_issue(issue_id)");
        assertThat(ddl).contains("稳定业务键快照");
        assertThat(ddl).contains("本批次前历史出现批次数");
        assertThat(ddl).contains("问题首次出现日期快照");
        assertThat(ddl).contains("本次前最近出现日期快照");
    }
}
