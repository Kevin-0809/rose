package com.spdb.sampling.engine;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Consumer;

@Component
public class JdbcSamplingSourceReader implements SamplingSourceReader {
    public static final String TRAN_FACT_SQL = """
            select mesg_seq, orig_cdate, dest_trcd, comp_result
            from tss_tran_comp
            where orig_cdate = ?
            order by mesg_seq
            """;

    public static final String RETURN_CODE_SQL = """
            select mesg_seq, service_code, orig_cdate, orig_error_code, orig_error_desc,
                   dest_error_code, dest_error_desc
            from tss_retcode_comp
            where orig_cdate = ?
            order by mesg_seq
            """;

    public static final String FIELD_DIFF_SQL = """
            select mesg_seq, orig_cdate, dest_trcd, field_index,
                   orig_field_name, orig_field_value, dest_field_name, dest_field_value
            from tss_field_comp
            where orig_cdate = ?
              and comp_result = '0'
            order by mesg_seq, field_index
            """;

    private final JdbcTemplate jdbc;
    private final int fetchSize;

    @Autowired
    public JdbcSamplingSourceReader(DataSource dataSource) {
        this(dataSource, 1000);
    }

    JdbcSamplingSourceReader(DataSource dataSource, int fetchSize) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.fetchSize = fetchSize;
    }

    @Override
    public void readTranFacts(String origCdate, Consumer<TranFact> consumer) {
        stream(TRAN_FACT_SQL, origCdate, rs -> {
            ServiceParts parts = splitDestTrcd(rs.getString("dest_trcd"));
            consumer.accept(new TranFact(
                    new SourceKey(rs.getString("mesg_seq")),
                    rs.getString("orig_cdate"),
                    rs.getString("dest_trcd"),
                    parts.serviceCode(),
                    parts.messageType(),
                    parts.serviceCode(),
                    null,
                    null,
                    null,
                    rs.getString("comp_result"),
                    "UNCONFIGURED_SERVICE"
            ));
        });
    }

    @Override
    public void readReturnCodes(String origCdate, Consumer<ReturnCodeDiff> consumer) {
        stream(RETURN_CODE_SQL, origCdate, rs -> {
            ServiceParts parts = splitDestTrcd(rs.getString("service_code"));
            consumer.accept(new ReturnCodeDiff(
                    rs.getString("mesg_seq"),
                    rs.getString("orig_cdate"),
                    rs.getString("service_code"),
                    parts.serviceCode(),
                    parts.messageType(),
                    rs.getString("orig_error_code"),
                    rs.getString("orig_error_desc"),
                    rs.getString("dest_error_code"),
                    rs.getString("dest_error_desc")
            ));
        });
    }

    @Override
    public void readFieldDiffs(String origCdate, Consumer<FieldDiff> consumer) {
        stream(FIELD_DIFF_SQL, origCdate, rs -> {
            ServiceParts parts = splitDestTrcd(rs.getString("dest_trcd"));
            consumer.accept(new FieldDiff(
                    new SourceKey(rs.getString("mesg_seq")),
                    rs.getString("orig_cdate"),
                    rs.getString("dest_trcd"),
                    parts.serviceCode(),
                    parts.messageType(),
                    rs.getString("orig_field_name"),
                    rs.getString("dest_field_name"),
                    rs.getString("orig_field_value"),
                    rs.getString("dest_field_value"),
                    rs.getInt("field_index")
            ));
        });
    }

    private void stream(String sql, String origCdate, RowConsumer rowConsumer) {
        jdbc.execute((Connection connection) -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                statement.setFetchSize(fetchSize);
                statement.setString(1, origCdate);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        rowConsumer.accept(rs);
                    }
                }
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
            return null;
        });
    }

    private ServiceParts splitDestTrcd(String destTrcd) {
        if (destTrcd == null) {
            return new ServiceParts("", "");
        }
        int separator = destTrcd.indexOf('&');
        if (separator < 0) {
            return new ServiceParts(destTrcd, "");
        }
        return new ServiceParts(destTrcd.substring(0, separator), MessageType.normalize(destTrcd.substring(separator + 1)));
    }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(ResultSet rs) throws SQLException;
    }

    private record ServiceParts(String serviceCode, String messageType) {
    }
}
