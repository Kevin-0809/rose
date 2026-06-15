package com.spdb.sampling;

import com.spdb.sampling.engine.FieldDiff;
import com.spdb.sampling.engine.FieldSemantic;
import com.spdb.sampling.engine.IssueCandidate;
import com.spdb.sampling.engine.IssueGrouper;
import com.spdb.sampling.engine.JdbcSamplingSourceReader;
import com.spdb.sampling.engine.ReturnCodeDiff;
import com.spdb.sampling.engine.SampleDetailDraft;
import com.spdb.sampling.engine.SampleDetailFieldDraft;
import com.spdb.sampling.engine.SampleGroupDraft;
import com.spdb.sampling.engine.SamplingConfigSnapshot;
import com.spdb.sampling.engine.SemanticSignatureBuilder;
import com.spdb.sampling.engine.SourceKey;
import com.spdb.sampling.engine.TranFact;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Component
public class SamplingBatchRunner {
    private static final int MAX_SAMPLES_PER_GROUP = 20;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final JdbcSamplingSourceReader sourceReader;
    private final SemanticSignatureBuilder signatureBuilder = new SemanticSignatureBuilder();

    public SamplingBatchRunner(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.sourceReader = new JdbcSamplingSourceReader(jdbc.getJdbcTemplate().getDataSource());
    }

    public void run(SamplingCommandRow command) {
        if (command == null) {
            throw new IllegalArgumentException("采样批次不存在");
        }
        transactionTemplate.executeWithoutResult(status -> runInTransaction(command));
    }

    private void runInTransaction(SamplingCommandRow command) {
        String batchId = command.batchId();
        String origCdate = command.origCdate();
        clearBatch(batchId);

        SamplingConfigSnapshot config = loadConfig();
        Map<SourceKey, TranFact> tranFacts = new LinkedHashMap<>();
        SummaryAccumulator summary = new SummaryAccumulator();
        List<IssueCandidate> candidates = new ArrayList<>();

        sourceReader.readTranFacts(origCdate, fact -> {
            TranFact configured = configureTran(fact, config);
            tranFacts.put(configured.sourceKey(), configured);
            summary.addTran(configured);
            if (isTranIssue(configured.compResult())) {
                candidates.add(tranResultCandidate(configured));
            }
        });

        sourceReader.readReturnCodes(origCdate, diff -> {
            TranFact fact = tranFacts.get(new SourceKey(diff.mesgSeq()));
            if (fact != null) {
                candidates.add(returnCodeCandidate(fact, diff));
                summary.returnCodeIssueCount++;
            }
        });

        collectFieldDiffCandidates(origCdate, config, tranFacts, summary, candidates);

        List<SampleGroupDraft> groups = new IssueGrouper(MAX_SAMPLES_PER_GROUP).group(candidates);
        writeGroups(batchId, groups);
        writeSummary(batchId, origCdate, summary, groups);
    }

    private void clearBatch(String batchId) {
        MapSqlParameterSource params = new MapSqlParameterSource("batchId", batchId);
        jdbc.update("delete from ana_sample_detail_field where batch_id = :batchId", params);
        jdbc.update("delete from ana_sample_detail where batch_id = :batchId", params);
        jdbc.update("delete from ana_sample_group where batch_id = :batchId", params);
        jdbc.update("delete from ana_sampling_summary where batch_id = :batchId", params);
    }

    private SamplingConfigSnapshot loadConfig() {
        List<SamplingConfigSnapshot.TranConfig> trans = jdbc.query("""
                select tran_code, service_code, tran_name, module_name, owner
                from ana_tran_catalog
                """, new MapSqlParameterSource(), (rs, i) -> new SamplingConfigSnapshot.TranConfig(
                rs.getString("tran_code"),
                rs.getString("service_code"),
                rs.getString("tran_name"),
                rs.getString("module_name"),
                rs.getString("owner")
        ));
        List<SamplingConfigSnapshot.FieldConfig> fields = jdbc.query("""
                select tran_code, service_code, std_field_name, field_cn_name,
                       sop_field_name, soap_field_name, bizjson_field_name
                from ana_field_mapping
                """, new MapSqlParameterSource(), (rs, i) -> new SamplingConfigSnapshot.FieldConfig(
                rs.getString("tran_code"),
                rs.getString("service_code"),
                rs.getString("std_field_name"),
                rs.getString("field_cn_name"),
                rs.getString("sop_field_name"),
                rs.getString("soap_field_name"),
                rs.getString("bizjson_field_name")
        ));
        return SamplingConfigSnapshot.from(trans, fields);
    }

    private TranFact configureTran(TranFact fact, SamplingConfigSnapshot config) {
        SamplingConfigSnapshot.TranConfig tranConfig = config.resolveTran(fact.serviceCode());
        if (tranConfig == null) {
            return fact;
        }
        return new TranFact(
                fact.sourceKey(),
                fact.origCdate(),
                fact.destTrcd(),
                fact.serviceCode(),
                fact.messageType(),
                tranConfig.tranCode(),
                tranConfig.tranName(),
                tranConfig.moduleName(),
                tranConfig.owner(),
                fact.compResult(),
                "CONFIGURED"
        );
    }

    private boolean isTranIssue(String compResult) {
        return "1".equals(compResult) || "2".equals(compResult) || "8".equals(compResult);
    }

    private IssueCandidate tranResultCandidate(TranFact fact) {
        String signature = "TRANSACTION:" + fact.compResult();
        String hash = signatureBuilder.build(List.of(new SemanticSignatureBuilder.SignatureField(
                "TRANSACTION", "TRANSACTION", fact.compResult(), fact.compResult()
        ))).hash();
        return new IssueCandidate(
                fact.origCdate(),
                "RETURN_CODE",
                fact.sourceKey(),
                fact.tranCode(),
                fact.tranName(),
                fact.moduleName(),
                fact.owner(),
                fact.serviceCode(),
                fact.messageType(),
                fact.destTrcd(),
                fact.compResult(),
                fact.configStatus(),
                FieldSemantic.MAPPED,
                signature,
                hash,
                null,
                null,
                null,
                null,
                List.of()
        );
    }

    private IssueCandidate returnCodeCandidate(TranFact fact, ReturnCodeDiff diff) {
        String signature = "returnCode:" + value(diff.origErrorCode()) + "->" + value(diff.destErrorCode());
        String hash = signatureBuilder.build(List.of(new SemanticSignatureBuilder.SignatureField(
                "returnCode", "returnCode", diff.origErrorCode(), diff.destErrorCode()
        ))).hash();
        return new IssueCandidate(
                fact.origCdate(),
                "RETURN_CODE",
                fact.sourceKey(),
                fact.tranCode(),
                fact.tranName(),
                fact.moduleName(),
                fact.owner(),
                fact.serviceCode(),
                fact.messageType(),
                fact.destTrcd(),
                fact.compResult(),
                fact.configStatus(),
                FieldSemantic.MAPPED,
                signature,
                hash,
                diff.origErrorCode(),
                diff.origErrorDesc(),
                diff.destErrorCode(),
                diff.destErrorDesc(),
                List.of()
        );
    }

    private void collectFieldDiffCandidates(String origCdate,
                                            SamplingConfigSnapshot config,
                                            Map<SourceKey, TranFact> tranFacts,
                                            SummaryAccumulator summary,
                                            List<IssueCandidate> candidates) {
        FieldBuffer buffer = new FieldBuffer();
        sourceReader.readFieldDiffs(origCdate, diff -> {
            if (!Objects.equals(buffer.key, diff.sourceKey()) && buffer.key != null) {
                flushFieldBuffer(buffer, config, tranFacts, summary, candidates);
                buffer.clear();
            }
            buffer.key = diff.sourceKey();
            buffer.diffs.add(diff);
        });
        if (buffer.key != null) {
            flushFieldBuffer(buffer, config, tranFacts, summary, candidates);
        }
    }

    private void flushFieldBuffer(FieldBuffer buffer,
                                  SamplingConfigSnapshot config,
                                  Map<SourceKey, TranFact> tranFacts,
                                  SummaryAccumulator summary,
                                  List<IssueCandidate> candidates) {
        TranFact fact = tranFacts.get(buffer.key);
        if (fact == null || !"4".equals(fact.compResult())) {
            return;
        }
        summary.fieldDiffTranKeys.add(buffer.key);

        List<SampleDetailFieldDraft> fields = new ArrayList<>();
        List<SemanticSignatureBuilder.SignatureField> signatureFields = new ArrayList<>();
        for (FieldDiff diff : buffer.diffs) {
            FieldSemantic semantic = config.resolveField(fact.tranCode(), fact.serviceCode(), diff.messageType(), diff.rawFieldName());
            if (FieldSemantic.UNMAPPED.equals(semantic.mappingStatus())) {
                summary.unmappedFieldNames.add(fact.serviceCode() + "|" + diff.rawFieldName());
            }
                fields.add(new SampleDetailFieldDraft(
                    diff.rawFieldName(),
                    semantic.stdFieldName(),
                    semantic.fieldCnName(),
                    semantic.sopFieldName(),
                    semantic.soapFieldName(),
                    semantic.bizjsonFieldName(),
                    diff.origFieldValue(),
                    diff.destFieldValue(),
                    semantic.mappingStatus(),
                    diff.fieldIndex()
            ));
            signatureFields.add(new SemanticSignatureBuilder.SignatureField(
                    diff.rawFieldName(),
                    semantic.stdFieldName(),
                    diff.origFieldValue(),
                    diff.destFieldValue()
            ));
        }

        SemanticSignatureBuilder.Signature signature = signatureBuilder.build(signatureFields);
        candidates.add(IssueCandidate.fieldDiff(
                fact.origCdate(),
                fact.sourceKey(),
                fact.tranCode(),
                fact.tranName(),
                fact.moduleName(),
                fact.owner(),
                fact.serviceCode(),
                fact.messageType(),
                fact.destTrcd(),
                fact.compResult(),
                signature.signature(),
                signature.hash(),
                fields
        ));
    }

    private void writeGroups(String batchId, List<SampleGroupDraft> groups) {
        for (SampleGroupDraft group : groups) {
            Long groupId = insertGroup(batchId, group);
            int sequence = 1;
            for (SampleDetailDraft detail : group.details()) {
                Long sampleId = insertDetail(batchId, groupId, group, detail, sequence++);
                insertDetailFields(batchId, groupId, sampleId, detail);
            }
        }
    }

    private Long insertGroup(String batchId, SampleGroupDraft group) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                insert into ana_sample_group (
                    batch_id, orig_cdate, sample_type, group_key, group_hash, config_status, mapping_status,
                    semantic_signature, semantic_signature_hash, semantic_field_names, message_types,
                    dest_trcd, service_code, message_type, tran_code, comp_result, sop_field_name,
                    owner, affected_count, affected_tran_count, affected_field_count, sample_count
                ) values (
                    :batchId, :origCdate, :sampleType, :groupKey, :groupHash, :configStatus, :mappingStatus,
                    :semanticSignature, :semanticSignatureHash, :semanticFieldNames, :messageTypes,
                    :destTrcd, :serviceCode, :messageType, :tranCode, :compResult, :sopFieldName,
                    :owner, :affectedCount, :affectedTranCount, :affectedFieldCount, :sampleCount
                )
                """, new MapSqlParameterSource()
                .addValue("batchId", batchId)
                .addValue("origCdate", group.origCdate())
                .addValue("sampleType", group.sampleType())
                .addValue("groupKey", group.groupKey())
                .addValue("groupHash", group.groupHash())
                .addValue("configStatus", group.configStatus())
                .addValue("mappingStatus", group.mappingStatus())
                .addValue("semanticSignature", group.semanticSignature())
                .addValue("semanticSignatureHash", group.semanticSignatureHash())
                .addValue("semanticFieldNames", group.semanticFieldNames())
                .addValue("messageTypes", group.messageTypes())
                .addValue("destTrcd", firstDestTrcd(group))
                .addValue("serviceCode", group.serviceCode())
                .addValue("messageType", firstMessageType(group))
                .addValue("tranCode", group.tranCode())
                .addValue("compResult", group.compResult())
                .addValue("sopFieldName", firstSemanticField(group))
                .addValue("owner", group.owner())
                .addValue("affectedCount", group.affectedTranCount())
                .addValue("affectedTranCount", group.affectedTranCount())
                .addValue("affectedFieldCount", group.affectedFieldCount())
                .addValue("sampleCount", group.details().size()), keyHolder);
        return generatedLongKey(keyHolder, "group_id");
    }

    private Long insertDetail(String batchId, Long groupId, SampleGroupDraft group, SampleDetailDraft detail, int sequence) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                insert into ana_sample_detail (
                    group_id, batch_id, orig_cdate, sample_type, sample_seq_no, config_status,
                    dest_trcd, service_code, message_type, tran_code, comp_result,
                    sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
                    tran_seq_no, owner, affected_count, field_count, orig_error_code, orig_error_desc,
                    dest_error_code, dest_error_desc, source_table, source_pk
                ) values (
                    :groupId, :batchId, :origCdate, :sampleType, :sampleSeqNo, :configStatus,
                    :destTrcd, :serviceCode, :messageType, :tranCode, :compResult,
                    :sopFieldName, :soapFieldName, :bizjsonFieldName, :fieldCnName,
                    :tranSeqNo, :owner, :affectedCount, :fieldCount, :origErrorCode, :origErrorDesc,
                    :destErrorCode, :destErrorDesc, :sourceTable, :sourcePk
                )
                """, new MapSqlParameterSource()
                .addValue("groupId", groupId)
                .addValue("batchId", batchId)
                .addValue("origCdate", group.origCdate())
                .addValue("sampleType", group.sampleType())
                .addValue("sampleSeqNo", sequence)
                .addValue("configStatus", detail.configStatus())
                .addValue("destTrcd", detail.destTrcd())
                .addValue("serviceCode", group.serviceCode())
                .addValue("messageType", detail.messageType())
                .addValue("tranCode", group.tranCode())
                .addValue("compResult", detail.compResult())
                .addValue("sopFieldName", detail.sopFieldName())
                .addValue("soapFieldName", detail.soapFieldName())
                .addValue("bizjsonFieldName", detail.bizjsonFieldName())
                .addValue("fieldCnName", detail.fieldCnName())
                .addValue("tranSeqNo", detail.tranSeqNo())
                .addValue("owner", group.owner())
                .addValue("affectedCount", group.affectedTranCount())
                .addValue("fieldCount", detail.fields().size())
                .addValue("origErrorCode", detail.origErrorCode())
                .addValue("origErrorDesc", detail.origErrorDesc())
                .addValue("destErrorCode", detail.destErrorCode())
                .addValue("destErrorDesc", detail.destErrorDesc())
                .addValue("sourceTable", sourceTable(group.sampleType()))
                .addValue("sourcePk", detail.tranSeqNo()), keyHolder);
        return generatedLongKey(keyHolder, "sample_id");
    }

    static Long generatedLongKey(KeyHolder keyHolder, String columnName) {
        Object value = keyHolder.getKeys() == null ? null : keyHolder.getKeys().get(columnName);
        if (value instanceof Number number) {
            return number.longValue();
        }
        Number singleKey = keyHolder.getKey();
        if (singleKey == null) {
            throw new IllegalStateException("未返回自增键：" + columnName);
        }
        return singleKey.longValue();
    }

    private void insertDetailFields(String batchId, Long groupId, Long sampleId, SampleDetailDraft detail) {
        for (SampleDetailFieldDraft field : detail.fields()) {
            jdbc.update("""
                    insert into ana_sample_detail_field (
                        sample_id, group_id, batch_id, mesg_seq, message_type, raw_field_name,
                        std_field_name, field_cn_name, orig_field_value, dest_field_value,
                        mapping_status, field_index
                    ) values (
                        :sampleId, :groupId, :batchId, :mesgSeq, :messageType, :rawFieldName,
                        :stdFieldName, :fieldCnName, :origFieldValue, :destFieldValue,
                        :mappingStatus, :fieldIndex
                    )
                    """, new MapSqlParameterSource()
                    .addValue("sampleId", sampleId)
                    .addValue("groupId", groupId)
                    .addValue("batchId", batchId)
                    .addValue("mesgSeq", detail.tranSeqNo())
                    .addValue("messageType", detail.messageType())
                    .addValue("rawFieldName", field.rawFieldName())
                    .addValue("stdFieldName", field.stdFieldName())
                    .addValue("fieldCnName", field.fieldCnName())
                    .addValue("origFieldValue", field.origFieldValue())
                    .addValue("destFieldValue", field.destFieldValue())
                    .addValue("mappingStatus", field.mappingStatus())
                    .addValue("fieldIndex", field.fieldIndex()));
        }
    }

    private void writeSummary(String batchId, String origCdate, SummaryAccumulator summary, List<SampleGroupDraft> groups) {
        jdbc.update("""
                insert into ana_sampling_summary (
                    batch_id, orig_cdate, total_tran_count, comp_result_1_count, comp_result_2_count,
                    comp_result_3_count, comp_result_4_count, comp_result_8_count, pass_tran_count,
                    tran_issue_count, return_code_issue_count, issue_field_count, field_diff_tran_count,
                    fully_matched_count, unconfigured_service_count, unmapped_field_count,
                    sample_group_count, sample_detail_count
                ) values (
                    :batchId, :origCdate, :totalTranCount, :comp1, :comp2,
                    :comp3, :comp4, :comp8, :passTranCount,
                    :tranIssueCount, :returnCodeIssueCount, :issueFieldCount, :fieldDiffTranCount,
                    :fullyMatchedCount, :unconfiguredServiceCount, :unmappedFieldCount,
                    :sampleGroupCount, :sampleDetailCount
                )
                """, new MapSqlParameterSource()
                .addValue("batchId", batchId)
                .addValue("origCdate", origCdate)
                .addValue("totalTranCount", summary.totalTranCount)
                .addValue("comp1", summary.compResultCounts.getOrDefault("1", 0L))
                .addValue("comp2", summary.compResultCounts.getOrDefault("2", 0L))
                .addValue("comp3", summary.compResultCounts.getOrDefault("3", 0L))
                .addValue("comp4", summary.compResultCounts.getOrDefault("4", 0L))
                .addValue("comp8", summary.compResultCounts.getOrDefault("8", 0L))
                .addValue("passTranCount", summary.compResultCounts.getOrDefault("4", 0L))
                .addValue("tranIssueCount", summary.tranIssueCount)
                .addValue("returnCodeIssueCount", summary.returnCodeIssueCount)
                .addValue("issueFieldCount", summary.issueFieldCount())
                .addValue("fieldDiffTranCount", summary.fieldDiffTranKeys.size())
                .addValue("fullyMatchedCount", summary.fullyMatchedCount())
                .addValue("unconfiguredServiceCount", summary.unconfiguredServices.size())
                .addValue("unmappedFieldCount", summary.unmappedFieldNames.size())
                .addValue("sampleGroupCount", groups.size())
                .addValue("sampleDetailCount", groups.stream().mapToInt(group -> group.details().size()).sum()));
    }

    private String firstDestTrcd(SampleGroupDraft group) {
        return group.details().isEmpty() ? group.serviceCode() : group.details().get(0).destTrcd();
    }

    private String firstMessageType(SampleGroupDraft group) {
        return group.details().isEmpty() ? null : group.details().get(0).messageType();
    }

    private String firstSemanticField(SampleGroupDraft group) {
        if (group.semanticFieldNames() == null || group.semanticFieldNames().isBlank()) {
            return group.sampleType();
        }
        int comma = group.semanticFieldNames().indexOf(',');
        return comma < 0 ? group.semanticFieldNames() : group.semanticFieldNames().substring(0, comma);
    }

    private String sourceTable(String sampleType) {
        if ("RETURN_CODE".equals(sampleType)) {
            return "tss_retcode_comp";
        }
        return "tss_field_comp";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private static class FieldBuffer {
        private SourceKey key;
        private final List<FieldDiff> diffs = new ArrayList<>();

        private void clear() {
            key = null;
            diffs.clear();
        }
    }

    private class SummaryAccumulator {
        private long totalTranCount;
        private long tranIssueCount;
        private long returnCodeIssueCount;
        private final Map<String, Long> compResultCounts = new HashMap<>();
        private final Set<SourceKey> successTranKeys = new HashSet<>();
        private final Set<SourceKey> fieldDiffTranKeys = new HashSet<>();
        private final Set<String> unconfiguredServices = new HashSet<>();
        private final Set<String> unmappedFieldNames = new HashSet<>();

        private void addTran(TranFact fact) {
            totalTranCount++;
            compResultCounts.merge(fact.compResult(), 1L, Long::sum);
            if ("4".equals(fact.compResult())) {
                successTranKeys.add(fact.sourceKey());
            }
            if ("UNCONFIGURED_SERVICE".equals(fact.configStatus())) {
                unconfiguredServices.add(fact.serviceCode());
            }
            if (isTranIssue(fact.compResult())) {
                tranIssueCount++;
            }
        }

        private long issueFieldCount() {
            return fieldDiffTranKeys.size();
        }

        private long fullyMatchedCount() {
            TreeSet<SourceKey> matched = new TreeSet<>(successTranKeys);
            matched.removeAll(fieldDiffTranKeys);
            return matched.size();
        }
    }
}
