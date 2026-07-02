package com.spdb.sampling.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IssueGrouperTest {

    @Test
    void groupsA825BizjsonAndSopSamplesBySemanticFieldCombination() {
        IssueGrouper grouper = new IssueGrouper(20);
        SemanticSignatureBuilder signatureBuilder = new SemanticSignatureBuilder();

        IssueCandidate bizjson = fieldCandidate(
                "11111111111",
                "bizjson",
                List.of(
                        field("CurrencyId", "currency_id", "111", "222", 1),
                        field("FcyCollCrspBnkLkg", "link_info", "A1/B1", "A/B", 2)
                ),
                signatureBuilder
        );
        IssueCandidate sop = fieldCandidate(
                "11111111114",
                "sop",
                List.of(
                        field("HUOBDH", "currency_id", "111", "222", 1),
                        field("FAB251", "link_info", "A1/B1", "A/B", 2)
                ),
                signatureBuilder
        );

        List<SampleGroupDraft> groups = grouper.group(List.of(bizjson, sop));

        assertThat(groups).hasSize(1);
        SampleGroupDraft group = groups.get(0);
        assertThat(group.sampleType()).isEqualTo("FIELD_DIFF");
        assertThat(group.tranCode()).isEqualTo("A825");
        assertThat(group.serviceCode()).isEqualTo("S030030014FcyCollCrspBnkLkgQry");
        assertThat(group.semanticFieldNames()).isEqualTo("currency_id,link_info");
        assertThat(group.messageTypes()).isEqualTo("bizjson,sop");
        assertThat(group.affectedTranCount()).isEqualTo(2);
        assertThat(group.affectedFieldCount()).isEqualTo(4);
        assertThat(group.details()).extracting(SampleDetailDraft::tranSeqNo)
                .containsExactly("11111111111", "11111111114");
        assertThat(group.details()).extracting(SampleDetailDraft::sopFieldName)
                .containsExactly("HUOBDH,FAB251", "HUOBDH,FAB251");
        assertThat(group.details()).extracting(SampleDetailDraft::soapFieldName)
                .containsExactly("CurrencyId,FcyCollCrspBnkLkg", "CurrencyId,FcyCollCrspBnkLkg");
        assertThat(group.details()).extracting(SampleDetailDraft::bizjsonFieldName)
                .containsExactly("CurrencyId,FcyCollCrspBnkLkg", "CurrencyId,FcyCollCrspBnkLkg");
        assertThat(group.details()).extracting(SampleDetailDraft::fieldCnName)
                .containsExactly("币种,联动信息", "币种,联动信息");
        assertThat(group.details()).flatExtracting(SampleDetailDraft::fields)
                .extracting(SampleDetailFieldDraft::rawFieldName)
                .containsExactly("CurrencyId", "FcyCollCrspBnkLkg", "HUOBDH", "FAB251");
    }

    @Test
    void acceptsCandidatesIncrementallyAndKeepsOnlySampleLimitPerGroup() {
        IssueGrouper grouper = new IssueGrouper(2);
        SemanticSignatureBuilder signatureBuilder = new SemanticSignatureBuilder();

        for (int i = 0; i < 5; i++) {
            grouper.add(fieldCandidate(
                    "1111111111" + i,
                    "bizjson",
                    List.of(field("CurrencyId", "currency_id", "111", "222", 1)),
                    signatureBuilder
            ));
        }

        List<SampleGroupDraft> groups = grouper.groups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).affectedTranCount()).isEqualTo(5);
        assertThat(groups.get(0).affectedFieldCount()).isEqualTo(5);
        assertThat(groups.get(0).details()).extracting(SampleDetailDraft::tranSeqNo)
                .containsExactly("11111111110", "11111111111");
    }

    private IssueCandidate fieldCandidate(String mesgSeq,
                                          String messageType,
                                          List<SampleDetailFieldDraft> fields,
                                          SemanticSignatureBuilder signatureBuilder) {
        SemanticSignatureBuilder.Signature signature = signatureBuilder.build(fields.stream()
                .map(field -> new SemanticSignatureBuilder.SignatureField(
                        field.rawFieldName(),
                        field.stdFieldName(),
                        field.origFieldValue(),
                        field.destFieldValue()
                ))
                .toList());
        return IssueCandidate.fieldDiff(
                "20260611",
                new SourceKey(mesgSeq),
                "A825",
                "外币托收代理行联动查询",
                "loan",
                "张伟",
                "S030030014FcyCollCrspBnkLkgQry",
                messageType,
                "S030030014FcyCollCrspBnkLkgQry&" + messageType,
                "4",
                "CONFIGURED",
                signature.signature(),
                signature.hash(),
                fields
        );
    }

    private SampleDetailFieldDraft field(String rawFieldName,
                                         String stdFieldName,
                                         String origValue,
                                         String destValue,
                                         int fieldIndex) {
        return new SampleDetailFieldDraft(
                rawFieldName,
                stdFieldName,
                "currency_id".equals(stdFieldName) ? "币种" : "联动信息",
                "currency_id".equals(stdFieldName) ? "HUOBDH" : "FAB251",
                "currency_id".equals(stdFieldName) ? "CurrencyId" : "FcyCollCrspBnkLkg",
                "currency_id".equals(stdFieldName) ? "CurrencyId" : "FcyCollCrspBnkLkg",
                origValue,
                destValue,
                "MAPPED",
                fieldIndex
        );
    }
}
