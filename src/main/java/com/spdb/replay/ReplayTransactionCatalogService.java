package com.spdb.replay;

import com.spdb.web.PageRequestParams;
import com.spdb.web.PagedResult;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReplayTransactionCatalogService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private final NamedParameterJdbcTemplate jdbc;

    public ReplayTransactionCatalogService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PagedResult<ReplayTransactionCatalogRow> search(ReplayTransactionCatalogSearch criteria, PageRequestParams page) {
        ReplayTransactionCatalogSearch c = criteria == null ? new ReplayTransactionCatalogSearch(null, null, null, null, null) : criteria;
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        like(clauses, params, "tran_code", "tranCode", c.tranCode());
        like(clauses, params, "tran_name", "tranName", c.tranName());
        like(clauses, params, "business_domain", "businessDomain", c.businessDomain());
        eq(clauses, params, "batch_type", "batchType", c.batchType());
        eq(clauses, params, "replay_required", "replayRequired", c.replayRequired());
        String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
        params.addValue("limit", page.size()).addValue("offset", page.offset());
        List<ReplayTransactionCatalogRow> rows = jdbc.query("""
                select tran_code, tran_name, business_domain, batch_type, new_core_tran_code, new_tran_name,
                       replay_required, original_service_scene_code, new_service_scene_code, latest_transaction_date,
                       created_at, updated_at
                from ana_replay_transaction_catalog""" + where + " order by tran_code limit :limit offset :offset", params,
                (rs, i) -> new ReplayTransactionCatalogRow(rs.getString("tran_code"), rs.getString("tran_name"),
                        rs.getString("business_domain"), rs.getString("batch_type"), rs.getString("new_core_tran_code"),
                        rs.getString("new_tran_name"), rs.getString("replay_required"), rs.getString("original_service_scene_code"),
                        rs.getString("new_service_scene_code"), rs.getString("latest_transaction_date"),
                        rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class)));
        Long total = jdbc.queryForObject("select count(*) from ana_replay_transaction_catalog" + where, params, Long.class);
        return PagedResult.of(rows, total == null ? 0 : total, page);
    }

    public ReplayTransactionCatalogRow find(String tranCode) {
        if (!StringUtils.hasText(tranCode)) return null;
        List<ReplayTransactionCatalogRow> rows = jdbc.query("select tran_code, tran_name, business_domain, batch_type, new_core_tran_code, new_tran_name, replay_required, original_service_scene_code, new_service_scene_code, latest_transaction_date, created_at, updated_at from ana_replay_transaction_catalog where tran_code = :tranCode", new MapSqlParameterSource("tranCode", tranCode.trim()),
                (rs, i) -> new ReplayTransactionCatalogRow(rs.getString("tran_code"), rs.getString("tran_name"), rs.getString("business_domain"), rs.getString("batch_type"), rs.getString("new_core_tran_code"), rs.getString("new_tran_name"), rs.getString("replay_required"), rs.getString("original_service_scene_code"), rs.getString("new_service_scene_code"), rs.getString("latest_transaction_date"), rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ReplayTransactionCatalogForm form(String tranCode) {
        ReplayTransactionCatalogRow row = find(tranCode);
        return row == null ? new ReplayTransactionCatalogForm(null, null, null, null, null, null, null, null, null, null, null)
                : new ReplayTransactionCatalogForm(row.tranCode(), row.tranName(), row.businessDomain(), row.batchType(), row.newCoreTranCode(), row.newTranName(), row.replayRequired(), row.originalServiceSceneCode(), row.newServiceSceneCode(), row.latestTransactionDate(), row.updatedAt());
    }

    public void save(ReplayTransactionCatalogForm form) { save(form, null); }

    public void save(ReplayTransactionCatalogForm form, String pathTranCode) {
        if (form == null) throw new IllegalArgumentException("表单不能为空");
        String code = StringUtils.hasText(pathTranCode) ? pathTranCode.trim() : trim(form.tranCode());
        if (!StringUtils.hasText(code)) throw new IllegalArgumentException("交易码不能为空");
        validate(form);
        MapSqlParameterSource p = params(form).addValue("tranCode", code);
        if (StringUtils.hasText(pathTranCode)) {
            int updated = jdbc.update("update ana_replay_transaction_catalog set tran_name=:tranName,business_domain=:businessDomain,batch_type=:batchType,new_core_tran_code=:newCoreTranCode,new_tran_name=:newTranName,replay_required=:replayRequired,original_service_scene_code=:originalServiceSceneCode,new_service_scene_code=:newServiceSceneCode,latest_transaction_date=:latestTransactionDate,updated_at=current_timestamp where tran_code=:tranCode", p);
            if (updated == 0) throw new IllegalArgumentException("交易码不存在: " + code);
        } else {
            jdbc.update("insert into ana_replay_transaction_catalog (tran_code,tran_name,business_domain,batch_type,new_core_tran_code,new_tran_name,replay_required,original_service_scene_code,new_service_scene_code,latest_transaction_date) values (:tranCode,:tranName,:businessDomain,:batchType,:newCoreTranCode,:newTranName,:replayRequired,:originalServiceSceneCode,:newServiceSceneCode,:latestTransactionDate)", p);
        }
    }

    public void save(String pathTranCode, ReplayTransactionCatalogForm form) { save(form, pathTranCode); }

    public void delete(String tranCode) {
        if (StringUtils.hasText(tranCode)) jdbc.update("delete from ana_replay_transaction_catalog where tran_code=:tranCode", new MapSqlParameterSource("tranCode", tranCode.trim()));
    }

    private MapSqlParameterSource params(ReplayTransactionCatalogForm f) {
        return new MapSqlParameterSource().addValue("tranName", trim(f.tranName())).addValue("businessDomain", trim(f.businessDomain())).addValue("batchType", trim(f.batchType())).addValue("newCoreTranCode", trim(f.newCoreTranCode())).addValue("newTranName", trim(f.newTranName())).addValue("replayRequired", trim(f.replayRequired())).addValue("originalServiceSceneCode", trim(f.originalServiceSceneCode())).addValue("newServiceSceneCode", trim(f.newServiceSceneCode())).addValue("latestTransactionDate", trim(f.latestTransactionDate()));
    }

    private void validate(ReplayTransactionCatalogForm f) {
        String replay = trim(f.replayRequired());
        if (!replay.isEmpty() && !List.of("是", "否").contains(replay)) throw new IllegalArgumentException("回放要求只能为空、是或否");
        String batch = trim(f.batchType());
        if (!batch.isEmpty() && !List.of("查询", "动账").contains(batch)) throw new IllegalArgumentException("批次类型只能为空、查询或动账");
        String date = trim(f.latestTransactionDate());
        if (!date.isEmpty()) {
            try { LocalDate.parse(date, DATE); } catch (DateTimeParseException e) { throw new IllegalArgumentException("最近交易日期必须为合法yyyyMMdd", e); }
        }
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static void like(List<String> clauses, MapSqlParameterSource p, String column, String name, String value) { if (StringUtils.hasText(value)) { clauses.add(column + " like :" + name); p.addValue(name, "%" + value.trim() + "%"); } }
    private static void eq(List<String> clauses, MapSqlParameterSource p, String column, String name, String value) { if (StringUtils.hasText(value)) { clauses.add(column + " = :" + name); p.addValue(name, value.trim()); } }
}
