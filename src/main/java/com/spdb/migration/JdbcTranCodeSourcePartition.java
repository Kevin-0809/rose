package com.spdb.migration;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 基于系统表的分区访问实现。分区名规则为 p{业务日期 yyyyMMdd}。
 */
final class JdbcTranCodeSourcePartition implements TranCodeSourcePartition {
    private static final String REQUEST_TABLE = "msg_flow_log_request";
    private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NamedParameterJdbcTemplate sourceJdbc;

    JdbcTranCodeSourcePartition(NamedParameterJdbcTemplate sourceJdbc) {
        this.sourceJdbc = sourceJdbc;
    }

    @Override
    public String requestTableRef(LocalDate date) {
        return REQUEST_TABLE + " partition(" + partitionName(date) + ")";
    }

    @Override
    public boolean exists(LocalDate date) {
        Long count = sourceJdbc.queryForObject("""
                select count(*)
                from pg_class
                where relname = :partitionName
                """, new MapSqlParameterSource("partitionName", partitionName(date)), Long.class);
        return count != null && count > 0L;
    }

    @Override
    public LocalDate earliestPartitionDate() {
        List<String> partitionNames = sourceJdbc.queryForList("""
                select child.relname
                from pg_inherits
                join pg_class child on child.oid = pg_inherits.inhrelid
                join pg_class parent on parent.oid = pg_inherits.inhparent
                where parent.relname = :tableName
                  and child.relname ~ '^p[0-9]{8}$'
                """, new MapSqlParameterSource("tableName", REQUEST_TABLE), String.class);
        return partitionNames.stream()
                .map(JdbcTranCodeSourcePartition::parsePartitionDate)
                .flatMap(Optional::stream)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    static String partitionName(LocalDate date) {
        return "p" + date.format(PARTITION_SUFFIX);
    }

    private static Optional<LocalDate> parsePartitionDate(String partitionName) {
        try {
            return Optional.of(LocalDate.parse(partitionName.substring(1), PARTITION_SUFFIX));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
