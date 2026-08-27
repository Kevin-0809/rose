package com.spdb.replay;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
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

    public ReplayVolumeCheckService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReplayVolumeCheckResult check() {
        return check(DEFAULT_SAMPLE_SIZE);
    }

    public ReplayVolumeCheckResult startCheck() {
        return check(DEFAULT_SAMPLE_SIZE);
    }

    public ReplayVolumeCheckResult check(int sampleSize) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("sampleSize must be positive");
        }
        LocalDateTime now = LocalDateTime.now();
        List<Catalog> catalogs = readCatalog();
        Map<String, Set<String>> requestKeys = readFlows("msg_flow_log_request");
        Map<String, Set<String>> responseKeys = readFlows("msg_flow_log_response");
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

        long checkId = insertBatch(now, sampleSize, catalogs.size());
        List<ReplayVolumeCheckDetail> details = new ArrayList<>();
        for (Catalog c : catalogs) {
            long volume = volumeByTran.getOrDefault(c.tranCode(), 0L);
            long mappedCount = mappedServiceCount.getOrDefault(c.tranCode(), 0L);
            ReplayVolumeCheckDetailStatus status = mappedCount == 0
                    ? ReplayVolumeCheckDetailStatus.NO_MAPPING
                    : volume > 0 ? ReplayVolumeCheckDetailStatus.HAS_VOLUME : ReplayVolumeCheckDetailStatus.NO_VOLUME;
            long detailId = insertDetail(checkId, c, mappedCount, volume, status, now);
            details.add(new ReplayVolumeCheckDetail(detailId, checkId, c.tranCode(), c.tranName(), c.businessDomain(), c.batchType(),
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
        jdbc.update("update ana_replay_volume_check_batch set status='WAITING_CONFIRM', no_volume_count=:noVolume, cleanup_service_count=:cleanupServices, cleanup_row_count=:cleanupRows, ended_time=:ended where check_id=:checkId",
                new MapSqlParameterSource().addValue("noVolume", noVolume).addValue("cleanupServices", cleanupDetails.size())
                        .addValue("cleanupRows", cleanupRows).addValue("ended", Timestamp.valueOf(now)).addValue("checkId", checkId));
        ReplayVolumeCheckBatch batch = new ReplayVolumeCheckBatch(checkId, ReplayVolumeCheckBatchStatus.WAITING_CONFIRM, now, sampleSize, LOOKBACK_DAYS,
                catalogs.size(), noVolume, cleanupDetails.size(), cleanupRows, 0L, null, now, now, now, null);
        return new ReplayVolumeCheckResult(batch, details, cleanupDetails);
    }

    private List<Catalog> readCatalog() {
        return jdbc.query("select tran_code, tran_name, business_domain, batch_type from ana_replay_transaction_catalog order by tran_code",
                new MapSqlParameterSource(), (rs, n) -> new Catalog(rs.getString("tran_code"), rs.getString("tran_name"), rs.getString("business_domain"), rs.getString("batch_type")));
    }

    private Map<String, Set<String>> readFlows(String table) {
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
            String code = (String) row.get("esf_service_code");
            if (code != null) online.computeIfAbsent(code.replace(".", ""), ignored -> new LinkedHashSet<>()).add((String) row.get("tran_code"));
        }
        Map<String, Set<String>> fallback = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("select tran_code, \"528_service_code\" as service_code from ana_tran_code_service_mapping", new MapSqlParameterSource())) {
            fallback.computeIfAbsent((String) row.get("service_code"), ignored -> new LinkedHashSet<>()).add((String) row.get("tran_code"));
        }
        Map<String, Set<String>> result = new HashMap<>();
        Set<String> services = new HashSet<>(online.keySet());
        services.addAll(fallback.keySet());
        for (String service : services) result.put(service, online.containsKey(service) ? online.get(service) : fallback.getOrDefault(service, Set.of()));
        return result;
    }

    private ParsedTxn parseTxnCode(String value) {
        if (value == null) return null;
        int index = value.lastIndexOf('&');
        if (index <= 0 || index == value.length() - 1) return null;
        String type = value.substring(index + 1).toLowerCase();
        if (!MESSAGE_TYPES.contains(type)) return null;
        return new ParsedTxn(value.substring(0, index), type);
    }

    private long insertBatch(LocalDateTime now, int sampleSize, int catalogCount) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update("insert into ana_replay_volume_check_batch(status,catalog_snapshot_time,sample_size,lookback_days,catalog_count,created_time,started_time) values ('CHECKING',:snapshot,:sampleSize,:lookback,:catalogCount,:created,:started)",
                new MapSqlParameterSource().addValue("snapshot", Timestamp.valueOf(now)).addValue("sampleSize", sampleSize).addValue("lookback", LOOKBACK_DAYS)
                        .addValue("catalogCount", catalogCount).addValue("created", Timestamp.valueOf(now)).addValue("started", Timestamp.valueOf(now)), holder, new String[]{"check_id"});
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("check batch key was not generated");
        return key.longValue();
    }

    private long insertDetail(long checkId, Catalog c, long mappedCount, long volume, ReplayVolumeCheckDetailStatus status, LocalDateTime now) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update("insert into ana_replay_volume_check_detail(check_id,tran_code,tran_name,business_domain,batch_type,mapped_service_count,complete_volume_count,status,created_time) values (:checkId,:tranCode,:tranName,:domain,:batch,:mapped,:volume,:status,:created)",
                new MapSqlParameterSource().addValue("checkId", checkId).addValue("tranCode", c.tranCode()).addValue("tranName", c.tranName()).addValue("domain", c.businessDomain()).addValue("batch", c.batchType()).addValue("mapped", mappedCount).addValue("volume", volume).addValue("status", status.name()).addValue("created", Timestamp.valueOf(now)), holder, new String[]{"detail_id"});
        return holder.getKey().longValue();
    }

    private long insertCleanup(long checkId, CleanupDraft d, String mappedCodes, LocalDateTime now) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update("insert into ana_replay_volume_cleanup_detail(check_id,service_code,mapped_tran_codes,catalog_hit,pending_cleanup_row_count,actual_cleanup_row_count,status,created_time) values (:checkId,:service,:mappedCodes,false,:pending,0,'PENDING',:created)",
                new MapSqlParameterSource().addValue("checkId", checkId).addValue("service", d.serviceCode()).addValue("mappedCodes", mappedCodes).addValue("pending", d.pendingRows()).addValue("created", Timestamp.valueOf(now)), holder, new String[]{"cleanup_id"});
        return holder.getKey().longValue();
    }

    private record Catalog(String tranCode, String tranName, String businessDomain, String batchType) {}
    private record ParsedTxn(String serviceCode, String messageType) {}
    private record CleanupDraft(String serviceCode, Set<String> mappedCodes, boolean catalogHit, long pendingRows) {}
}
