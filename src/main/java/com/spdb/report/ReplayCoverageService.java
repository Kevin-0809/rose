package com.spdb.report;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ReplayCoverageService {
    private final NamedParameterJdbcTemplate jdbc;

    public ReplayCoverageService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void materialize(String batchId, String reportDate) {
        if (!tableExists()) return;
        MapSqlParameterSource batch = new MapSqlParameterSource("batchId", batchId).addValue("reportDate", reportDate);
        jdbc.update("delete from ana_report_export_replay_coverage where batch_id=:batchId", batch);
        jdbc.getJdbcTemplate().query("select tran_code, tran_name, business_domain, new_service_scene_code, replay_required, latest_transaction_date from ana_replay_transaction_catalog order by tran_code", rs -> {
            String tranCode = rs.getString("tran_code");
            List<String> services = new ArrayList<>();
            String direct = rs.getString("new_service_scene_code");
            if (direct != null && !direct.isBlank()) services.add(direct.trim());
            else jdbc.query("select distinct service_code from ana_tran_catalog where tran_code=:tranCode order by service_code", new MapSqlParameterSource("tranCode", tranCode), (RowCallbackHandler) serviceRs -> services.add(serviceRs.getString("service_code")));
            String resolved = String.join(",", services);
            long sent = services.stream().mapToLong(service -> sentCount(batchId, service)).sum();
            String mapping = services.isEmpty() ? "UNMAPPED" : (direct == null || direct.isBlank() ? "CATALOG_MATCHED" : "DIRECT");
            String status = services.isEmpty() ? "缺少S码映射" : (sent > 0 ? "已发送" : "未发送");
            String reason = sent > 0 ? null : unsentReason(rs.getString("replay_required"), rs.getString("latest_transaction_date"));
            String owner = services.isEmpty() ? null : owner(services.get(0));
            String internalOwner = services.isEmpty() ? null : internalOwner(services.get(0));
            jdbc.update("""
                    insert into ana_report_export_replay_coverage(batch_id,report_date,tran_code,tran_name,business_domain,new_service_scene_code,
                      resolved_service_codes,service_mapping_status,replay_required,latest_transaction_date,sent_transaction_count,coverage_status,unsent_reason,owner,internal_owner)
                    values (:batchId,:reportDate,:tranCode,:tranName,:domain,:direct,:resolved,:mapping,:replay,:latest,:sent,:status,:reason,:owner,:internalOwner)
                    """, batch.addValue("tranCode", tranCode).addValue("tranName", rs.getString("tran_name"))
                    .addValue("domain", rs.getString("business_domain")).addValue("direct", direct).addValue("resolved", resolved)
                    .addValue("mapping", mapping).addValue("replay", rs.getString("replay_required"))
                    .addValue("latest", rs.getString("latest_transaction_date")).addValue("sent", sent)
                    .addValue("status", status).addValue("reason", reason).addValue("owner", owner).addValue("internalOwner", internalOwner));
        });
    }

    private String owner(String service) { return ownerField(service, "owner"); }
    private String internalOwner(String service) { return ownerField(service, "internal_owner"); }
    private String ownerField(String service, String field) {
        String sql = "select " + field + " from ana_tran_catalog where service_code=:service limit 1";
        return jdbc.query(sql, new MapSqlParameterSource("service", service), rs -> rs.next() ? rs.getString(1) : null);
    }

    private boolean tableExists() {
        try {
            Integer count = jdbc.getJdbcTemplate().queryForObject("select count(*) from information_schema.tables where lower(table_name)='ana_report_export_replay_coverage'", Integer.class);
            return count != null && count > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private long sentCount(String batchId, String service) {
        Long count = jdbc.queryForObject("select coalesce(sum(sent_transaction_count),0) from ana_report_export_interface_summary where batch_id=:batchId and service_code=:service", new MapSqlParameterSource("batchId", batchId).addValue("service", service), Long.class);
        return count == null ? 0L : count;
    }

    static String unsentReason(String replayRequired, String latestDate) {
        if (!"是".equals(replayRequired)) return "不回放";
        return latestDate == null || latestDate.isBlank() || latestDate.compareTo("20260717") <= 0
                ? "近期无交易" : "待分析";
    }
}
