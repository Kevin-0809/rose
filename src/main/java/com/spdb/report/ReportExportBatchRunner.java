package com.spdb.report;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;

/** Materializes one report-export batch from the current replay comparison tables. */
@Component
public class ReportExportBatchRunner {
    private static final String UNCONFIGURED_MODULE = "未配置领域";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public ReportExportBatchRunner(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void run(String batchId, String reportDate, LocalDateTime exportTime) {
        transactionTemplate.executeWithoutResult(status -> runInTransaction(batchId, reportDate, exportTime));
    }

    private void runInTransaction(String batchId, String reportDate, LocalDateTime exportTime) {
        Map<String, Catalog> catalogs = catalogs();
        Map<String, Retcode> retcodes = retcodes();
        List<Tran> transactions = transactions();
        List<Field> fields = fields();
        insertSummaries(batchId, reportDate, transactions, fields, catalogs);
        insertTransactionDetails(batchId, reportDate, exportTime, transactions, retcodes, catalogs);
        insertFieldDetails(batchId, reportDate, exportTime, fields, catalogs);
    }

    private Map<String, Catalog> catalogs() {
        Map<String, Catalog> result = new LinkedHashMap<>();
        jdbc.getJdbcTemplate().query("select catalog_id, tran_code, service_code, tran_name, module_name, owner from ana_tran_catalog order by catalog_id", (RowCallbackHandler)
                rs -> result.putIfAbsent(key(rs.getString("service_code")), new Catalog(rs.getString("tran_code"),
                        rs.getString("tran_name"), rs.getString("module_name"), rs.getString("owner"))));
        return result;
    }

    private Map<String, Retcode> retcodes() {
        Map<String, Retcode> result = new LinkedHashMap<>();
        jdbc.getJdbcTemplate().query("select mesg_seq, orig_cdate, service_code, orig_error_code, orig_error_desc, dest_error_code, dest_error_desc from tss_retcode_comp", (RowCallbackHandler)
                rs -> result.putIfAbsent(rowKey(rs.getString("orig_cdate"), rs.getString("mesg_seq")),
                        new Retcode(rs.getString("service_code"), rs.getString("orig_error_code"), rs.getString("orig_error_desc"),
                                rs.getString("dest_error_code"), rs.getString("dest_error_desc"))));
        return result;
    }

    private List<Tran> transactions() {
        List<Tran> result = new ArrayList<>();
        jdbc.getJdbcTemplate().query("select mesg_seq, orig_cdate, conv_index, conv_cindex, dest_trcd, comp_result from tss_tran_comp", (RowCallbackHandler)
                rs -> result.add(new Tran(rs.getString("mesg_seq"), rs.getString("orig_cdate"), rs.getInt("conv_index"),
                        rs.getInt("conv_cindex"), rs.getString("dest_trcd"), rs.getString("comp_result"))));
        return result;
    }

    private List<Field> fields() {
        List<Field> result = new ArrayList<>();
        jdbc.getJdbcTemplate().query("select mesg_seq, orig_cdate, conv_index, conv_cindex, field_index, dest_trcd, orig_field_name, orig_field_value, dest_field_name, dest_field_value from tss_field_comp", (RowCallbackHandler)
                rs -> result.add(new Field(rs.getString("mesg_seq"), rs.getString("orig_cdate"), rs.getInt("conv_index"),
                        rs.getInt("conv_cindex"), rs.getInt("field_index"), rs.getString("dest_trcd"),
                        rs.getString("orig_field_name"), rs.getString("orig_field_value"), rs.getString("dest_field_name"),
                        rs.getString("dest_field_value"))));
        return result;
    }

    private void insertSummaries(String batchId, String reportDate, List<Tran> transactions, List<Field> fields,
                                 Map<String, Catalog> catalogs) {
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

    private void insertTransactionDetails(String batchId, String reportDate, LocalDateTime exportTime, List<Tran> transactions,
                                          Map<String, Retcode> retcodes, Map<String, Catalog> catalogs) {
        Map<String, Tran> grouped = new TreeMap<>();
        transactions.stream().filter(row -> Set.of("1", "2", "3", "7", "8").contains(row.compResult())).sorted(TRAN_ORDER).forEach(row -> {
            Retcode retcode = retcodes.get(rowKey(row.origCdate(), row.mesgSeq()));
            String issue = retcode == null ? "TRAN|" + text(row.destTrcd()) + "|" + row.compResult()
                    : "RET|" + service(retcode.serviceCode()) + "|" + text(retcode.origCode()) + "|" + text(retcode.destCode());
            grouped.putIfAbsent(issue, row);
        });
        long rowNo = 0;
        for (Tran tran : grouped.values()) {
            Retcode retcode = retcodes.get(rowKey(tran.origCdate(), tran.mesgSeq()));
            String service = retcode == null ? service(tran.destTrcd()) : service(retcode.serviceCode());
            Catalog catalog = catalogs.get(key(service));
            String fieldName = tran.compResult();
            String description = retcode == null ? "交易比对结果：" + tran.compResult()
                    : "528响应码：" + text(retcode.origCode()) + "；CCBS响应码：" + text(retcode.destCode());
            jdbc.update("""
                    insert into ana_tran_diff_tracking_export(export_timestamp, source_batch_id, business_date, row_no,
                    service_code, orig_error_code, dest_error_code, tran_code, tran_name, module_name, orig_error_desc,
                    dest_error_desc, transaction_owner, tran_seq_no, problem_level, registration_date, field_name, problem_description)
                    values (:time,:batchId,:date,:rowNo,:service,:origCode,:destCode,:tranCode,:tranName,:module,:origDesc,:destDesc,:owner,:seq,'交易级',:date,:field,:description)""",
                    params(batchId, reportDate).addValue("time", Timestamp.valueOf(exportTime)).addValue("date", reportDate).addValue("rowNo", ++rowNo)
                            .addValue("service", service).addValue("origCode", retcode == null ? null : retcode.origCode()).addValue("destCode", retcode == null ? null : retcode.destCode())
                            .addValue("tranCode", catalog == null ? service : catalog.tranCode()).addValue("tranName", catalog == null ? null : catalog.tranName())
                            .addValue("module", catalog == null ? UNCONFIGURED_MODULE : moduleName(catalog)).addValue("origDesc", retcode == null ? null : retcode.origDesc())
                            .addValue("destDesc", retcode == null ? null : retcode.destDesc()).addValue("owner", catalog == null ? null : catalog.owner())
                            .addValue("seq", tran.mesgSeq()).addValue("field", fieldName).addValue("description", description));
        }
    }

    private void insertFieldDetails(String batchId, String reportDate, LocalDateTime exportTime, List<Field> fields, Map<String, Catalog> catalogs) {
        Map<String, Field> grouped = new TreeMap<>();
        fields.stream().sorted(FIELD_ORDER).forEach(row -> grouped.putIfAbsent(service(row.destTrcd()) + "|" + normalizedField(row.origFieldName()) + "|" + text(row.destFieldName()), row));
        long rowNo = 0;
        for (Field field : grouped.values()) {
            String service = service(field.destTrcd()); Catalog catalog = catalogs.get(key(service)); String normalized = normalizedField(field.origFieldName());
            jdbc.update("""
                    insert into ana_field_diff_tracking_export(export_timestamp, source_batch_id, business_date, row_no,
                    service_code, tran_code, tran_name, module_name, soap_field_name, mapping_status, orig_field_value,
                    dest_field_value, transaction_owner, tran_seq_no, problem_level, registration_date, field_name, problem_description)
                    values (:time,:batchId,:date,:rowNo,:service,:tranCode,:tranName,:module,:field,'UNMAPPED',:orig,:dest,:owner,:seq,'字段级',:date,:field,:description)""",
                    params(batchId, reportDate).addValue("time", Timestamp.valueOf(exportTime)).addValue("date", reportDate).addValue("rowNo", ++rowNo)
                            .addValue("service", service).addValue("tranCode", catalog == null ? service : catalog.tranCode()).addValue("tranName", catalog == null ? null : catalog.tranName())
                            .addValue("module", catalog == null ? UNCONFIGURED_MODULE : moduleName(catalog)).addValue("field", normalized).addValue("orig", field.origValue()).addValue("dest", field.destValue())
                            .addValue("owner", catalog == null ? null : catalog.owner()).addValue("seq", field.mesgSeq()).addValue("description", "528字段值：" + text(field.origValue()) + "；CCBS字段值：" + text(field.destValue())));
        }
    }

    private static final Comparator<Tran> TRAN_ORDER = Comparator.comparing(Tran::mesgSeq, Comparator.nullsFirst(String::compareTo)).thenComparingInt(Tran::convIndex).thenComparingInt(Tran::convCindex);
    private static final Comparator<Field> FIELD_ORDER = Comparator.comparing(Field::mesgSeq, Comparator.nullsFirst(String::compareTo)).thenComparingInt(Field::convIndex).thenComparingInt(Field::convCindex).thenComparingInt(Field::fieldIndex);
    private static long count(List<Tran> rows, String result) { return rows.stream().filter(row -> result.equals(row.compResult())).count(); }
    private static String service(String value) { int index = text(value).indexOf('&'); return index < 0 ? text(value) : value.substring(0, index); }
    private static String normalizedField(String value) { String[] parts = text(value).split("\\."); return parts.length > 1 ? parts[0] + "." + parts[1] : text(value); }
    private static String module(String service, Map<String, Catalog> catalogs) { Catalog catalog = catalogs.get(key(service)); return catalog == null ? UNCONFIGURED_MODULE : moduleName(catalog); }
    private static String moduleName(Catalog catalog) { return catalog.moduleName() == null || catalog.moduleName().isBlank() ? UNCONFIGURED_MODULE : catalog.moduleName(); }
    private static String key(String value) { return text(value).toLowerCase(Locale.ROOT); }
    private static String rowKey(String date, String sequence) { return text(date) + "|" + text(sequence); }
    private static String text(String value) { return value == null ? "" : value; }
    private static <T> Set<T> union(Set<T> first, Set<T> second) { Set<T> result = new TreeSet<>(); result.addAll(first); result.addAll(second); return result; }
    private static MapSqlParameterSource params(String batchId, String reportDate) { return new MapSqlParameterSource("batchId", batchId).addValue("reportDate", reportDate); }

    private record Catalog(String tranCode, String tranName, String moduleName, String owner) {}
    private record Retcode(String serviceCode, String origCode, String origDesc, String destCode, String destDesc) {}
    private record Tran(String mesgSeq, String origCdate, int convIndex, int convCindex, String destTrcd, String compResult) {}
    private record Field(String mesgSeq, String origCdate, int convIndex, int convCindex, int fieldIndex, String destTrcd,
                         String origFieldName, String origValue, String destFieldName, String destValue) {}
}
