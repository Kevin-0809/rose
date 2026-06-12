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
        assertThat(group.details()).flatExtracting(SampleDetailDraft::fields)
                .extracting(SampleDetailFieldDraft::rawFieldName)
                .containsExactly("CurrencyId", "FcyCollCrspBnkLkg", "HUOBDH", "FAB251");
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
                new SourceKey(mesgSeq, 1, 1),
                "A825",
                "外币托收代理行联动查询",
                "loan",
                "张伟",
                "S030030014FcyCollCrspBnkLkgQry",
                messageType,
                "S030030014FcyCollCrspBnkLkgQry&" + messageType,
                "4",
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
                null,
                origValue,
                destValue,
                "MAPPED",
                fieldIndex
        );
    }
}
