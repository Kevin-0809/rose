package com.spdb.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TransactionListImportTaskService {
    private static final int MAX_FAILURE_LENGTH = 4000;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<TransactionListImportTaskLauncher> launcherProvider;

    public TransactionListImportTaskService(NamedParameterJdbcTemplate jdbc,
                                            ObjectProvider<TransactionListImportTaskLauncher> launcherProvider) {
        this.jdbc = jdbc;
        this.launcherProvider = launcherProvider;
    }

    public long createTask(Path listFilePath, String originalFilename) {
        if (listFilePath == null) {
            throw new IllegalArgumentException("导入文件路径不能为空");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                insert into ana_transaction_list_import_task (
                    status, original_filename, list_file_path
                ) values (
                    'CREATED', :originalFilename, :listFilePath
                )
                """, new MapSqlParameterSource()
                .addValue("originalFilename", StringUtils.hasText(originalFilename) ? originalFilename : null)
                .addValue("listFilePath", listFilePath.toString()), keyHolder, new String[]{"task_id"});
        long taskId = generatedLongKey(keyHolder, "task_id");
        TransactionListImportTaskLauncher launcher = launcherProvider.getIfAvailable();
        if (launcher != null) {
            launcher.launch(taskId);
        }
        return taskId;
    }

    public void resume(long taskId) {
        TransactionListImportTaskLauncher launcher = launcherProvider.getIfAvailable();
        if (launcher == null) {
            throw new IllegalStateException("Transaction list import launcher is not available");
        }
        launcher.launch(taskId);
    }

    public TransactionListImportTaskRow task(long taskId) {
        return jdbc.query("""
                select *
                from ana_transaction_list_import_task
                where task_id = :taskId
                """, new MapSqlParameterSource("taskId", taskId), (rs, i) -> mapTask(rs))
                .stream()
                .findFirst()
                .orElse(null);
    }

    public TransactionListImportProgressRow progress(long taskId) {
        TransactionListImportTaskRow task = task(taskId);
        if (task == null) {
            return null;
        }
        return new TransactionListImportProgressRow(
                task.taskId(),
                task.status(),
                task.originalFilename(),
                task.totalCount(),
                task.requestBatchCount(),
                task.completedBatchCount(),
                task.failedBatchCount(),
                task.importedCount(),
                task.tranInserted(),
                task.tranUpdated(),
                task.fieldInserted(),
                task.fieldUpdated(),
                task.fieldSkipped(),
                task.failureMessage(),
                task.createdTime(),
                task.startedTime(),
                task.endedTime()
        );
    }

    public boolean markRunning(long taskId) {
        int updated = jdbc.update("""
                update ana_transaction_list_import_task
                   set status = 'RUNNING',
                       started_time = coalesce(started_time, current_timestamp),
                       ended_time = null,
                       failure_message = null,
                       updated_at = current_timestamp
                 where task_id = :taskId and status in ('CREATED','FAILED')
                """, new MapSqlParameterSource("taskId", taskId));
        return updated == 1;
    }

    public void updatePlannedCounts(long taskId, int totalCount, int requestBatchCount) {
        jdbc.update("""
                update ana_transaction_list_import_task
                   set total_count = :totalCount,
                       request_batch_count = :requestBatchCount,
                       completed_batch_count = 0,
                       failed_batch_count = 0,
                       updated_at = current_timestamp
                 where task_id = :taskId
                """, new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("totalCount", totalCount)
                .addValue("requestBatchCount", requestBatchCount));
    }

    public void incrementCompletedBatch(long taskId) {
        jdbc.update("""
                update ana_transaction_list_import_task
                   set completed_batch_count = completed_batch_count + 1,
                       updated_at = current_timestamp
                 where task_id = :taskId
                """, new MapSqlParameterSource("taskId", taskId));
    }

    public void incrementFailedBatch(long taskId, String failureMessage) {
        jdbc.update("""
                update ana_transaction_list_import_task
                   set failed_batch_count = failed_batch_count + 1,
                       failure_message = :failureMessage,
                       updated_at = current_timestamp
                 where task_id = :taskId
                """, new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("failureMessage", appendFailure(taskId, failureMessage)));
    }

    public void markCompleted(long taskId,
                              ConfigImportBatchResult result,
                              int importedCount,
                              String failureMessage) {
        jdbc.update("""
                update ana_transaction_list_import_task
                   set status = 'COMPLETED',
                       failure_message = :failureMessage,
                       ended_time = current_timestamp,
                       updated_at = current_timestamp
                 where task_id = :taskId
                """, new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("failureMessage", appendFailure(taskId, failureMessage)));
    }

    public void recordSuccessfulImportChunk(long taskId, ConfigImportBatchResult result) {
        if (result == null || result.results().isEmpty()) {
            return;
        }
        Set<String> importedCodes = importedTranCodes(taskId);
        List<String> newCodes = result.results().stream()
                .map(importResult -> importResult.parsed().tran().tranCode())
                .filter(StringUtils::hasText)
                .filter(code -> !importedCodes.contains(code))
                .toList();
        if (newCodes.isEmpty()) {
            return;
        }
        importedCodes.addAll(newCodes);
        jdbc.update("""
                update ana_transaction_list_import_task
                   set imported_count = imported_count + :importedCount,
                       tran_inserted = tran_inserted + :tranInserted,
                       tran_updated = tran_updated + :tranUpdated,
                       field_inserted = field_inserted + :fieldInserted,
                       field_updated = field_updated + :fieldUpdated,
                       field_skipped = field_skipped + :fieldSkipped,
                       imported_tran_codes = :importedTranCodes,
                       updated_at = current_timestamp
                 where task_id = :taskId
                """, new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("importedCount", newCodes.size())
                .addValue("tranInserted", result.tranInserted())
                .addValue("tranUpdated", result.tranUpdated())
                .addValue("fieldInserted", result.fieldInserted())
                .addValue("fieldUpdated", result.fieldUpdated())
                .addValue("fieldSkipped", result.fieldSkipped())
                .addValue("importedTranCodes", serializeCodes(importedCodes)));
    }

    public Set<String> importedTranCodes(long taskId) {
        TransactionListImportTaskRow task = task(taskId);
        if (task == null || !StringUtils.hasText(task.importedTranCodes())) {
            return new LinkedHashSet<>();
        }
        return java.util.Arrays.stream(task.importedTranCodes().split("\\R"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public void markFailed(long taskId, String failureMessage) {
        jdbc.update("""
                update ana_transaction_list_import_task
                   set status = 'FAILED',
                       failure_message = :failureMessage,
                       ended_time = current_timestamp,
                       updated_at = current_timestamp
                 where task_id = :taskId
                """, new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("failureMessage", abbreviate(failureMessage)));
    }

    private String appendFailure(long taskId, String newFailure) {
        TransactionListImportTaskRow task = task(taskId);
        String existing = task == null ? null : task.failureMessage();
        if (!StringUtils.hasText(newFailure)) {
            return abbreviate(existing);
        }
        if (!StringUtils.hasText(existing)) {
            return abbreviate(newFailure);
        }
        return abbreviate(existing + "\n" + newFailure);
    }

    private String serializeCodes(Set<String> codes) {
        return String.join("\n", codes);
    }

    private TransactionListImportTaskRow mapTask(ResultSet rs) throws SQLException {
        return new TransactionListImportTaskRow(
                rs.getLong("task_id"),
                rs.getString("status"),
                rs.getString("original_filename"),
                rs.getString("list_file_path"),
                rs.getInt("total_count"),
                rs.getInt("request_batch_count"),
                rs.getInt("completed_batch_count"),
                rs.getInt("failed_batch_count"),
                rs.getInt("imported_count"),
                rs.getInt("tran_inserted"),
                rs.getInt("tran_updated"),
                rs.getInt("field_inserted"),
                rs.getInt("field_updated"),
                rs.getInt("field_skipped"),
                rs.getString("imported_tran_codes"),
                rs.getString("failure_message"),
                localDateTime(rs.getTimestamp("created_time")),
                localDateTime(rs.getTimestamp("started_time")),
                localDateTime(rs.getTimestamp("ended_time"))
        );
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
        throw new IllegalStateException("Generated transaction list import task id was not returned");
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= MAX_FAILURE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_FAILURE_LENGTH);
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
