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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class MigrationCommandService {
    static final long MAX_SHARD_COUNT = 10_000L;
    static final int MAX_PARALLELISM = 16;
    static final int MAX_TRAN_CODE_LENGTH = 32;
    private static final long MILLIS_PER_SECOND = 1000L;
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
                        source_data_source, target_data_source, command_type, time_from, time_to, window_seconds, parallelism,
                        status, total_shard_count, remark, created_by
                    ) values (
                        :sourceDataSource, :targetDataSource, 'TIME_RANGE', :timeFrom, :timeTo, :windowSeconds, :parallelism,
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

    public long createSqlCommand(MigrationSqlCommandForm form) {
        validateSql(form);
        Long createdCommandId = transactionTemplate.execute(status -> {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update("""
                    insert into ana_migration_command (
                        source_data_source, target_data_source, command_type, time_from, time_to, window_seconds, parallelism,
                        status, total_shard_count, response_sql, remark, created_by
                    ) values (
                        :sourceDataSource, :targetDataSource, 'SQL', 0, 0, 0, 1,
                        'CREATED', 1, :responseSql, :remark, '系统'
                    )
                    """, sqlParams(form), keyHolder, new String[]{"command_id"});
            long commandId = generatedLongKey(keyHolder, "command_id");
            jdbc.update("""
                    insert into ana_migration_shard (
                        command_id, shard_seq, time_from, time_to, status
                    ) values (
                        :commandId, 0, 0, 0, 'PENDING'
                    )
                    """, new MapSqlParameterSource("commandId", commandId));
            return commandId;
        });
        if (createdCommandId == null) {
            throw new IllegalStateException("SQL migration command was not created");
        }
        long commandId = createdCommandId;
        launch(commandId);
        return commandId;
    }

    public long createTranCodeCommand(MigrationTranCodeCommandForm form) {
        List<String> tranCodes = validateTranCode(form);
        Long createdCommandId = transactionTemplate.execute(status -> {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update("""
                    insert into ana_migration_command (
                        source_data_source, target_data_source, command_type, time_from, time_to, window_seconds, parallelism,
                        status, total_shard_count, tran_codes, sample_size, lookback_days, remark, created_by
                    ) values (
                        :sourceDataSource, :targetDataSource, 'TRAN_CODE', 0, 0, 0, :parallelism,
                        'CREATED', :totalShardCount, :tranCodes, :sampleSize, :lookbackDays, :remark, '绯荤粺'
                    )
                    """, tranCodeParams(form, tranCodes), keyHolder, new String[]{"command_id"});
            long commandId = generatedLongKey(keyHolder, "command_id");
            insertTranCodeShards(commandId, tranCodes);
            return commandId;
        });
        if (createdCommandId == null) {
            throw new IllegalStateException("Transaction-code migration command was not created");
        }
        long commandId = createdCommandId;
        launch(commandId);
        return commandId;
    }

    public PagedResult<MigrationCommandRow> search(PageRequestParams page) {
        return searchByType(page, "TIME_RANGE");
    }

    public PagedResult<MigrationCommandRow> searchSql(PageRequestParams page) {
        return searchByType(page, "SQL");
    }

    public PagedResult<MigrationCommandRow> searchTranCode(PageRequestParams page) {
        return searchByType(page, "TRAN_CODE");
    }

    private PagedResult<MigrationCommandRow> searchByType(PageRequestParams page, String commandType) {
        Long totalValue = jdbc.queryForObject("""
                select count(*)
                from ana_migration_command
                where command_type = :commandType
                """, new MapSqlParameterSource("commandType", commandType), Long.class);
        long total = totalValue == null ? 0 : totalValue;
        PageRequestParams effectivePage = new PageRequestParams(Math.min(page.page(), page.totalPages(total)), page.size());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", effectivePage.size())
                .addValue("offset", effectivePage.offset())
                .addValue("commandType", commandType);
        List<MigrationCommandRow> rows = jdbc.query("""
                select *
                from ana_migration_command
                where command_type = :commandType
                order by created_time desc, command_id desc
                limit :limit offset :offset
                """, params, (rs, i) -> mapCommand(rs));
        return PagedResult.of(rows, total, effectivePage);
    }

    public String sourceDataSource() {
        return runtimeProperties.sourceDataSource();
    }

    public String targetDataSource() {
        return runtimeProperties.targetDataSource();
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
                command.commandId(), command.sourceDataSource(), command.targetDataSource(), command.status(),
                command.timeFrom(), command.timeTo(), command.windowSeconds(), command.parallelism(),
                command.totalShardCount(), command.completedShardCount(), command.failedShardCount(),
                command.migratedRows(), command.skippedRows(), command.droppedRows(),
                command.startedTime() == null ? null : durationSeconds(
                        Timestamp.valueOf(command.startedTime()),
                        command.endedTime() == null ? null : Timestamp.valueOf(command.endedTime())),
                command.startedTime(), command.endedTime(), command.errorMessage(), shards,
                command.commandType(), command.tranCodes(), command.sampleSize()
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
        markShardCompleted(shardId, new MigrationShardResult(migratedRows, skippedRows, droppedRows));
    }

    void markShardCompleted(long shardId, MigrationShardResult result) {
        jdbc.update("""
                update ana_migration_shard
                   set status = 'COMPLETED',
                       migrated_rows = :migratedRows,
                       skipped_rows = :skippedRows,
                       dropped_rows = :droppedRows,
                       actual_lookback_days = :actualLookbackDays,
                       ended_time = current_timestamp,
                       error_message = null,
                       updated_at = current_timestamp
                 where shard_id = :shardId
                """, new MapSqlParameterSource()
                .addValue("shardId", shardId)
                .addValue("migratedRows", result.migratedRows())
                .addValue("skippedRows", result.skippedRows())
                .addValue("droppedRows", result.droppedRows())
                .addValue("actualLookbackDays", result.actualLookbackDays()));
    }

    void markShardSkipped(long shardId) {
        markShardSkipped(shardId, null);
    }

    void markShardSkipped(long shardId, MigrationShardResult result) {
        jdbc.update("""
                update ana_migration_shard
                   set status = 'SKIPPED',
                       migrated_rows = 0,
                       skipped_rows = 0,
                       dropped_rows = 0,
                       actual_lookback_days = :actualLookbackDays,
                       ended_time = current_timestamp,
                       error_message = null,
                       updated_at = current_timestamp
                 where shard_id = :shardId
                """, new MapSqlParameterSource("shardId", shardId)
                .addValue("actualLookbackDays", result == null ? null : result.actualLookbackDays()));
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
            throw new IllegalArgumentException("迁移指令参数不能为空");
        }
        if (form.timeFrom() < 0 || form.timeTo() <= form.timeFrom()) {
            throw new IllegalArgumentException("响应时间终点必须大于响应时间起点");
        }
        if (form.windowSeconds() <= 0) {
            throw new IllegalArgumentException("分片窗口大小必须大于0");
        }
        if (form.parallelism() <= 0) {
            throw new IllegalArgumentException("并行度必须大于0");
        }
        if (form.parallelism() > MAX_PARALLELISM) {
            throw new IllegalArgumentException("并行度不能超过 " + MAX_PARALLELISM);
        }
        shardCount(form);
    }

    private void validateSql(MigrationSqlCommandForm form) {
        if (form == null) {
            throw new IllegalArgumentException("SQL迁移指令参数不能为空");
        }
        validateQuerySql(form.responseSql(), "Response SQL");
    }

    private List<String> validateTranCode(MigrationTranCodeCommandForm form) {
        if (form == null) {
            throw new IllegalArgumentException("Transaction-code migration command must not be null");
        }
        List<String> tranCodes = parseTranCodes(form.tranCodes());
        if (tranCodes.isEmpty()) {
            throw new IllegalArgumentException("Transaction codes must not be blank");
        }
        if (tranCodes.size() > MAX_SHARD_COUNT) {
            throw new IllegalArgumentException("Transaction-code count exceeds maximum " + MAX_SHARD_COUNT);
        }
        if (tranCodes.stream().anyMatch(tranCode -> tranCode.length() > MAX_TRAN_CODE_LENGTH)) {
            throw new IllegalArgumentException("Transaction-code length must not exceed " + MAX_TRAN_CODE_LENGTH);
        }
        if (form.sampleSize() <= 0) {
            throw new IllegalArgumentException("Sample size must be greater than 0");
        }
        if (form.lookbackDays() <= 0) {
            throw new IllegalArgumentException("Lookback days must be greater than 0");
        }
        if (form.parallelism() <= 0) {
            throw new IllegalArgumentException("Parallelism must be greater than 0");
        }
        if (form.parallelism() > MAX_PARALLELISM) {
            throw new IllegalArgumentException("Parallelism must not exceed " + MAX_PARALLELISM);
        }
        return tranCodes;
    }

    private List<String> parseTranCodes(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        Set<String> distinctCodes = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String tranCode = part.trim();
            if (!tranCode.isEmpty()) {
                distinctCodes.add(tranCode);
            }
        }
        return List.copyOf(distinctCodes);
    }

    private void validateQuerySql(String sql, String label) {
        if (!StringUtils.hasText(sql)) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String trimmed = sql.trim();
        if (trimmed.contains(";")) {
            throw new IllegalArgumentException(label + "只允许单条查询SQL");
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (!(normalized.startsWith("select ") || normalized.startsWith("with "))) {
            throw new IllegalArgumentException(label + "只允许select或with查询");
        }
        String padded = " " + normalized.replaceAll("\\s+", " ") + " ";
        String[] forbiddenKeywords = {
                " insert ", " update ", " delete ", " drop ", " alter ",
                " truncate ", " create ", " merge ", " call "
        };
        for (String keyword : forbiddenKeywords) {
            if (padded.contains(keyword)) {
                throw new IllegalArgumentException(label + "只允许查询SQL，不能包含写操作");
            }
        }
    }

    private MapSqlParameterSource params(MigrationCommandForm form) {
        return new MapSqlParameterSource()
                .addValue("sourceDataSource", runtimeProperties.sourceDataSource())
                .addValue("targetDataSource", runtimeProperties.targetDataSource())
                .addValue("timeFrom", form.timeFrom())
                .addValue("timeTo", form.timeTo())
                .addValue("windowSeconds", form.windowSeconds())
                .addValue("parallelism", form.parallelism())
                .addValue("remark", StringUtils.hasText(form.remark()) ? form.remark().trim() : null);
    }

    private MapSqlParameterSource sqlParams(MigrationSqlCommandForm form) {
        return new MapSqlParameterSource()
                .addValue("sourceDataSource", runtimeProperties.sourceDataSource())
                .addValue("targetDataSource", runtimeProperties.targetDataSource())
                .addValue("responseSql", form.responseSql().trim())
                .addValue("remark", StringUtils.hasText(form.remark()) ? form.remark().trim() : null);
    }

    private MapSqlParameterSource tranCodeParams(MigrationTranCodeCommandForm form, List<String> tranCodes) {
        return new MapSqlParameterSource()
                .addValue("sourceDataSource", runtimeProperties.sourceDataSource())
                .addValue("targetDataSource", runtimeProperties.targetDataSource())
                .addValue("parallelism", form.parallelism())
                .addValue("totalShardCount", tranCodes.size())
                .addValue("tranCodes", String.join(",", tranCodes))
                .addValue("sampleSize", form.sampleSize())
                .addValue("lookbackDays", form.lookbackDays())
                .addValue("remark", StringUtils.hasText(form.remark()) ? form.remark().trim() : null);
    }

    private long shardCount(MigrationCommandForm form) {
        long windowMillis = windowMillis(form.windowSeconds());
        long range = form.timeTo() - form.timeFrom();
        if (range - 1 > Long.MAX_VALUE - windowMillis) {
            throw new IllegalArgumentException("Migration shard count exceeds supported range");
        }
        if (Long.MAX_VALUE - form.timeFrom() < windowMillis) {
            throw new IllegalArgumentException("Migration shard window exceeds supported range");
        }
        long shardCount = ((range - 1) / windowMillis) + 1;
        if (shardCount > MAX_SHARD_COUNT) {
            throw new IllegalArgumentException("Migration shard count exceeds maximum " + MAX_SHARD_COUNT);
        }
        return shardCount;
    }

    private long windowMillis(long windowSeconds) {
        try {
            return Math.multiplyExact(windowSeconds, MILLIS_PER_SECOND);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Migration shard window exceeds supported range", ex);
        }
    }

    private void insertShards(long commandId, MigrationCommandForm form, long totalShardCount) {
        List<MapSqlParameterSource> shards = new ArrayList<>();
        long windowMillis = windowMillis(form.windowSeconds());
        long currentFrom = form.timeFrom();
        int shardSeq = 0;
        while (shardSeq < totalShardCount) {
            long remaining = form.timeTo() - currentFrom;
            long currentTo = remaining <= windowMillis ? form.timeTo() : Math.addExact(currentFrom, windowMillis);
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

    private void insertTranCodeShards(long commandId, List<String> tranCodes) {
        List<MapSqlParameterSource> shards = new ArrayList<>();
        for (int shardSeq = 0; shardSeq < tranCodes.size(); shardSeq++) {
            shards.add(new MapSqlParameterSource()
                    .addValue("commandId", commandId)
                    .addValue("shardSeq", shardSeq)
                    .addValue("tranCode", tranCodes.get(shardSeq)));
        }
        jdbc.batchUpdate("""
                insert into ana_migration_shard (
                    command_id, shard_seq, tran_code, time_from, time_to, status
                ) values (
                    :commandId, :shardSeq, :tranCode, 0, 0, 'PENDING'
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
                rs.getString("source_data_source"),
                rs.getString("target_data_source"),
                rs.getString("command_type"),
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
                rs.getString("request_sql"),
                rs.getString("response_sql"),
                rs.getString("tran_codes"),
                rs.getObject("sample_size", Integer.class),
                rs.getString("remark"),
                rs.getObject("lookback_days", Integer.class)
        );
    }

    private MigrationShardRow mapShard(ResultSet rs) throws SQLException {
        return new MigrationShardRow(
                rs.getInt("shard_seq"),
                rs.getString("tran_code"),
                rs.getLong("time_from"),
                rs.getLong("time_to"),
                rs.getString("status"),
                rs.getLong("migrated_rows"),
                rs.getLong("skipped_rows"),
                rs.getLong("dropped_rows"),
                rs.getObject("actual_lookback_days", Integer.class),
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
