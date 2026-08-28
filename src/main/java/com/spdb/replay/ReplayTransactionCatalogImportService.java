package com.spdb.replay;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

@Service
public class ReplayTransactionCatalogImportService {
    private final ReplayTransactionCatalogWorkbookParser parser;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public ReplayTransactionCatalogImportService(ReplayTransactionCatalogWorkbookParser parser,
                                                 NamedParameterJdbcTemplate jdbc,
                                                 PlatformTransactionManager transactionManager) {
        this.parser = parser;
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public int importWorkbook(Path path) throws IOException { return replace(parser.parse(path)); }
    public int importWorkbook(InputStream input) throws IOException { return replace(parser.parse(input)); }
    public int importWorkbook(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Excel文件为空");
        return importWorkbook(new java.io.ByteArrayInputStream(bytes));
    }

    public int importEntries(List<ReplayTransactionCatalogForm> entries) {
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("没有可导入的数据");
        return replace(entries);
    }

    private int replace(List<ReplayTransactionCatalogForm> entries) {
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("没有可导入的数据");
        Integer count = transactionTemplate.execute(status -> {
            jdbc.getJdbcTemplate().execute("delete from ana_replay_transaction_catalog");
            jdbc.batchUpdate("insert into ana_replay_transaction_catalog (tran_code,tran_name,business_domain,batch_type,new_core_tran_code,new_tran_name,replay_required,original_service_scene_code,new_service_scene_code,latest_transaction_date) values (:tranCode,:tranName,:businessDomain,:batchType,:newCoreTranCode,:newTranName,:replayRequired,:originalServiceSceneCode,:newServiceSceneCode,:latestTransactionDate)",
                    entries.stream().map(this::params).toArray(MapSqlParameterSource[]::new));
            return entries.size();
        });
        return count == null ? 0 : count;
    }

    private MapSqlParameterSource params(ReplayTransactionCatalogForm f) {
        return new MapSqlParameterSource()
                .addValue("tranCode", f.tranCode()).addValue("tranName", emptyToNull(f.tranName()))
                .addValue("businessDomain", emptyToNull(f.businessDomain())).addValue("batchType", emptyToNull(f.batchType()))
                .addValue("newCoreTranCode", emptyToNull(f.newCoreTranCode())).addValue("newTranName", emptyToNull(f.newTranName()))
                .addValue("replayRequired", emptyToNull(f.replayRequired())).addValue("originalServiceSceneCode", emptyToNull(f.originalServiceSceneCode()))
                .addValue("newServiceSceneCode", emptyToNull(f.newServiceSceneCode())).addValue("latestTransactionDate", emptyToNull(f.latestTransactionDate()));
    }

    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
