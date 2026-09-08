package com.spdb.replay;

import com.spdb.migration.MigrationCommandService;
import com.spdb.migration.MigrationTranCodeCommandForm;
import com.spdb.web.PageRequestParams;
import com.spdb.web.PagedResult;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Read-only volume inspection and persistence for the replay catalog. */
@Service
public class ReplayVolumeCheckService {
    private static final int DEFAULT_SAMPLE_SIZE = 100;
    private static final int LOOKBACK_DAYS = 30;
    private static final Set<String> MESSAGE_TYPES = Set.of("bzjson", "sop", "soap");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final MigrationCommandService migrationCommandService;

    ReplayVolumeCheckService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactionTemplate = null;
        this.migrationCommandService = null;
    }

    public ReplayVolumeCheckService(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, null);
    }

    @Autowired
    public ReplayVolumeCheckService(NamedParameterJdbcTemplate jdbc,
                                    PlatformTransactionManager transactionManager,
                                    MigrationCommandService migrationCommandService) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.migrationCommandService = migrationCommandService;
    }

    public ReplayVolumeCheckResult check() {
        return check(DEFAULT_SAMPLE_SIZE);
    }

    public ReplayVolumeCheckResult startCheck() {
        return check(DEFAULT_SAMPLE_SIZE);
    }

    public ReplayVolumeCheckResult execute(long checkId) {
        return confirm(checkId);
    }

    public boolean canConfirm(long checkId) {
        ReplayVolumeCheckBatch batch = readBatch(checkId);
        return batch != null
                && batch.status() == ReplayVolumeCheckBatchStatus.WAITING_CONFIRM
                && catalogSnapshotMatches(checkId);
    }

    public ReplayVolumeCheckResult confirm(long checkId) {
        ReplayVolumeCheckBatch current = readBatch(checkId);
        if (current == null) {
            throw new IllegalArgumentException("volume check batch not found: " + checkId);
        }
        if (current.status() != ReplayVolumeCheckBatchStatus.WAITING_CONFIRM) {
            throw new IllegalStateException("volume check batch status does not allow confirmation: " + current.status());
        }
        claimForExecution(checkId);

        long actualCleanupRows = 0L;
        try {
            List<ReplayVolumeCleanupDetail> cleanupDetails = readCleanupDetails(checkId);
            for (ReplayVolumeCleanupDetail cleanup : cleanupDetails) {
                try {
                    long deleted = executeCleanup(cleanup);
                    actualCleanupRows += deleted;
                } catch (RuntimeException ex) {
                    markFailed(checkId, actualCleanupRows, cleanup.cleanupId(), ex);
                    throw ex;
                }
            }

            List<String> noVolumeCodes = readNoVolumeCodes(checkId);
            Long migrationCommandId = null;
            if (!noVolumeCodes.isEmpty()) {
                if (migrationCommandService == null) {
                    throw new IllegalStateException("migration command service is unavailable");
                }
                jdbc.update("update ana_replay_volume_check_detail set status='MIGRATION_STARTED', migration_status='MIGRATION_STARTED' where check_id=:checkId and status='NO_VOLUME'",
                        new MapSqlParameterSource("checkId", checkId));
                try {
                    migrationCommandId = migrationCommandService.createTranCodeCommand(new MigrationTranCodeCommandForm(
                            String.join(",", noVolumeCodes), current.sampleSize(), current.lookbackDays(),
                            MigrationTranCodeCommandForm.DEFAULT_PARALLELISM, "回放交易量检查"));
                } catch (RuntimeException migrationFailure) {
                    markMigrationFailed(checkId, migrationFailure);
                    throw migrationFailure;
                }
                jdbc.update("update ana_replay_volume_check_batch set migration_command_id=:commandId where check_id=:checkId",
                        new MapSqlParameterSource().addValue("commandId", migrationCommandId).addValue("checkId", checkId));
                jdbc.update("update ana_replay_volume_check_batch set actual_cleanup_row_count=:actual where check_id=:checkId",
                        new MapSqlParameterSource().addValue("actual", actualCleanupRows).addValue("checkId", checkId));
                return loadResult(checkId);
            }
            LocalDateTime ended = LocalDateTime.now();
            jdbc.update("update ana_replay_volume_check_batch set status='COMPLETED', actual_cleanup_row_count=:actual, ended_time=:ended where check_id=:checkId",
                    new MapSqlParameterSource().addValue("actual", actualCleanupRows).addValue("ended", Timestamp.valueOf(ended)).addValue("checkId", checkId));
            return loadResult(checkId);
        } catch (RuntimeException ex) {
            markFailed(checkId, actualCleanupRows, null, ex);
            throw ex;
        }
    }

    public ReplayVolumeCheckResult refresh(long checkId) {
        ReplayVolumeCheckBatch batch = readBatch(checkId);
        if (batch == null) {
            throw new IllegalArgumentException("volume check batch not found: " + checkId);
        }
        if (batch.status() != ReplayVolumeCheckBatchStatus.EXECUTING || batch.migrationCommandId() == null) {
            return loadResult(checkId);
        }
        if (migrationCommandService == null) {
            throw new IllegalStateException("migration command service is unavailable");
        }
        com.spdb.migration.MigrationProgressRow progress;
        try {
            progress = migrationCommandService.progress(batch.migrationCommandId());
        } catch (RuntimeException progressFailure) {
            markMigrationFailed(checkId, progressFailure);
            markFailed(checkId, batch.actualCleanupRowCount(), null, progressFailure);
            throw progressFailure;
        }
        if (progress == null || "CREATED".equals(progress.status()) || "RUNNING".equals(progress.status()) || "CANCEL_REQUESTED".equals(progress.status())) {
            return loadResult(checkId);
        }
        LocalDateTime ended = LocalDateTime.now();
        if ("COMPLETED".equals(progress.status())) {
            jdbc.update("update ana_replay_volume_check_detail set status='MIGRATION_COMPLETED', migration_status='MIGRATION_COMPLETED' where check_id=:checkId and migration_status='MIGRATION_STARTED'",
                    new MapSqlParameterSource("checkId", checkId));
            jdbc.update("update ana_replay_volume_check_batch set status='COMPLETED', ended_time=:ended where check_id=:checkId and status='EXECUTING'",
                    new MapSqlParameterSource().addValue("ended", Timestamp.valueOf(ended)).addValue("checkId", checkId));
        } else if ("FAILED".equals(progress.status()) || "CANCELLED".equals(progress.status())) {
            String message = progress.errorMessage() == null ? "migration command " + progress.status().toLowerCase() : progress.errorMessage();
            jdbc.update("update ana_replay_volume_check_detail set status='MIGRATION_FAILED', migration_status='MIGRATION_FAILED', error_message=:error where check_id=:checkId and migration_status='MIGRATION_STARTED'",
                    new MapSqlParameterSource().addValue("error", message).addValue("checkId", checkId));
            jdbc.update("update ana_replay_volume_check_batch set status='FAILED', error_message=:error, ended_time=:ended where check_id=:checkId and status='EXECUTING'",
                    new MapSqlParameterSource().addValue("error", message).addValue("ended", Timestamp.valueOf(ended)).addValue("checkId", checkId));
        }
        return loadResult(checkId);
    }

    public ReplayVolumeCheckResult result(long checkId) {
        return loadResult(checkId);
    }

    public ReplayVolumeCheckResult latest() {
        Long id = jdbc.queryForObject("select max(check_id) from ana_replay_volume_check_batch", new MapSqlParameterSource(), Long.class);
        return id == null ? null : loadResult(id);
    }

    public PagedResult<ReplayVolumeCheckBatch> history(PageRequestParams page) {
        Long totalValue = jdbc.queryForObject("select count(*) from ana_replay_volume_check_batch", new MapSqlParameterSource(), Long.class);
        long total = totalValue == null ? 0 : totalValue;
        page = pageForTotal(total, page);
        List<ReplayVolumeCheckBatch> rows = jdbc.query("select * from ana_replay_volume_check_batch order by check_id desc limit :limit offset :offset",
                new MapSqlParameterSource().addValue("limit", page.size()).addValue("offset", offset(total, page)), (rs, n) -> new ReplayVolumeCheckBatch(rs.getLong("check_id"), ReplayVolumeCheckBatchStatus.valueOf(rs.getString("status")), localDateTime(rs.getTimestamp("catalog_snapshot_time")), rs.getInt("sample_size"), rs.getInt("lookback_days"), rs.getLong("catalog_count"), getLong(rs, "has_volume_count"), rs.getLong("no_volume_count"), rs.getLong("cleanup_service_count"), rs.getLong("cleanup_row_count"), rs.getLong("actual_cleanup_row_count"), rs.getObject("migration_command_id", Long.class), localDateTime(rs.getTimestamp("created_time")), localDateTime(rs.getTimestamp("started_time")), localDateTime(rs.getTimestamp("ended_time")), rs.getString("error_message")));
        return PagedResult.of(rows, total, page);
    }

    public PagedResult<ReplayVolumeCheckDetail> details(long checkId, ReplayVolumeCheckDetailStatus status, PageRequestParams page) {
        String filter = status == null ? "" : " and status=:status";
        MapSqlParameterSource countArgs = new MapSqlParameterSource("checkId", checkId).addValue("status", status == null ? null : status.name());
        Long totalValue = jdbc.queryForObject("select count(*) from ana_replay_volume_check_detail where check_id=:checkId" + filter, countArgs, Long.class);
        long total = totalValue == null ? 0 : totalValue;
        page = pageForTotal(total, page);
        MapSqlParameterSource args = countArgs.addValue("limit", page.size()).addValue("offset", offset(total, page));
        List<ReplayVolumeCheckDetail> rows = jdbc.query("select * from ana_replay_volume_check_detail where check_id=:checkId" + filter + " order by detail_id limit :limit offset :offset", args, (rs, n) -> mapDetail(rs));
        return PagedResult.of(rows, total, page);
    }

    public PagedResult<ReplayVolumeCheckDetail> details(long checkId, PageRequestParams page) {
        return details(checkId, null, page);
    }

    /* legacy implementation replaced by status-aware overload */
    private PagedResult<ReplayVolumeCheckDetail> detailsLegacy(long checkId, PageRequestParams page) {
        Long totalValue = jdbc.queryForObject("select count(*) from ana_replay_volume_check_detail where check_id=:checkId", new MapSqlParameterSource("checkId", checkId), Long.class);
        long total = totalValue == null ? 0 : totalValue;
        List<ReplayVolumeCheckDetail> rows = jdbc.query("select * from ana_replay_volume_check_detail where check_id=:checkId order by detail_id limit :limit offset :offset", new MapSqlParameterSource().addValue("checkId", checkId).addValue("limit", page.size()).addValue("offset", page.offset()), (rs, n) -> mapDetail(rs));
        return PagedResult.of(rows, total, page);
    }

    public PagedResult<ReplayVolumeCleanupDetail> cleanupDetails(long checkId, PageRequestParams page) {
        Long totalValue = jdbc.queryForObject("select count(*) from ana_replay_volume_cleanup_detail where check_id=:checkId", new MapSqlParameterSource("checkId", checkId), Long.class);
        long total = totalValue == null ? 0 : totalValue;
        page = pageForTotal(total, page);
        List<ReplayVolumeCleanupDetail> rows = jdbc.query("select * from ana_replay_volume_cleanup_detail where check_id=:checkId order by cleanup_id limit :limit offset :offset", new MapSqlParameterSource().addValue("checkId", checkId).addValue("limit", page.size()).addValue("offset", offset(total, page)), (rs, n) -> new ReplayVolumeCleanupDetail(rs.getLong("cleanup_id"), rs.getLong("check_id"), rs.getString("service_code"), rs.getString("mapped_tran_codes"), rs.getBoolean("catalog_hit"), rs.getLong("pending_cleanup_row_count"), rs.getLong("actual_cleanup_row_count"), ReplayVolumeCleanupStatus.valueOf(rs.getString("status")), rs.getString("error_message"), localDateTime(rs.getTimestamp("created_time")), localDateTime(rs.getTimestamp("started_time")), localDateTime(rs.getTimestamp("ended_time"))));
        return PagedResult.of(rows, total, page);
    }

    private PageRequestParams pageForTotal(long total, PageRequestParams page) {
        PageRequestParams normalized = page == null ? PageRequestParams.of(1, 20) : PageRequestParams.of(page.page(), page.size());
        long pages = total <= 0 ? 1L : (total + normalized.size() - 1L) / normalized.size();
        int last = (int) Math.min(Integer.MAX_VALUE, pages);
        return normalized.page() > last ? new PageRequestParams(last, normalized.size()) : normalized;
    }

    private long offset(long total, PageRequestParams page) {
        return Math.max(0L, ((long) page.page() - 1L) * page.size());
    }

    public ReplayVolumeCheckResult check(int sampleSize) {
        return check(sampleSize, LOOKBACK_DAYS);
    }

    public ReplayVolumeCheckResult check(int sampleSize, int lookbackDays) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("sampleSize must be positive");
        }
        if (lookbackDays <= 0) throw new IllegalArgumentException("lookbackDays must be positive");
        if (transactionTemplate != null) {
            ReplayVolumeCheckResult result = transactionTemplate.execute(status -> checkInTransaction(sampleSize, lookbackDays));
            if (result == null) throw new IllegalStateException("volume check transaction returned no result");
            return result;
        }
        return checkInTransaction(sampleSize, lookbackDays);
    }

    private ReplayVolumeCheckResult checkInTransaction(int sampleSize, int lookbackDays) {
        LocalDateTime now = LocalDateTime.now();
        List<Catalog> catalogs = readCatalog();
        Map<String, Set<String>> requestKeys = readFlows("msg_flow_log_request", lookbackDays);
        Map<String, Set<String>> responseKeys = readFlows("msg_flow_log_response", lookbackDays);
        Map<String, Set<String>> completeByService = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : requestKeys.entrySet()) {
            Set<String> complete = new HashSet<>(entry.getValue());
            complete.retainAll(responseKeys.getOrDefault(entry.getKey(), Set.of()));
            if (!complete.isEmpty()) {
                completeByService.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>()).addAll(complete);
            }
        }

        Map<String, Set<String>> mappings = readMappings();
        Set<String> catalogSet = new HashSet<>();
        for (Catalog c : catalogs) catalogSet.add(c.tranCode());
        Map<String, Long> volumeByTran = new HashMap<>();
        Map<String, Long> mappedServiceCount = new HashMap<>();
        for (Map.Entry<String, Set<String>> mapping : mappings.entrySet()) {
            for (String tranCode : mapping.getValue()) {
                mappedServiceCount.merge(tranCode, 1L, Long::sum);
            }
        }
        List<CleanupDraft> cleanupDrafts = new ArrayList<>();
        for (Map.Entry<String, Set<String>> flow : completeByService.entrySet()) {
            String serviceCode = flow.getKey();
            Set<String> mapped = mappings.getOrDefault(serviceCode, Set.of());
            boolean hit = mapped.stream().anyMatch(catalogSet::contains);
            for (String tranCode : mapped) {
                volumeByTran.merge(tranCode, (long) flow.getValue().size(), Long::sum);
            }
            if (!hit) {
                cleanupDrafts.add(new CleanupDraft(serviceCode, mapped, false, flow.getValue().size()));
            }
        }

        long checkId = insertBatch(now, sampleSize, lookbackDays, catalogs.size());
        List<ReplayVolumeCheckDetail> details = new ArrayList<>();
        for (Catalog c : catalogs) {
            long volume = volumeByTran.getOrDefault(c.tranCode(), 0L);
            long mappedCount = mappedServiceCount.getOrDefault(c.tranCode(), 0L);
            ReplayVolumeCheckDetailStatus status = mappedCount == 0
                    ? ReplayVolumeCheckDetailStatus.NO_MAPPING
                    : volume > 0 ? ReplayVolumeCheckDetailStatus.HAS_VOLUME : ReplayVolumeCheckDetailStatus.NO_VOLUME;
            long detailId = insertDetail(checkId, c, mappedCount, volume, status, now);
            details.add(new ReplayVolumeCheckDetail(detailId, checkId, c.tranCode(), c.tranName(), c.businessDomain(), c.batchType(), c.newCoreTranCode(), c.newTranName(), c.replayRequired(), c.originalServiceSceneCode(), c.newServiceSceneCode(), c.latestTransactionDate(),
                    mappedCount, volume, status, null, null, now));
        }
        List<ReplayVolumeCleanupDetail> cleanupDetails = new ArrayList<>();
        long cleanupRows = 0;
        for (CleanupDraft draft : cleanupDrafts) {
            String mappedCodes = String.join(",", new TreeSet<>(draft.mappedCodes()));
            long cleanupId = insertCleanup(checkId, draft, mappedCodes, now);
            cleanupRows += draft.pendingRows();
            cleanupDetails.add(new ReplayVolumeCleanupDetail(cleanupId, checkId, draft.serviceCode(), mappedCodes, false,
                    draft.pendingRows(), 0L, ReplayVolumeCleanupStatus.PENDING, null, now, null, null));
        }
        long noVolume = details.stream().filter(d -> d.status() == ReplayVolumeCheckDetailStatus.NO_VOLUME).count();
        long hasVolume = details.stream().filter(d -> d.status() == ReplayVolumeCheckDetailStatus.HAS_VOLUME).count();
        try {
            jdbc.update("update ana_replay_volume_check_batch set status='WAITING_CONFIRM', has_volume_count=:hasVolume, no_volume_count=:noVolume, cleanup_service_count=:cleanupServices, cleanup_row_count=:cleanupRows, ended_time=:ended where check_id=:checkId",
                    new MapSqlParameterSource().addValue("hasVolume", hasVolume).addValue("noVolume", noVolume).addValue("cleanupServices", cleanupDetails.size()).addValue("cleanupRows", cleanupRows).addValue("ended", Timestamp.valueOf(now)).addValue("checkId", checkId));
        } catch (org.springframework.jdbc.BadSqlGrammarException legacySchema) {
            jdbc.update("update ana_replay_volume_check_batch set status='WAITING_CONFIRM', no_volume_count=:noVolume, cleanup_service_count=:cleanupServices, cleanup_row_count=:cleanupRows, ended_time=:ended where check_id=:checkId",
                    new MapSqlParameterSource().addValue("noVolume", noVolume).addValue("cleanupServices", cleanupDetails.size()).addValue("cleanupRows", cleanupRows).addValue("ended", Timestamp.valueOf(now)).addValue("checkId", checkId));
        }
        ReplayVolumeCheckBatch batch = new ReplayVolumeCheckBatch(checkId, ReplayVolumeCheckBatchStatus.WAITING_CONFIRM, now, sampleSize, lookbackDays,
                catalogs.size(), hasVolume, noVolume, cleanupDetails.size(), cleanupRows, 0L, null, now, now, now, null);
        return new ReplayVolumeCheckResult(batch, details, cleanupDetails);
    }

    private List<Catalog> readCatalog() {
        return jdbc.query("select tran_code, tran_name, business_domain, batch_type, new_core_tran_code, new_tran_name, replay_required, original_service_scene_code, new_service_scene_code, latest_transaction_date from ana_replay_transaction_catalog where replay_required='是' order by tran_code",
                new MapSqlParameterSource(), (rs, n) -> catalog(rs));
    }

    private Map<String, Set<String>> readFlows(String table, int lookbackDays) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("select source_ip, trans_id, txn_code from " + table, new MapSqlParameterSource())) {
                    String txnCode = (String) row.get("txn_code");
                    ParsedTxn parsed = parseTxnCode(txnCode);
                    if (parsed != null) {
                        result.computeIfAbsent(parsed.serviceCode(), ignored -> new HashSet<>())
                                .add(row.get("source_ip") + "\u0000" + row.get("trans_id"));
                    }
        }
        return result;
    }

    private Map<String, Set<String>> readMappings() {
        Map<String, Set<String>> online = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("select tran_code, esf_service_code from tp_online_service_in", new MapSqlParameterSource())) {
            String code = normalizeServiceCode((String) row.get("esf_service_code"));
            if (code != null) online.computeIfAbsent(code, ignored -> new LinkedHashSet<>()).add((String) row.get("tran_code"));
        }
        Map<String, Set<String>> fallback = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("select tran_code, \"528_service_code\" as service_code from ana_tran_code_service_mapping", new MapSqlParameterSource())) {
            String code = normalizeServiceCode((String) row.get("service_code"));
            if (code != null) fallback.computeIfAbsent(code, ignored -> new LinkedHashSet<>()).add((String) row.get("tran_code"));
        }
        Map<String, Set<String>> result = new HashMap<>();
        Set<String> services = new HashSet<>(online.keySet());
        services.addAll(fallback.keySet());
        for (String service : services) result.put(service, online.containsKey(service) ? online.get(service) : fallback.getOrDefault(service, Set.of()));
        return result;
    }

    private ReplayVolumeCheckBatch readBatch(long checkId) {
        List<ReplayVolumeCheckBatch> rows = jdbc.query("select * from ana_replay_volume_check_batch where check_id=:checkId",
                new MapSqlParameterSource("checkId", checkId), (rs, n) -> new ReplayVolumeCheckBatch(
                        rs.getLong("check_id"), ReplayVolumeCheckBatchStatus.valueOf(rs.getString("status")),
                        localDateTime(rs.getTimestamp("catalog_snapshot_time")), rs.getInt("sample_size"), rs.getInt("lookback_days"),
                        rs.getLong("catalog_count"), getLong(rs, "has_volume_count"), rs.getLong("no_volume_count"), rs.getLong("cleanup_service_count"),
                        rs.getLong("cleanup_row_count"), rs.getLong("actual_cleanup_row_count"),
                        rs.getObject("migration_command_id", Long.class), localDateTime(rs.getTimestamp("created_time")),
                        localDateTime(rs.getTimestamp("started_time")), localDateTime(rs.getTimestamp("ended_time")), rs.getString("error_message")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void claimForExecution(long checkId) {
        Runnable claim = () -> {
            jdbc.query("select tran_code from ana_replay_transaction_catalog for update", new MapSqlParameterSource(), (rs, n) -> rs.getString("tran_code"));
            if (!catalogSnapshotMatches(checkId)) {
                throw new IllegalStateException("catalog snapshot has changed; please run a new check");
            }
            int claimed = jdbc.update("update ana_replay_volume_check_batch set status='EXECUTING', started_time=:started, error_message=null where check_id=:checkId and status='WAITING_CONFIRM'",
                    new MapSqlParameterSource().addValue("started", Timestamp.valueOf(LocalDateTime.now())).addValue("checkId", checkId));
            if (claimed != 1) {
                throw new IllegalStateException("volume check batch status does not allow confirmation");
            }
        };
        if (transactionTemplate == null) {
            claim.run();
        } else {
            transactionTemplate.executeWithoutResult(status -> claim.run());
        }
    }

    private static long getLong(ResultSet rs, String column) {
        try { return rs.getLong(column); } catch (SQLException ignored) { return 0L; }
    }

    private boolean catalogSnapshotMatches(long checkId) {
        Map<String, Catalog> current = new LinkedHashMap<>();
        jdbc.query("select tran_code, tran_name, business_domain, batch_type, new_core_tran_code, new_tran_name, replay_required, original_service_scene_code, new_service_scene_code, latest_transaction_date from ana_replay_transaction_catalog where replay_required='是'",
                new MapSqlParameterSource(), (rs, n) -> {
                    current.put(rs.getString("tran_code"), catalog(rs));
                    return null;
                });
        Map<String, Catalog> snapshot = new LinkedHashMap<>();
        jdbc.query("select tran_code, tran_name, business_domain, batch_type, new_core_tran_code, new_tran_name, replay_required, original_service_scene_code, new_service_scene_code, latest_transaction_date from ana_replay_volume_check_detail where check_id=:checkId",
                new MapSqlParameterSource("checkId", checkId), (rs, n) -> {
                    snapshot.put(rs.getString("tran_code"), catalog(rs));
                    return null;
                });
        if (!current.equals(snapshot)) {
            return false;
        }
        ReplayVolumeCheckBatch batch = readBatch(checkId);
        LocalDateTime snapshotTime = batch == null ? null : batch.catalogSnapshotTime();
        if (snapshotTime == null) {
            return false;
        }
        Timestamp latest = jdbc.queryForObject("select max(updated_at) from ana_replay_transaction_catalog", new MapSqlParameterSource(), Timestamp.class);
        return latest == null || !latest.toLocalDateTime().isAfter(snapshotTime);
    }

    private List<ReplayVolumeCleanupDetail> readCleanupDetails(long checkId) {
        return jdbc.query("select * from ana_replay_volume_cleanup_detail where check_id=:checkId order by cleanup_id",
                new MapSqlParameterSource("checkId", checkId), (rs, n) -> new ReplayVolumeCleanupDetail(
                        rs.getLong("cleanup_id"), rs.getLong("check_id"), rs.getString("service_code"), rs.getString("mapped_tran_codes"),
                        rs.getBoolean("catalog_hit"), rs.getLong("pending_cleanup_row_count"), rs.getLong("actual_cleanup_row_count"),
                        ReplayVolumeCleanupStatus.valueOf(rs.getString("status")), rs.getString("error_message"),
                        localDateTime(rs.getTimestamp("created_time")), localDateTime(rs.getTimestamp("started_time")), localDateTime(rs.getTimestamp("ended_time"))));
    }

    private List<String> readNoVolumeCodes(long checkId) {
        return jdbc.queryForList("select tran_code from ana_replay_volume_check_detail where check_id=:checkId and status='NO_VOLUME' order by tran_code",
                new MapSqlParameterSource("checkId", checkId), String.class);
    }

    private long executeCleanup(ReplayVolumeCleanupDetail cleanup) {
        if (transactionTemplate == null) {
            return executeCleanupInTransaction(cleanup);
        }
        Long deleted = transactionTemplate.execute(status -> executeCleanupInTransaction(cleanup));
        return deleted == null ? 0L : deleted;
    }

    private long executeCleanupInTransaction(ReplayVolumeCleanupDetail cleanup) {
        LocalDateTime started = LocalDateTime.now();
        jdbc.update("update ana_replay_volume_cleanup_detail set status='EXECUTING', started_time=:started, error_message=null where cleanup_id=:cleanupId and status='PENDING'",
                new MapSqlParameterSource().addValue("started", Timestamp.valueOf(started)).addValue("cleanupId", cleanup.cleanupId()));
        Map<String, Set<String>> requestRows = readTxnRows("msg_flow_log_request").getOrDefault(cleanup.serviceCode(), Map.of());
        Map<String, Set<String>> responseRows = readTxnRows("msg_flow_log_response").getOrDefault(cleanup.serviceCode(), Map.of());
        Set<String> requestKeys = requestRows.keySet();
        Set<String> responseKeys = responseRows.keySet();
        Set<String> completeKeys = new HashSet<>(requestKeys);
        completeKeys.retainAll(responseKeys);
        long deleted = 0L;
        for (String key : completeKeys) {
            String[] parts = key.split("\\u0000", -1);
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("sourceIp", parts[0]).addValue("transId", parts[1]);
            params.addValue("responseTxnCodes", responseRows.get(key));
            params.addValue("requestTxnCodes", requestRows.get(key));
            int responseDeleted = jdbc.update("delete from msg_flow_log_response where source_ip=:sourceIp and trans_id=:transId and txn_code in (:responseTxnCodes)", params);
            int requestDeleted = jdbc.update("delete from msg_flow_log_request where source_ip=:sourceIp and trans_id=:transId and txn_code in (:requestTxnCodes)", params);
            if (responseDeleted > 0 && requestDeleted > 0) deleted++;
        }
        LocalDateTime ended = LocalDateTime.now();
        jdbc.update("update ana_replay_volume_cleanup_detail set status='COMPLETED', actual_cleanup_row_count=:actual, ended_time=:ended where cleanup_id=:cleanupId",
                new MapSqlParameterSource().addValue("actual", deleted).addValue("ended", Timestamp.valueOf(ended)).addValue("cleanupId", cleanup.cleanupId()));
        return deleted;
    }

    private void markFailed(long checkId, long actualCleanupRows, Long cleanupId, RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        if (cleanupId != null) {
            jdbc.update("update ana_replay_volume_cleanup_detail set status='FAILED', error_message=:error, ended_time=:ended where cleanup_id=:cleanupId",
                    new MapSqlParameterSource().addValue("error", message).addValue("ended", Timestamp.valueOf(LocalDateTime.now())).addValue("cleanupId", cleanupId));
        }
        jdbc.update("update ana_replay_volume_check_batch set status='FAILED', actual_cleanup_row_count=:actual, error_message=:error, ended_time=:ended where check_id=:checkId",
                new MapSqlParameterSource().addValue("actual", actualCleanupRows).addValue("error", message).addValue("ended", Timestamp.valueOf(LocalDateTime.now())).addValue("checkId", checkId));
    }

    private void markMigrationFailed(long checkId, RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        jdbc.update("update ana_replay_volume_check_detail set status='MIGRATION_FAILED', migration_status='MIGRATION_FAILED', error_message=:error where check_id=:checkId and status in ('NO_VOLUME','MIGRATION_STARTED')",
                new MapSqlParameterSource().addValue("error", message).addValue("checkId", checkId));
    }

    private ReplayVolumeCheckResult loadResult(long checkId) {
        ReplayVolumeCheckBatch batch = readBatch(checkId);
        List<ReplayVolumeCheckDetail> details = jdbc.query("select * from ana_replay_volume_check_detail where check_id=:checkId order by detail_id",
                new MapSqlParameterSource("checkId", checkId), (rs, n) -> mapDetail(rs));
        return new ReplayVolumeCheckResult(batch, details, readCleanupDetails(checkId));
    }

    private ReplayVolumeCheckDetail mapDetail(ResultSet rs) throws SQLException {
        return new ReplayVolumeCheckDetail(rs.getLong("detail_id"), rs.getLong("check_id"), rs.getString("tran_code"), rs.getString("tran_name"), rs.getString("business_domain"), rs.getString("batch_type"), rs.getString("new_core_tran_code"), rs.getString("new_tran_name"), rs.getString("replay_required"), rs.getString("original_service_scene_code"), rs.getString("new_service_scene_code"), rs.getString("latest_transaction_date"), rs.getLong("mapped_service_count"), rs.getLong("complete_volume_count"), ReplayVolumeCheckDetailStatus.valueOf(rs.getString("status")), rs.getString("migration_status") == null ? null : ReplayVolumeMigrationStatus.valueOf(rs.getString("migration_status")), rs.getString("error_message"), localDateTime(rs.getTimestamp("created_time")));
    }

    private Map<String, Map<String, Set<String>>> readTxnRows(String table) {
        Map<String, Map<String, Set<String>>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("select source_ip, trans_id, txn_code from " + table, new MapSqlParameterSource())) {
            String txnCode = (String) row.get("txn_code");
            ParsedTxn parsed = parseTxnCode(txnCode);
            if (parsed != null) {
                String key = row.get("source_ip") + "\u0000" + row.get("trans_id");
                result.computeIfAbsent(parsed.serviceCode(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(txnCode);
            }
        }
        return result;
    }

    private LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private ParsedTxn parseTxnCode(String value) {
        if (value == null) return null;
        int index = value.lastIndexOf('&');
        if (index <= 0 || index == value.length() - 1) return null;
        String type = value.substring(index + 1).trim().toLowerCase();
        if (!MESSAGE_TYPES.contains(type)) return null;
        String serviceCode = normalizeServiceCode(value.substring(0, index));
        return serviceCode == null ? null : new ParsedTxn(serviceCode, type);
    }

    private String normalizeServiceCode(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase().replace(".", "");
        return normalized.isEmpty() ? null : normalized;
    }

    private long insertBatch(LocalDateTime now, int sampleSize, int lookbackDays, int catalogCount) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update("insert into ana_replay_volume_check_batch(status,catalog_snapshot_time,sample_size,lookback_days,catalog_count,created_time,started_time) values ('CHECKING',:snapshot,:sampleSize,:lookback,:catalogCount,:created,:started)",
                new MapSqlParameterSource().addValue("snapshot", Timestamp.valueOf(now)).addValue("sampleSize", sampleSize).addValue("lookback", lookbackDays)
                        .addValue("catalogCount", catalogCount).addValue("created", Timestamp.valueOf(now)).addValue("started", Timestamp.valueOf(now)), holder, new String[]{"check_id"});
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("check batch key was not generated");
        return key.longValue();
    }

    private long insertDetail(long checkId, Catalog c, long mappedCount, long volume, ReplayVolumeCheckDetailStatus status, LocalDateTime now) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update("insert into ana_replay_volume_check_detail(check_id,tran_code,tran_name,business_domain,batch_type,new_core_tran_code,new_tran_name,replay_required,original_service_scene_code,new_service_scene_code,latest_transaction_date,mapped_service_count,complete_volume_count,status,created_time) values (:checkId,:tranCode,:tranName,:domain,:batch,:newCore,:newName,:replay,:original,:newScene,:latest,:mapped,:volume,:status,:created)",
                new MapSqlParameterSource().addValue("checkId", checkId).addValue("tranCode", c.tranCode()).addValue("tranName", c.tranName()).addValue("domain", c.businessDomain()).addValue("batch", c.batchType()).addValue("newCore", c.newCoreTranCode()).addValue("newName", c.newTranName()).addValue("replay", c.replayRequired()).addValue("original", c.originalServiceSceneCode()).addValue("newScene", c.newServiceSceneCode()).addValue("latest", c.latestTransactionDate()).addValue("mapped", mappedCount).addValue("volume", volume).addValue("status", status.name()).addValue("created", Timestamp.valueOf(now)), holder, new String[]{"detail_id"});
        return holder.getKey().longValue();
    }

    private long insertCleanup(long checkId, CleanupDraft d, String mappedCodes, LocalDateTime now) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update("insert into ana_replay_volume_cleanup_detail(check_id,service_code,mapped_tran_codes,catalog_hit,pending_cleanup_row_count,actual_cleanup_row_count,status,created_time) values (:checkId,:service,:mappedCodes,false,:pending,0,'PENDING',:created)",
                new MapSqlParameterSource().addValue("checkId", checkId).addValue("service", d.serviceCode()).addValue("mappedCodes", mappedCodes).addValue("pending", d.pendingRows()).addValue("created", Timestamp.valueOf(now)), holder, new String[]{"cleanup_id"});
        return holder.getKey().longValue();
    }

    private Catalog catalog(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Catalog(rs.getString("tran_code"), rs.getString("tran_name"), rs.getString("business_domain"), rs.getString("batch_type"), rs.getString("new_core_tran_code"), rs.getString("new_tran_name"), rs.getString("replay_required"), rs.getString("original_service_scene_code"), rs.getString("new_service_scene_code"), rs.getString("latest_transaction_date"));
    }

    private record Catalog(String tranCode, String tranName, String businessDomain, String batchType,
                           String newCoreTranCode, String newTranName, String replayRequired,
                           String originalServiceSceneCode, String newServiceSceneCode, String latestTransactionDate) {}
    private record ParsedTxn(String serviceCode, String messageType) {}
    private record CleanupDraft(String serviceCode, Set<String> mappedCodes, boolean catalogHit, long pendingRows) {}
}
