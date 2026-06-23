package com.spdb.migration;

import com.spdb.web.PageRequestParams;
import com.spdb.web.PagedResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class MigrationCommandService {
    static final long MAX_SHARD_COUNT = 10_000L;
    static final int MAX_PARALLELISM = 8;
    private static final int MAX_ERROR_LENGTH = 2000;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<MigrationTaskLauncher> launcherProvider;
    private final MigrationRuntimeProperties runtimeProperties;

    public MigrationCommandService(NamedParameterJdbcTemplate jdbc,
                                   PlatformTransactionManager transactionManager,
                                   ObjectProvider<MigrationTaskLauncher> launcherProvider,
                                   MigrationRuntimeProperties runtimeProperties) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.launcherProvider = launcherProvider;
        this.runtimeProperties = runtimeProperties;
    }

    public long createCommand(MigrationCommandForm form) {
        validate(form);
        long totalShardCount = shardCount(form);
        Long createdCommandId = transactionTemplate.execute(status -> {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update("""
                    insert into ana_migration_command (
                        source_label, target_schema, time_from, time_to, window_seconds, parallelism,
                        status, total_shard_count, remark, created_by
                    ) values (
                        :sourceLabel, :targetSchema, :timeFrom, :timeTo, :windowSeconds, :parallelism,
                        'CREATED', :totalShardCount, :remark, '系统'
                    )
                    """, params(form).addValue("totalShardCount", totalShardCount), keyHolder, new String[]{"command_id"});
            long commandId = generatedLongKey(keyHolder, "command_id");
            insertShards(commandId, form, totalShardCount);
            return commandId;
        });
        if (createdCommandId == null) {
            throw new IllegalStateException("Migration command was not created");
        }
        long commandId = createdCommandId;
        launch(commandId);
        return commandId;
    }

    public PagedResult<MigrationCommandRow> search(PageRequestParams page) {
        Long totalValue = jdbc.queryForObject("select count(*) from ana_migration_command", new MapSqlParameterSource(), Long.class);
        long total = totalValue == null ? 0 : totalValue;
        PageRequestParams effectivePage = new PageRequestParams(Math.min(page.page(), page.totalPages(total)), page.size());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", effectivePage.size())
                .addValue("offset", effectivePage.offset());
        List<MigrationCommandRow> rows = jdbc.query("""
                select *
                from ana_migration_command
                order by created_time desc, command_id desc
                limit :limit offset :offset
                """, params, (rs, i) -> mapCommand(rs));
        return PagedResult.of(rows, total, effectivePage);
    }

    public String sourceLabel() {
        return runtimeProperties.sourceLabel();
    }

    public String targetSchema() {
        return runtimeProperties.targetSchema();
    }

    public MigrationProgressRow progress(long commandId) {
        MigrationCommandRow command = command(commandId);
        if (command == null) {
            return null;
        }
        List<MigrationShardRow> shards = jdbc.query("""
                select *
                from ana_migration_shard
                where command_id = :commandId
                order by shard_seq
                """, new MapSqlParameterSource("commandId", commandId), (rs, i) -> mapShard(rs));
        return new MigrationProgressRow(
                command.commandId(), command.sourceLabel(), command.targetSchema(), command.status(),
                command.timeFrom(), command.timeTo(), command.windowSeconds(), command.parallelism(),
                command.totalShardCount(), command.completedShardCount(), command.failedShardCount(),
                command.migratedRows(), command.skippedRows(), command.droppedRows(),
                command.startedTime() == null ? null : durationSeconds(
                        Timestamp.valueOf(command.startedTime()),
                        command.endedTime() == null ? null : Timestamp.valueOf(command.endedTime())),
                command.startedTime(), command.endedTime(), command.errorMessage(), shards
        );
    }

    public void requestCancel(long commandId) {
        jdbc.update("""
                update ana_migration_command
                   set status = 'CANCEL_REQUESTED', updated_at = current_timestamp
                 where command_id = :commandId and status in ('CREATED','RUNNING')
                """, new MapSqlParameterSource("commandId", commandId));
    }

    public void resume(long commandId) {
        resetStaleRunningShards(commandId);
        int updated = jdbc.update("""
                update ana_migration_command
                   set status = 'CREATED', error_message = null, ended_time = null, updated_at = current_timestamp
                 where command_id = :commandId and status in ('FAILED','CANCELLED','CANCEL_REQUESTED','RUNNING')
                """, new MapSqlParameterSource("commandId", commandId));
        if (updated == 1) {
            launch(commandId);
        }
    }

    MigrationCommandRow command(long commandId) {
        List<MigrationCommandRow> commands = jdbc.query("""
                select *
                from ana_migration_command
                where command_id = :commandId
                """, new MapSqlParameterSource("commandId", commandId), (rs, i) -> mapCommand(rs));
        return commands.isEmpty() ? null : commands.get(0);
    }

    List<Long> runnableShardIds(long commandId) {
        return jdbc.queryForList("""
                select shard_id
                from ana_migration_shard
                where command_id = :commandId and status in ('PENDING','FAILED')
                order by shard_seq
                """, new MapSqlParameterSource("commandId", commandId), Long.class);
    }

    boolean tryStartShard(long shardId) {
        int updated = jdbc.update("""
                update ana_migration_shard
                   set status = 'RUNNING',
                       attempts = attempts + 1,
                       started_time = current_timestamp,
                       ended_time = null,
                       error_message = null,
                       updated_at = current_timestamp
                 where shard_id = :shardId and status in ('PENDING','FAILED')
                """, new MapSqlParameterSource("shardId", shardId));
        return updated == 1;
    }

    boolean markRunning(long commandId) {
        int updated = jdbc.update("""
                update ana_migration_command
                   set status = 'RUNNING',
                       started_time = coalesce(started_time, current_timestamp),
                       ended_time = null,
                       error_message = null,
                       updated_at = current_timestamp
                 where command_id = :commandId and status in ('CREATED','FAILED','CANCELLED')
                """, new MapSqlParameterSource("commandId", commandId));
        return updated == 1;
    }

    void markShardCompleted(long shardId, long migratedRows, long skippedRows, long droppedRows) {
        jdbc.update("""
                update ana_migration_shard
                   set status = 'COMPLETED',
                       migrated_rows = :migratedRows,
                       skipped_rows = :skippedRows,
                       dropped_rows = :droppedRows,
                       ended_time = current_timestamp,
                       error_message = null,
                       updated_at = current_timestamp
                 where shard_id = :shardId
                """, new MapSqlParameterSource()
                .addValue("shardId", shardId)
                .addValue("migratedRows", migratedRows)
                .addValue("skippedRows", skippedRows)
                .addValue("droppedRows", droppedRows));
    }

    void markShardCompleted(long shardId, MigrationShardResult result) {
        markShardCompleted(shardId, result.migratedRows(), result.skippedRows(), result.droppedRows());
    }

    void markShardFailed(long shardId, String errorMessage) {
        jdbc.update("""
                update ana_migration_shard
                   set status = 'FAILED',
                       ended_time = current_timestamp,
                       error_message = :errorMessage,
                       updated_at = current_timestamp
                 where shard_id = :shardId
                """, new MapSqlParameterSource()
                .addValue("shardId", shardId)
                .addValue("errorMessage", abbreviate(errorMessage, MAX_ERROR_LENGTH)));
    }

    void refreshCommandCounters(long commandId) {
        jdbc.update("""
                update ana_migration_command c
                   set completed_shard_count = (select count(*) from ana_migration_shard where command_id = c.command_id and status in ('COMPLETED','SKIPPED')),
                       failed_shard_count = (select count(*) from ana_migration_shard where command_id = c.command_id and status = 'FAILED'),
                       migrated_rows = (select coalesce(sum(migrated_rows), 0) from ana_migration_shard where command_id = c.command_id),
                       skipped_rows = (select coalesce(sum(skipped_rows), 0) from ana_migration_shard where command_id = c.command_id),
                       dropped_rows = (select coalesce(sum(dropped_rows), 0) from ana_migration_shard where command_id = c.command_id),
                       updated_at = current_timestamp
                 where c.command_id = :commandId
                """, new MapSqlParameterSource("commandId", commandId));
    }

    boolean markCompleted(long commandId) {
        int updated = jdbc.update("""
                update ana_migration_command
                   set status = 'COMPLETED',
                       ended_time = current_timestamp,
                       error_message = null,
                       updated_at = current_timestamp
                 where command_id = :commandId and status = 'RUNNING'
                """, new MapSqlParameterSource("commandId", commandId));
        return updated == 1;
    }

    boolean markFailed(long commandId, String errorMessage) {
        int updated = jdbc.update("""
                update ana_migration_command
                   set status = 'FAILED',
                       ended_time = current_timestamp,
                       error_message = :errorMessage,
                       updated_at = current_timestamp
                 where command_id = :commandId and status = 'RUNNING'
                """, new MapSqlParameterSource()
                .addValue("commandId", commandId)
                .addValue("errorMessage", abbreviate(errorMessage, MAX_ERROR_LENGTH)));
        return updated == 1;
    }

    void markCancelled(long commandId) {
        jdbc.update("""
                update ana_migration_command
                   set status = 'CANCELLED',
                       ended_time = current_timestamp,
                       updated_at = current_timestamp
                 where command_id = :commandId and status in ('CANCEL_REQUESTED','RUNNING','CREATED')
                """, new MapSqlParameterSource("commandId", commandId));
    }

    boolean isCancelRequested(long commandId) {
        String status = jdbc.queryForObject("""
                select status
                from ana_migration_command
                where command_id = :commandId
                """, new MapSqlParameterSource("commandId", commandId), String.class);
        return "CANCEL_REQUESTED".equals(status);
    }

    MigrationShardRow shard(long shardId) {
        List<MigrationShardRow> shards = jdbc.query("""
                select *
                from ana_migration_shard
                where shard_id = :shardId
                """, new MapSqlParameterSource("shardId", shardId), (rs, i) -> mapShard(rs));
        return shards.isEmpty() ? null : shards.get(0);
    }

    private void resetStaleRunningShards(long commandId) {
        jdbc.update("""
                update ana_migration_shard
                   set status = 'FAILED',
                       ended_time = current_timestamp,
                       error_message = 'stale running shard reset for resume',
                       updated_at = current_timestamp
                 where command_id = :commandId and status = 'RUNNING'
                """, new MapSqlParameterSource("commandId", commandId));
    }

    private void validate(MigrationCommandForm form) {
        if (form == null) {
            throw new IllegalArgumentException("Migration command form is required");
        }
        if (form.timeFrom() < 0 || form.timeTo() <= form.timeFrom()) {
            throw new IllegalArgumentException("Migration time range is invalid");
        }
        if (form.windowSeconds() <= 0) {
            throw new IllegalArgumentException("Migration window seconds must be positive");
        }
        if (form.parallelism() <= 0) {
            throw new IllegalArgumentException("Migration parallelism must be positive");
        }
        if (form.parallelism() > MAX_PARALLELISM) {
            throw new IllegalArgumentException("Migration parallelism must not exceed " + MAX_PARALLELISM);
        }
        shardCount(form);
    }

    private MapSqlParameterSource params(MigrationCommandForm form) {
        return new MapSqlParameterSource()
                .addValue("sourceLabel", runtimeProperties.sourceLabel())
                .addValue("targetSchema", runtimeProperties.targetSchema())
                .addValue("timeFrom", form.timeFrom())
                .addValue("timeTo", form.timeTo())
                .addValue("windowSeconds", form.windowSeconds())
                .addValue("parallelism", form.parallelism())
                .addValue("remark", StringUtils.hasText(form.remark()) ? form.remark().trim() : null);
    }

    private long shardCount(MigrationCommandForm form) {
        long range = form.timeTo() - form.timeFrom();
        if (range - 1 > Long.MAX_VALUE - form.windowSeconds()) {
            throw new IllegalArgumentException("Migration shard count exceeds supported range");
        }
        if (Long.MAX_VALUE - form.timeFrom() < form.windowSeconds()) {
            throw new IllegalArgumentException("Migration shard window exceeds supported range");
        }
        long shardCount = ((range - 1) / form.windowSeconds()) + 1;
        if (shardCount > MAX_SHARD_COUNT) {
            throw new IllegalArgumentException("Migration shard count exceeds maximum " + MAX_SHARD_COUNT);
        }
        return shardCount;
    }

    private void insertShards(long commandId, MigrationCommandForm form, long totalShardCount) {
        List<MapSqlParameterSource> shards = new ArrayList<>();
        long currentFrom = form.timeFrom();
        int shardSeq = 0;
        while (shardSeq < totalShardCount) {
            long remaining = form.timeTo() - currentFrom;
            long currentTo = remaining <= form.windowSeconds() ? form.timeTo() : Math.addExact(currentFrom, form.windowSeconds());
            shards.add(new MapSqlParameterSource()
                    .addValue("commandId", commandId)
                    .addValue("shardSeq", shardSeq++)
                    .addValue("timeFrom", currentFrom)
                    .addValue("timeTo", currentTo));
            currentFrom = currentTo;
        }
        jdbc.batchUpdate("""
                insert into ana_migration_shard (
                    command_id, shard_seq, time_from, time_to, status
                ) values (
                    :commandId, :shardSeq, :timeFrom, :timeTo, 'PENDING'
                )
                """, shards.toArray(MapSqlParameterSource[]::new));
    }

    private long generatedLongKey(KeyHolder keyHolder, String keyName) {
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null && keys.get(keyName) instanceof Number number) {
            return number.longValue();
        }
        Number key = keyHolder.getKey();
        if (key != null) {
            return key.longValue();
        }
        throw new IllegalStateException("Generated command id was not returned");
    }

    private MigrationCommandRow mapCommand(ResultSet rs) throws SQLException {
        LocalDateTime startedTime = localDateTime(rs.getTimestamp("started_time"));
        LocalDateTime endedTime = localDateTime(rs.getTimestamp("ended_time"));
        return new MigrationCommandRow(
                rs.getLong("command_id"),
                rs.getString("source_label"),
                rs.getString("target_schema"),
                rs.getString("status"),
                rs.getLong("time_from"),
                rs.getLong("time_to"),
                rs.getLong("window_seconds"),
                rs.getInt("parallelism"),
                rs.getLong("total_shard_count"),
                rs.getLong("completed_shard_count"),
                rs.getLong("failed_shard_count"),
                rs.getLong("migrated_rows"),
                rs.getLong("skipped_rows"),
                rs.getLong("dropped_rows"),
                durationText(startedTime, endedTime),
                localDateTime(rs.getTimestamp("created_time")),
                startedTime,
                endedTime,
                rs.getString("error_message"),
                rs.getString("remark")
        );
    }

    private MigrationShardRow mapShard(ResultSet rs) throws SQLException {
        return new MigrationShardRow(
                rs.getInt("shard_seq"),
                rs.getLong("time_from"),
                rs.getLong("time_to"),
                rs.getString("status"),
                rs.getLong("migrated_rows"),
                rs.getLong("skipped_rows"),
                rs.getLong("dropped_rows"),
                rs.getInt("attempts"),
                durationSeconds(rs.getTimestamp("started_time"), rs.getTimestamp("ended_time")),
                rs.getString("error_message")
        );
    }

    private String durationText(LocalDateTime startedTime, LocalDateTime endedTime) {
        if (startedTime == null) {
            return "-";
        }
        long seconds = Duration.between(startedTime, endedTime == null ? LocalDateTime.now() : endedTime).toSeconds();
        if (seconds < 60) {
            return seconds + "秒";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "分" + (seconds % 60) + "秒";
        }
        return (minutes / 60) + "时" + (minutes % 60) + "分";
    }

    private long durationSeconds(Timestamp startedTime, Timestamp endedTime) {
        if (startedTime == null) {
            return 0L;
        }
        Timestamp end = endedTime == null ? Timestamp.valueOf(LocalDateTime.now()) : endedTime;
        return Math.max(0L, Duration.between(startedTime.toLocalDateTime(), end.toLocalDateTime()).toSeconds());
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private void launch(long commandId) {
        MigrationTaskLauncher launcher = launcherProvider.getIfAvailable();
        if (launcher != null) {
            launcher.launch(commandId);
        }
    }
}
