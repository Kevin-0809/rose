package com.spdb.report;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** Keeps large comparison results in database sort/group operators and bounded JDBC cursors. */
final class ReportExportSourceReader {
    private static final int FETCH_SIZE = 500;
    private final DataSource dataSource;
    private final boolean postgres;

    ReportExportSourceReader(DataSource dataSource) {
        this.dataSource = dataSource;
        try (Connection connection = dataSource.getConnection()) {
            this.postgres = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to identify report source database", exception);
        }
    }

    String javaOrder(String expression) {
        if (!postgres) return expression;
        // PostgreSQL UTF-8/C order differs from Java UTF-16 for supplementary characters.
        // Fixed-width hexadecimal UTF-16 units preserve String.compareTo ordering, including surrogate pairs.
        String codePoint = "ascii(substring(" + expression + ", java_position, 1))";
        return "(case when " + expression + " is null then null else coalesce((select string_agg("
                + "case when " + codePoint + " > 65535 then "
                + "lpad(to_hex(55296 + (" + codePoint + " - 65536) / 1024), 4, '0') || "
                + "lpad(to_hex(56320 + (" + codePoint + " - 65536) % 1024), 4, '0') "
                + "else lpad(to_hex(" + codePoint + "), 4, '0') end, '' order by java_position) "
                + "from generate_series(1, length(" + expression + ")) java_position), '') end) collate \"C\"";
    }

    static String service(String column) {
        return "case when position('&' in coalesce(" + column + ", '')) > 0 then substring("
                + column + ", 1, position('&' in " + column + ") - 1) else coalesce(" + column + ", '') end";
    }

    static String issuePart(String expression) {
        return javaText(expression, "issue_value");
    }

    static String rootLower(String expression) {
        return javaText(expression, "lower_value");
    }

    private static String javaText(String expression, String column) {
        return "(select " + column + " from report_java_text j where j.original_text = coalesce("
                + expression + ", ''))";
    }

    private void prepareJavaText(Connection connection) throws SQLException {
        // Keep the dictionary in the database, never in a Java Map. Unicode conversion must use
        // the running JDK, including contextual sigma and expanding mappings such as U+0130.
        try (var ddl = connection.createStatement()) {
            ddl.execute("create temporary table if not exists report_java_text (original_text varchar primary key, "
                    + "lower_value varchar, issue_value varchar)" + (postgres ? " on commit drop" : " transactional"));
            ddl.executeUpdate("delete from report_java_text");
        }
        String values = "with " + fieldCtes() + " select normalized_field from field_source"
                + " union select orig_error_code from tss_retcode_comp"
                + " union select dest_error_code from tss_retcode_comp"
                + " union select service_code from ana_tran_catalog"
                + " union select " + service("dest_trcd") + " from tss_field_comp"
                + " union select '' union select '未比对' union select '忽略比对'"
                + " union select '比对中' union select '对比异常'";
        try (PreparedStatement read = connection.prepareStatement(values);
             PreparedStatement write = connection.prepareStatement(
                     "insert into report_java_text(original_text, lower_value, issue_value) values (?, ?, ?)")) {
            read.setFetchSize(FETCH_SIZE);
            try (ResultSet rows = read.executeQuery()) {
                int pending = 0;
                while (rows.next()) {
                    String value = rows.getString(1);
                    if (value == null) continue;
                    write.setString(1, value);
                    write.setString(2, value.toLowerCase(java.util.Locale.ROOT));
                    write.setString(3, value.trim().toLowerCase(java.util.Locale.ROOT));
                    write.addBatch();
                    if (++pending == FETCH_SIZE) {
                        write.executeBatch();
                        write.clearBatch();
                        pending = 0;
                    }
                }
                if (pending != 0) write.executeBatch();
            }
        }
    }

    static String sameIdentity(String left, String right) {
        return identity(left) + " = " + identity(right);
    }

    private static String responseKey(String alias) {
        return "coalesce(" + alias + ".mesg_seq, '') || '|' || coalesce(" + alias + ".orig_cdate, '')";
    }

    private static String identity(String alias) {
        return "(" + responseKey(alias) + " || '|' || cast(coalesce(" + alias + ".conv_index, 0) as varchar)"
                + " || '|' || cast(coalesce(" + alias + ".conv_cindex, 0) as varchar))";
    }

    static String moduleName(String expression) {
        // Character.isWhitespace used by Java String.isBlank (not SQL trim's space-only semantics).
        String whitespace = "[\\x09-\\x0D\\x1C-\\x20\\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000]";
        return "case when regexp_replace(coalesce(" + expression + ", ''), '^" + whitespace
                + "*$', '') = '' then '未配置领域' else " + expression + " end";
    }

    static String notIgnored(String alias) {
        return "not exists (select 1 from tss_tran_comp ignored where ignored.comp_result = '5' and "
                + sameIdentity(alias, "ignored") + ")";
    }

    // Java String.split drops trailing empty segments. Keep this normalization identical to normalizedField.
    static String fieldCtes() {
        return """
                field_text as (
                    select f.mesg_seq, f.orig_cdate, f.conv_index, f.conv_cindex, f.field_index, f.dest_trcd,
                           f.orig_field_name, f.orig_field_value, f.dest_field_name, f.dest_field_value,
                           row_number() over () as source_order, %s as service_code,
                           coalesce(orig_field_name, '') as field_text,
                           regexp_replace(coalesce(orig_field_name, ''), '[.]+$', '') as trimmed_field
                      from tss_field_comp f
                ), field_split as (
                    select field_text.*, case when position('.' in trimmed_field) = 0 then field_text else trimmed_field end as split_field
                      from field_text
                ), field_two_parts as (
                    select field_split.*,
                           case when position('.' in trimmed_field) > 0 and position('.' in split_field) > 0
                                  and position('.' in substring(split_field, position('.' in split_field) + 1)) > 0
                                then substring(split_field, 1, position('.' in split_field)
                                     + position('.' in substring(split_field, position('.' in split_field) + 1)) - 1)
                                else split_field end as two_parts
                      from field_split
                ), field_source as (
                    select field_two_parts.*,
                           case when position('_' in two_parts) > 0
                                then substring(two_parts, 1, position('_' in two_parts) - 1)
                                else two_parts end as normalized_field
                      from field_two_parts
                )
                """.formatted(service("f.dest_trcd"));
    }

    static String transactionCtes() {
        return """
                retcode_ranked as (
                    select r.*, row_number() over (
                        partition by %s) as response_no
                      from tss_retcode_comp r
                ), transaction_source as (
                    select t.*, %s as service_code,
                           case when t.comp_result in ('3', '8') then
                               case when r.orig_error_code is not null and r.orig_error_code <> ''
                                          and r.dest_error_code is not null and r.dest_error_code <> ''
                                          and r.orig_error_code = r.dest_error_code then '3' else '8' end
                               else t.comp_result end as summary_result,
                           case when exists (select 1 from tss_field_comp f where %s) then 1 else 0 end as has_field,
                           case when exists (select 1 from tss_field_comp f
                                              where %s = %s
                                                and (f.mesg_seq = t.mesg_seq or (f.mesg_seq is null and t.mesg_seq is null)))
                                then 1 else 0 end as service_has_field
                      from tss_tran_comp t
                      left join retcode_ranked r on (%s) = (%s)
                                               and r.response_no = 1
                )
                """.formatted(responseKey("r"), service("t.dest_trcd"), sameIdentity("t", "f"),
                service("f.dest_trcd"), service("t.dest_trcd"), responseKey("r"), responseKey("t"));
    }

    static String metricsSql() {
        return "with " + transactionCtes() + """
                select service_code, count(*) as total,
                       sum(case when summary_result = '1' then 1 else 0 end) as one,
                       sum(case when summary_result = '2' then 1 else 0 end) as two,
                       sum(case when summary_result = '3' then 1 else 0 end) as three,
                       sum(case when summary_result = '4' then 1 else 0 end) as four,
                       sum(case when summary_result = '8' then 1 else 0 end) as eight,
                       sum(case when summary_result = '5' then 1 else 0 end) as five,
                       sum(case when comp_result = '4' and has_field = 0 then 1 else 0 end) as field_pass,
                       count(distinct case when comp_result = '4' then mesg_seq end)
                         + max(case when comp_result = '4' and mesg_seq is null then 1 else 0 end) as interface_four,
                       count(distinct case when comp_result = '4' and service_has_field = 0 then mesg_seq end)
                         + max(case when comp_result = '4' and service_has_field = 0 and mesg_seq is null then 1 else 0 end) as interface_pass
                  from transaction_source
                 group by service_code
                 order by service_code
                """;
    }

    void stream(String sql, RowConsumer consumer, Object... parameters) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        boolean transactional = DataSourceUtils.isConnectionTransactional(connection, dataSource);
        try {
            if (!transactional) connection.setAutoCommit(false);
            try {
                if (sql.contains("report_java_text")) prepareJavaText(connection);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setFetchSize(FETCH_SIZE);
                    for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) consumer.accept(rs, connection);
                    }
                }
                if (!transactional) connection.commit();
            } catch (SQLException | RuntimeException exception) {
                if (!transactional) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        exception.addSuppressed(rollbackFailure);
                    }
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to stream report comparison sources", exception);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @FunctionalInterface
    interface RowConsumer {
        void accept(ResultSet row, Connection connection) throws SQLException;
    }
}
