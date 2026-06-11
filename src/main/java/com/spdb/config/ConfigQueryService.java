package com.spdb.config;

import com.spdb.domain.FieldMapping;
import com.spdb.domain.TranCatalog;
import com.spdb.repository.FieldMappingRepository;
import com.spdb.repository.TranCatalogRepository;
import com.spdb.web.PageRequestParams;
import com.spdb.web.PagedResult;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ConfigQueryService {
    private final TranCatalogRepository tranCatalogRepository;
    private final FieldMappingRepository fieldMappingRepository;
    private final NamedParameterJdbcTemplate jdbc;

    public ConfigQueryService(TranCatalogRepository tranCatalogRepository,
                              FieldMappingRepository fieldMappingRepository,
                              NamedParameterJdbcTemplate jdbc) {
        this.tranCatalogRepository = tranCatalogRepository;
        this.fieldMappingRepository = fieldMappingRepository;
        this.jdbc = jdbc;
    }

    public PagedResult<TranCatalog> trans(TranSearchCriteria criteria, PageRequestParams params) {
        QueryParts query = tranWhere(criteria);
        query.params.addValue("limit", params.size()).addValue("offset", params.offset());
        List<TranCatalog> rows = jdbc.query("""
                        select catalog_id, tran_code, service_code, tran_name, module_name, owner,
                               importance_level, is_key_tran, remark, created_at, updated_at
                        from ana_tran_catalog
                        """ + query.where + " order by tran_code limit :limit offset :offset",
                query.params,
                (rs, i) -> {
                    TranCatalog row = new TranCatalog();
                    row.setCatalogId(rs.getLong("catalog_id"));
                    row.setTranCode(rs.getString("tran_code"));
                    row.setServiceCode(rs.getString("service_code"));
                    row.setTranName(rs.getString("tran_name"));
                    row.setModuleName(rs.getString("module_name"));
                    row.setOwner(rs.getString("owner"));
                    row.setImportanceLevel(rs.getString("importance_level"));
                    row.setIsKeyTran(rs.getString("is_key_tran"));
                    row.setRemark(rs.getString("remark"));
                    return row;
                });
        return PagedResult.of(rows, count("ana_tran_catalog", query), params);
    }

    public PagedResult<FieldMapping> fields(FieldSearchCriteria criteria, PageRequestParams params) {
        QueryParts query = fieldWhere(criteria);
        query.params.addValue("limit", params.size()).addValue("offset", params.offset());
        List<FieldMapping> rows = jdbc.query("""
                        select mapping_id, tran_code, service_code, std_field_name, field_cn_name,
                               sop_field_name, soap_field_name, bizjson_field_name, remark, created_at, updated_at
                        from ana_field_mapping
                        """ + query.where + " order by tran_code, std_field_name limit :limit offset :offset",
                query.params,
                (rs, i) -> {
                    FieldMapping row = new FieldMapping();
                    row.setMappingId(rs.getLong("mapping_id"));
                    row.setTranCode(rs.getString("tran_code"));
                    row.setServiceCode(rs.getString("service_code"));
                    row.setStdFieldName(rs.getString("std_field_name"));
                    row.setFieldCnName(rs.getString("field_cn_name"));
                    row.setSopFieldName(rs.getString("sop_field_name"));
                    row.setSoapFieldName(rs.getString("soap_field_name"));
                    row.setBizjsonFieldName(rs.getString("bizjson_field_name"));
                    row.setRemark(rs.getString("remark"));
                    return row;
                });
        return PagedResult.of(rows, count("ana_field_mapping", query), params);
    }

    public PagedResult<RecordingConfigRow> recordingConfigs(RecordingSearchCriteria criteria, PageRequestParams params) {
        QueryParts query = recordingWhere(criteria);
        query.params.addValue("limit", params.size()).addValue("offset", params.offset());
        List<RecordingConfigRow> rows = jdbc.query("""
                        select id, txn_code, txn_switch, record_ratio, description, created_time, updated_time
                        from recording_config
                        """ + query.where + " order by txn_code limit :limit offset :offset",
                query.params,
                (rs, i) -> new RecordingConfigRow(
                        rs.getLong("id"),
                        rs.getString("txn_code"),
                        rs.getInt("txn_switch"),
                        rs.getInt("record_ratio"),
                        rs.getString("description"),
                        rs.getObject("created_time", LocalDateTime.class),
                        rs.getObject("updated_time", LocalDateTime.class)
                ));
        return PagedResult.of(rows, count("recording_config", query), params);
    }

    public TranCatalog saveTran(TranCatalog tranCatalog) {
        return tranCatalogRepository.save(tranCatalog);
    }

    public FieldMapping saveField(FieldMapping fieldMapping) {
        return fieldMappingRepository.save(fieldMapping);
    }

    public void saveRecordingConfig(RecordingConfigForm form) {
        Integer switchValue = form.getTxnSwitch() == null ? 0 : form.getTxnSwitch();
        Integer ratio = form.getRecordRatio() == null ? 0 : Math.max(0, Math.min(100, form.getRecordRatio()));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", form.getId())
                .addValue("txnCode", trim(form.getTxnCode()))
                .addValue("txnSwitch", switchValue)
                .addValue("recordRatio", ratio)
                .addValue("description", trim(form.getDescription()));
        if (form.getId() == null) {
            jdbc.update("""
                    insert into recording_config (txn_code, txn_switch, record_ratio, description)
                    values (:txnCode, :txnSwitch, :recordRatio, :description)
                    """, params);
        } else {
            jdbc.update("""
                    update recording_config
                    set txn_code = :txnCode,
                        txn_switch = :txnSwitch,
                        record_ratio = :recordRatio,
                        description = :description,
                        updated_time = pg_systimestamp()
                    where id = :id
                    """, params);
        }
    }

    public void saveGlobalRecordingSwitch(String configValue) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("configKey", "global_recording_switch")
                .addValue("configValue", "1".equals(configValue) ? "1" : "0")
                .addValue("description", "全局录制开关：0=关闭，1=启用");
        Integer updated = jdbc.update("""
                update system_config
                set config_value = :configValue,
                    description = :description,
                    updated_time = pg_systimestamp()
                where config_key = :configKey
                """, params);
        if (updated == 0) {
            jdbc.update("""
                    insert into system_config (config_key, config_value, description)
                    values (:configKey, :configValue, :description)
                    """, params);
        }
    }

    public void deleteTran(Long id) {
        tranCatalogRepository.deleteById(id);
    }

    public void deleteField(Long id) {
        fieldMappingRepository.deleteById(id);
    }

    public void deleteRecordingConfig(Long id) {
        jdbc.update("delete from recording_config where id = :id", new MapSqlParameterSource("id", id));
    }

    public TranCatalog newTran() {
        TranCatalog tran = new TranCatalog();
        tran.setIsKeyTran("false");
        return tran;
    }

    public FieldMapping newField() {
        return new FieldMapping();
    }

    public RecordingConfigForm newRecordingConfig() {
        return new RecordingConfigForm();
    }

    public TranCatalog tran(Long id) {
        return id == null ? newTran() : tranCatalogRepository.findById(id).orElseGet(this::newTran);
    }

    public FieldMapping field(Long id) {
        return id == null ? newField() : fieldMappingRepository.findById(id).orElseGet(this::newField);
    }

    public RecordingConfigForm recordingConfig(Long id) {
        if (id == null) {
            return newRecordingConfig();
        }
        return jdbc.query("""
                        select id, txn_code, txn_switch, record_ratio, description
                        from recording_config
                        where id = :id
                        """,
                new MapSqlParameterSource("id", id),
                rs -> {
                    if (!rs.next()) {
                        return newRecordingConfig();
                    }
                    RecordingConfigForm form = new RecordingConfigForm();
                    form.setId(rs.getLong("id"));
                    form.setTxnCode(rs.getString("txn_code"));
                    form.setTxnSwitch(rs.getInt("txn_switch"));
                    form.setRecordRatio(rs.getInt("record_ratio"));
                    form.setDescription(rs.getString("description"));
                    return form;
                });
    }

    public String globalRecordingSwitch() {
        List<String> values = jdbc.query("""
                        select config_value
                        from system_config
                        where config_key = :configKey
                        """,
                new MapSqlParameterSource("configKey", "global_recording_switch"),
                (rs, i) -> rs.getString("config_value"));
        return values.isEmpty() ? "0" : values.get(0);
    }

    private QueryParts tranWhere(TranSearchCriteria c) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        like(clauses, params, "tran_code", "tranCode", c.tranCode());
        like(clauses, params, "service_code", "serviceCode", c.serviceCode());
        like(clauses, params, "tran_name", "tranName", c.tranName());
        eq(clauses, params, "module_name", "moduleName", c.moduleName());
        like(clauses, params, "owner", "owner", c.owner());
        eq(clauses, params, "is_key_tran", "isKeyTran", c.isKeyTran());
        return parts(clauses, params);
    }

    private QueryParts fieldWhere(FieldSearchCriteria c) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        like(clauses, params, "tran_code", "tranCode", c.tranCode());
        like(clauses, params, "service_code", "serviceCode", c.serviceCode());
        like(clauses, params, "std_field_name", "stdFieldName", c.stdFieldName());
        like(clauses, params, "sop_field_name", "sopFieldName", c.sopFieldName());
        like(clauses, params, "soap_field_name", "soapFieldName", c.soapFieldName());
        like(clauses, params, "bizjson_field_name", "bizjsonFieldName", c.bizjsonFieldName());
        like(clauses, params, "field_cn_name", "fieldCnName", c.fieldCnName());
        return parts(clauses, params);
    }

    private QueryParts recordingWhere(RecordingSearchCriteria c) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        like(clauses, params, "txn_code", "txnCode", c.txnCode());
        if (c.txnSwitch() != null) {
            clauses.add("txn_switch = :txnSwitch");
            params.addValue("txnSwitch", c.txnSwitch());
        }
        return parts(clauses, params);
    }

    private QueryParts parts(List<String> clauses, MapSqlParameterSource params) {
        String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
        return new QueryParts(where, params);
    }

    private long count(String table, QueryParts query) {
        Long total = jdbc.queryForObject("select count(*) from " + table + query.where, query.params, Long.class);
        return total == null ? 0 : total;
    }

    private void like(List<String> clauses, MapSqlParameterSource params, String column, String key, String value) {
        if (StringUtils.hasText(value)) {
            clauses.add(column + " like :" + key);
            params.addValue(key, "%" + value.trim() + "%");
        }
    }

    private void eq(List<String> clauses, MapSqlParameterSource params, String column, String key, String value) {
        if (StringUtils.hasText(value)) {
            clauses.add(column + " = :" + key);
            params.addValue(key, value.trim());
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private record QueryParts(String where, MapSqlParameterSource params) {}
}
