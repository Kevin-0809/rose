package com.spdb.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 基于 openGauss 分区系统表的分区访问实现。分区名规则为 p{业务日期 yyyyMMdd}。
 *
 * <p>openGauss 的分区信息保存在 {@code pg_partition}（{@code parttype = 'p'}），
 * 不使用 PostgreSQL 的 {@code pg_inherits}/{@code pg_class} 子表继承模型，
 * 因此分区判断改为查询 {@code pg_partition}。源表固定使用 adp schema。
 */
final class JdbcTranCodeSourcePartition implements TranCodeSourcePartition {
    private static final Logger log = LoggerFactory.getLogger(JdbcTranCodeSourcePartition.class);
    private static final String SCHEMA_NAME = "adp";
    private static final String REQUEST_TABLE = "msg_flow_log_request";
    private static final String QUALIFIED_REQUEST_TABLE = SCHEMA_NAME + "." + REQUEST_TABLE;
    private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NamedParameterJdbcTemplate sourceJdbc;

    JdbcTranCodeSourcePartition(NamedParameterJdbcTemplate sourceJdbc) {
        this.sourceJdbc = sourceJdbc;
    }

    @Override
    public String requestTableRef(LocalDate date) {
        return QUALIFIED_REQUEST_TABLE + " partition(" + partitionName(date) + ")";
    }

    @Override
    public boolean exists(LocalDate date) {
        Long count = sourceJdbc.queryForObject("""
                select count(*)
                from pg_partition part
                join pg_class parent on parent.oid = part.parentid
                join pg_namespace ns on ns.oid = parent.relnamespace
                where ns.nspname = :schemaName
                  and parent.relname = :tableName
                  and part.relname = :partitionName
                  and part.parttype = 'p'
                """, new MapSqlParameterSource()
                .addValue("schemaName", SCHEMA_NAME)
                .addValue("tableName", REQUEST_TABLE)
                .addValue("partitionName", partitionName(date)), Long.class);
        return count != null && count > 0L;
    }

    @Override
    public LocalDate earliestPartitionDate() {
        List<String> partitionNames = sourceJdbc.queryForList("""
                select part.relname
                from pg_partition part
                join pg_class parent on parent.oid = part.parentid
                join pg_namespace ns on ns.oid = parent.relnamespace
                where ns.nspname = :schemaName
                  and parent.relname = :tableName
                  and part.parttype = 'p'
                  and part.relname ~ '^p[0-9]{8}$'
                """, new MapSqlParameterSource()
                .addValue("schemaName", SCHEMA_NAME)
                .addValue("tableName", REQUEST_TABLE), String.class);
        LocalDate earliest = partitionNames.stream()
                .map(JdbcTranCodeSourcePartition::parsePartitionDate)
                .flatMap(Optional::stream)
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (earliest == null) {
            log.warn("No dated partition found for table {}, full-lookback migration may be skipped", QUALIFIED_REQUEST_TABLE);
        }
        return earliest;
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
