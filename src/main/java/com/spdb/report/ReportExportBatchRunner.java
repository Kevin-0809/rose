package com.spdb.report;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import java.util.TreeMap;
import java.util.TreeSet;

/** Materializes one report-export batch from the current replay comparison tables. */
@Component
public class ReportExportBatchRunner {
    private static final String UNCONFIGURED_MODULE = "未配置领域";

    private static final int TRANSACTION_FETCH_SIZE = 500;

    private final NamedParameterJdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate transactionTemplate;
    private final Executor transactionDetailExecutor;
    private final DiffIssueLedgerService issueLedgerService;

    public ReportExportBatchRunner(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, Runnable::run);
    }

    @Autowired
    public ReportExportBatchRunner(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                   @Qualifier("reportExportTransactionDetailExecutor") Executor transactionDetailExecutor) {
        this.jdbc = jdbc;
        this.dataSource = jdbc.getJdbcTemplate().getDataSource();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionDetailExecutor = transactionDetailExecutor;
        this.issueLedgerService = new DiffIssueLedgerService(jdbc);
    }

    public void run(String batchId, String reportDate, LocalDateTime exportTime) {
        try {
            Map<String, Catalog> catalogs = transactionTemplate.execute(status -> runInTransaction(batchId, reportDate, exportTime));
            streamTransactionDetails(batchId, reportDate, exportTime, catalogs);
            issueLedgerService.materializeBatch(batchId, LocalDate.parse(reportDate, DateTimeFormatter.BASIC_ISO_DATE));
        } catch (RuntimeException exception) {
            cleanupBatchArtifacts(batchId);
            throw exception;
        }
    }

    private Map<String, Catalog> runInTransaction(String batchId, String reportDate, LocalDateTime exportTime) {
        Map<String, Catalog> catalogs = catalogs();
        List<FieldMapping> fieldMappings = fieldMappings();
        List<Tran> transactions = transactions();
        List<Field> fields = fields();
        insertSummaries(batchId, reportDate, transactions, fields, catalogs);
        insertFieldDetails(batchId, reportDate, exportTime, fields, catalogs, fieldMappings);
        return catalogs;
    }

    private Map<String, Catalog> catalogs() {
        Map<String, Catalog> result = new LinkedHashMap<>();
        jdbc.getJdbcTemplate().query("select catalog_id, tran_code, service_code, tran_name, module_name, owner from ana_tran_catalog order by catalog_id", (RowCallbackHandler)
                rs -> result.putIfAbsent(key(rs.getString("service_code")), new Catalog(rs.getString("tran_code"), rs.getString("tran_name"), rs.getString("module_name"), rs.getString("owner"))));
        return result;
    }

    private List<FieldMapping> fieldMappings() {
        List<FieldMapping> result = new ArrayList<>();
        jdbc.getJdbcTemplate().query("select tran_code, service_code, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name from ana_field_mapping", (RowCallbackHandler)
                rs -> result.add(new FieldMapping(rs.getString("tran_code"), rs.getString("service_code"), rs.getString("sop_field_name"), rs.getString("soap_field_name"), rs.getString("bizjson_field_name"), rs.getString("field_cn_name"))));
        return result;
    }

    private List<Tran> transactions() {
        List<Tran> result = new ArrayList<>();
        jdbc.getJdbcTemplate().query("select mesg_seq, orig_cdate, conv_index, conv_cindex, dest_trcd, comp_result from tss_tran_comp", (RowCallbackHandler)
                rs -> result.add(new Tran(rs.getString("mesg_seq"), rs.getString("orig_cdate"), rs.getInt("conv_index"), rs.getInt("conv_cindex"), rs.getString("dest_trcd"), rs.getString("comp_result"))));
        return result;
    }

    private List<Field> fields() {
        List<Field> result = new ArrayList<>();
        jdbc.getJdbcTemplate().query("select mesg_seq, orig_cdate, conv_index, conv_cindex, field_index, dest_trcd, orig_field_name, orig_field_value, dest_field_name, dest_field_value from tss_field_comp", (RowCallbackHandler)
                rs -> result.add(new Field(rs.getString("mesg_seq"), rs.getString("orig_cdate"), rs.getInt("conv_index"), rs.getInt("conv_cindex"), rs.getInt("field_index"), rs.getString("dest_trcd"), rs.getString("orig_field_name"), rs.getString("orig_field_value"), rs.getString("dest_field_name"), rs.getString("dest_field_value"))));
        return result;
    }

    private void insertSummaries(String batchId, String reportDate, List<Tran> transactions, List<Field> fields, Map<String, Catalog> catalogs) {
        Map<String, List<Tran>> byModule = new TreeMap<>();
        for (Tran tran : transactions) byModule.computeIfAbsent(module(service(tran.destTrcd()), catalogs), ignored -> new ArrayList<>()).add(tran);
        Map<String, Set<String>> fieldNames = new LinkedHashMap<>();
        for (Field field : fields) fieldNames.computeIfAbsent(module(service(field.destTrcd()), catalogs), ignored -> new TreeSet<>()).add(normalizedField(field.origFieldName()));
        for (String module : union(byModule.keySet(), fieldNames.keySet())) {
            List<Tran> rows = byModule.getOrDefault(module, List.of());
            long one = count(rows, "1"), two = count(rows, "2"), three = count(rows, "3"), four = count(rows, "4"), eight = count(rows, "8");
            long total = rows.size();
            MapSqlParameterSource p = params(batchId, reportDate).addValue("module", module)
                    .addValue("covered", rows.stream().map(row -> service(row.destTrcd())).filter(value -> !value.isBlank()).distinct().count())
                    .addValue("total", total).addValue("one", one).addValue("two", two).addValue("three", three).addValue("four", four).addValue("eight", eight)
                    .addValue("rate", total == 0 ? java.math.BigDecimal.ZERO : java.math.BigDecimal.valueOf(three + four).divide(java.math.BigDecimal.valueOf(total), 8, java.math.RoundingMode.HALF_UP))
                    .addValue("fieldCount", fieldNames.getOrDefault(module, Set.of()).size());
            jdbc.update("""
                    insert into ana_report_export_summary(batch_id, report_date, module_name, covered_528_interface_count,
                      sent_transaction_count, comp_result_1_count, comp_result_2_count, comp_result_3_count,
                      comp_result_4_count, comp_result_8_count, success_rate, diff_528_field_count)
                    values (:batchId, :reportDate, :module, :covered, :total, :one, :two, :three, :four, :eight, :rate, :fieldCount)
                    """, p);
        }
    }

    private void streamTransactionDetails(String batchId, String reportDate, LocalDateTime exportTime, Map<String, Catalog> catalogs) {
        try {
            AtomicLong rowNo = new AtomicLong();
            List<CompletableFuture<Void>> tasks = transactionServiceCodes().stream()
                    .map(service -> CompletableFuture.runAsync(() -> streamTransactionService(batchId, reportDate, exportTime, service, catalogs, rowNo), transactionDetailExecutor))
                    .toList();
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        } catch (RuntimeException exception) {
            jdbc.update("delete from ana_tran_diff_tracking_export where source_batch_id = :batchId",
                    new MapSqlParameterSource("batchId", batchId));
            throw exception;
        }
    }

    private void cleanupBatchArtifacts(String batchId) {
        transactionTemplate.executeWithoutResult(status -> {
            MapSqlParameterSource params = new MapSqlParameterSource("batchId", batchId);
            jdbc.update("delete from ana_tran_diff_tracking_export where source_batch_id = :batchId", params);
            jdbc.update("delete from ana_field_diff_tracking_export where source_batch_id = :batchId", params);
            jdbc.update("delete from ana_report_export_summary where batch_id = :batchId", params);
        });
    }

    private Set<String> transactionServiceCodes() {
        Set<String> services = new TreeSet<>();
        jdbc.getJdbcTemplate().query("""
                select distinct case when position('&' in coalesce(dest_trcd, '')) > 0
                    then substring(dest_trcd, 1, position('&' in dest_trcd) - 1)
                    else coalesce(dest_trcd, '') end as service_code
                from tss_tran_comp
                """, (RowCallbackHandler) rs -> services.add(rs.getString("service_code")));
        return services;
    }

    private void streamTransactionService(String batchId, String reportDate, LocalDateTime exportTime, String normalizedService,
                                          Map<String, Catalog> catalogs, AtomicLong rowNo) {
        Set<String> issues = new TreeSet<>();
        String insert = transactionDetailInsertSql();
        String sql = """
                select tc.mesg_seq, tc.orig_cdate, tc.conv_index, tc.conv_cindex, tc.dest_trcd, tc.comp_result,
                       rc.mesg_seq as ret_mesg_seq, rc.service_code as ret_service_code, rc.orig_error_code, rc.orig_error_desc,
                       rc.dest_error_code, rc.dest_error_desc
                from tss_tran_comp tc
                left join tss_retcode_comp rc on rc.mesg_seq = tc.mesg_seq and rc.orig_cdate = tc.orig_cdate
                where case when position('&' in coalesce(tc.dest_trcd, '')) > 0
                    then substring(tc.dest_trcd, 1, position('&' in tc.dest_trcd) - 1)
                    else coalesce(tc.dest_trcd, '') end = ?
                order by tc.mesg_seq, tc.conv_index, tc.conv_cindex
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            statement.setFetchSize(TRANSACTION_FETCH_SIZE);
            statement.setString(1, normalizedService);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) streamTransactionRow(rs, insert, batchId, reportDate, exportTime, catalogs, rowNo, issues);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to stream transaction details for service " + normalizedService, exception);
        }
    }

    private void streamTransactionRow(ResultSet rs, String insert, String batchId, String reportDate, LocalDateTime exportTime,
                                      Map<String, Catalog> catalogs, AtomicLong rowNo, Set<String> issues) throws SQLException {
        Tran row = new Tran(rs.getString("mesg_seq"), rs.getString("orig_cdate"), rs.getInt("conv_index"), rs.getInt("conv_cindex"), rs.getString("dest_trcd"), rs.getString("comp_result"));
        Retcode retcode = rs.getString("ret_mesg_seq") == null ? null
                : new Retcode(rs.getString("ret_service_code"), rs.getString("orig_error_code"), rs.getString("orig_error_desc"), rs.getString("dest_error_code"), rs.getString("dest_error_desc"));
        if (!shouldExportTransaction(row, retcode)) return;
        String issue = transactionStatus(row.compResult()) == null
                ? retcode == null ? "TRAN|" + text(row.destTrcd()) + "|" + row.compResult() : "RET|" + service(retcode.serviceCode()) + "|" + text(retcode.origCode()) + "|" + text(retcode.destCode())
                : "STATUS|" + row.compResult() + "|" + text(row.destTrcd());
        if (!issues.add(issue)) return;
        insertTransactionDetail(insert, batchId, reportDate, exportTime, rowNo.incrementAndGet(), row, retcode, catalogs);
    }

    private void insertTransactionDetail(String insert, String batchId, String reportDate, LocalDateTime exportTime, long rowNo,
                                         Tran tran, Retcode retcode, Map<String, Catalog> catalogs) {
        String status = transactionStatus(tran.compResult());
        String service = retcode == null ? service(tran.destTrcd()) : service(retcode.serviceCode());
        Catalog catalog = catalogs.get(key(service));
        jdbc.update(insert,
                params(batchId, reportDate).addValue("time", Timestamp.valueOf(exportTime)).addValue("date", reportDate).addValue("rowNo", rowNo)
                        .addValue("service", service).addValue("origCode", status == null ? retcode == null ? null : retcode.origCode() : status).addValue("destCode", status == null ? retcode == null ? null : retcode.destCode() : status)
                        .addValue("issueKey", transactionIssueKey(service, status == null ? retcode == null ? null : retcode.origCode() : status, status == null ? retcode == null ? null : retcode.destCode() : status))
                        .addValue("tranCode", catalog == null ? service : catalog.tranCode()).addValue("tranName", catalog == null ? null : catalog.tranName())
                        .addValue("module", catalog == null ? UNCONFIGURED_MODULE : moduleName(catalog)).addValue("origDesc", status == null ? retcode == null ? null : retcode.origDesc() : status)
                        .addValue("destDesc", status == null ? retcode == null ? null : retcode.destDesc() : status).addValue("owner", catalog == null ? null : catalog.owner()).addValue("seq", tran.mesgSeq())
                        .addValue("field", status == null ? transactionFieldName(retcode) : status).addValue("description", transactionDescription(retcode)));
    }

    private String transactionDetailInsertSql() {
        return """
                merge into ana_tran_diff_tracking_export as target
                using (
                    select cast(:time as timestamp) as export_timestamp, cast(:batchId as varchar(64)) as source_batch_id,
                    cast(:date as varchar(8)) as business_date, cast(:rowNo as bigint) as row_no,
                    cast(:service as varchar(200)) as service_code, cast(:origCode as varchar(64)) as orig_error_code,
                    cast(:destCode as varchar(64)) as dest_error_code, cast(:tranCode as varchar(32)) as tran_code,
                    cast(:tranName as varchar(200)) as tran_name, cast(:module as varchar(100)) as module_name,
                    cast(:origDesc as varchar(500)) as orig_error_desc, cast(:destDesc as varchar(500)) as dest_error_desc,
                    cast(:owner as varchar(100)) as transaction_owner, cast(:seq as varchar(64)) as tran_seq_no,
                    '交易级' as problem_level, cast(:date as varchar(8)) as registration_date,
                    cast(:field as varchar(500)) as field_name, cast(:description as varchar(2000)) as problem_description,
                    cast(:issueKey as varchar(600)) as issue_key
                ) as source
                on (target.source_batch_id = source.source_batch_id
                    and target.service_code = source.service_code
                    and target.orig_error_code = source.orig_error_code
                    and target.dest_error_code = source.dest_error_code)
                when not matched then
                    insert (export_timestamp, source_batch_id, business_date, row_no, service_code, orig_error_code,
                    dest_error_code, tran_code, tran_name, module_name, orig_error_desc, dest_error_desc, transaction_owner,
                    tran_seq_no, problem_level, registration_date, field_name, problem_description, issue_key)
                    values (source.export_timestamp, source.source_batch_id, source.business_date, source.row_no, source.service_code,
                    source.orig_error_code, source.dest_error_code, source.tran_code, source.tran_name, source.module_name,
                    source.orig_error_desc, source.dest_error_desc, source.transaction_owner, source.tran_seq_no,
                    source.problem_level, source.registration_date, source.field_name, source.problem_description, source.issue_key)
                """;
    }

    private void insertFieldDetails(String batchId, String reportDate, LocalDateTime exportTime, List<Field> fields, Map<String, Catalog> catalogs, List<FieldMapping> fieldMappings) {
        Map<String, Field> grouped = new TreeMap<>();
        fields.stream().sorted(FIELD_ORDER).forEach(row -> grouped.putIfAbsent(service(row.destTrcd()) + "|" + normalizedField(row.origFieldName()) + "|" + text(row.destFieldName()), row));
        long rowNo = 0;
        for (Field field : grouped.values()) {
            String service = service(field.destTrcd());
            Catalog catalog = catalogs.get(key(service));
            String normalized = normalizedField(field.origFieldName());
            FieldMapping mapping = fieldMapping(fieldMappings, service, normalized);
            String sop = mapping == null ? null : mapping.sopFieldName();
            String soap = mapping == null ? normalized : mapping.soapFieldName();
            String bizjson = mapping == null ? null : mapping.bizjsonFieldName();
            String fieldCn = mapping == null ? null : mapping.fieldCnName();
            jdbc.update("""
                    insert into ana_field_diff_tracking_export(export_timestamp, source_batch_id, business_date, row_no,
                    service_code, tran_code, tran_name, module_name, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name, mapping_status, orig_field_value,
                    dest_field_value, transaction_owner, tran_seq_no, problem_level, registration_date, field_name, problem_description, issue_key)
                    values (:time,:batchId,:date,:rowNo,:service,:tranCode,:tranName,:module,:sop,:soap,:bizjson,:fieldCn,:mappingStatus,:orig,:dest,:owner,:seq,'字段级',:date,:field,:description,:issueKey)""",
                    params(batchId, reportDate).addValue("time", Timestamp.valueOf(exportTime)).addValue("date", reportDate).addValue("rowNo", ++rowNo)
                            .addValue("service", service).addValue("tranCode", catalog == null ? service : catalog.tranCode()).addValue("tranName", catalog == null ? null : catalog.tranName())
                            .addValue("module", catalog == null ? UNCONFIGURED_MODULE : moduleName(catalog)).addValue("sop", sop).addValue("soap", soap).addValue("bizjson", bizjson).addValue("fieldCn", fieldCn)
                            .addValue("mappingStatus", mapping == null ? "UNMAPPED" : "MAPPED").addValue("orig", field.origValue()).addValue("dest", field.destValue())
                            .addValue("owner", catalog == null ? null : catalog.owner()).addValue("seq", field.mesgSeq()).addValue("field", joinedFieldNames(sop, soap, bizjson, fieldCn))
                            .addValue("description", "528：" + valueStatus(field.origValue()) + "；CCBS：" + valueStatus(field.destValue()))
                            .addValue("issueKey", fieldIssueKey(service, normalized)));
        }
    }

    private static final Comparator<Field> FIELD_ORDER = Comparator.comparing(Field::mesgSeq, Comparator.nullsFirst(String::compareTo)).thenComparingInt(Field::convIndex).thenComparingInt(Field::convCindex).thenComparingInt(Field::fieldIndex);
    private static long count(List<Tran> rows, String result) { return rows.stream().filter(row -> result.equals(row.compResult())).count(); }
    private static String service(String value) { int index = text(value).indexOf('&'); return index < 0 ? text(value) : value.substring(0, index); }
    private static String normalizedField(String value) { String[] parts = text(value).split("\\."); return parts.length > 1 ? parts[0] + "." + parts[1] : text(value); }
    private static FieldMapping fieldMapping(List<FieldMapping> mappings, String service, String soapFieldName) {
        return mappings.stream().filter(mapping -> key(mapping.serviceCode()).equals(key(service))).filter(mapping -> key(mapping.soapFieldName()).equals(key(soapFieldName)))
                .findFirst().orElse(null);
    }
    private static String transactionDescription(Retcode retcode) { return "528错误码：" + text(retcode == null ? null : retcode.origCode()) + "；描述：" + text(retcode == null ? null : retcode.origDesc()) + "；CCBS错误码：" + text(retcode == null ? null : retcode.destCode()) + "；CCBS错误描述：" + text(retcode == null ? null : retcode.destDesc()); }
    private static boolean shouldExportTransaction(Tran tran, Retcode retcode) { return !"4".equals(tran.compResult()) && (transactionStatus(tran.compResult()) != null || !bothSucceeded(retcode)); }
    private static String transactionStatus(String result) { return switch (result) { case "0" -> "未比对"; case "5" -> "忽略比对"; case "6" -> "比对中"; case "7" -> "对比异常"; default -> null; }; }
    private static boolean bothSucceeded(Retcode retcode) { return retcode != null && successCode(retcode.origCode()) && successCode(retcode.destCode()); }
    private static boolean successCode(String code) { return "AAAAAAA".equals(code) || "000000000000".equals(code); }
    private static String transactionFieldName(Retcode retcode) {
        if (retcode == null || missingResponseCode(retcode.origCode()) || missingResponseCode(retcode.destCode())) return "二者都失败响应码不一致";
        if (successCode(retcode == null ? null : retcode.origCode())) return "528成功ccbs失败";
        if (successCode(retcode == null ? null : retcode.destCode())) return "528失败ccbs成功";
        return text(retcode == null ? null : retcode.origCode()).equals(text(retcode == null ? null : retcode.destCode())) ? "二者都失败响应码一致" : "二者都失败响应码不一致";
    }
    private static boolean missingResponseCode(String code) { return code == null || code.isEmpty(); }
    private static String joinedFieldNames(String... names) { List<String> result = new ArrayList<>(); for (String name : names) if (!text(name).isBlank()) result.add(name); return String.join(" | ", result); }
    private static String valueStatus(String value) { return text(value).isEmpty() ? "无值" : "有值"; }
    private static String module(String service, Map<String, Catalog> catalogs) { Catalog catalog = catalogs.get(key(service)); return catalog == null ? UNCONFIGURED_MODULE : moduleName(catalog); }
    private static String moduleName(Catalog catalog) { return catalog.moduleName() == null || catalog.moduleName().isBlank() ? UNCONFIGURED_MODULE : catalog.moduleName(); }
    private static String key(String value) { return text(value).toLowerCase(Locale.ROOT); }
    private static String transactionIssueKey(String service, String origCode, String destCode) { return "TRAN|" + issuePart(service) + "|" + issuePart(origCode) + "|" + issuePart(destCode); }
    private static String fieldIssueKey(String service, String sourceField) { return "FIELD|" + issuePart(service) + "|" + issuePart(sourceField); }
    private static String issuePart(String value) { return text(value).trim().toLowerCase(Locale.ROOT); }
    private static String text(String value) { return value == null ? "" : value; }
    private static <T> Set<T> union(Set<T> first, Set<T> second) { Set<T> result = new TreeSet<>(); result.addAll(first); result.addAll(second); return result; }
    private static MapSqlParameterSource params(String batchId, String reportDate) { return new MapSqlParameterSource("batchId", batchId).addValue("reportDate", reportDate); }

    private record Catalog(String tranCode, String tranName, String moduleName, String owner) {}
    private record FieldMapping(String tranCode, String serviceCode, String sopFieldName, String soapFieldName, String bizjsonFieldName, String fieldCnName) {}
    private record Retcode(String serviceCode, String origCode, String origDesc, String destCode, String destDesc) {}
    private record Tran(String mesgSeq, String origCdate, int convIndex, int convCindex, String destTrcd, String compResult) {}
    private record Field(String mesgSeq, String origCdate, int convIndex, int convCindex, int fieldIndex, String destTrcd, String origFieldName, String origValue, String destFieldName, String destValue) {}
}
