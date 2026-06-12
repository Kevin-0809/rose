package com.spdb.sampling.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticSignatureBuilderTest {

    @Test
    void buildsSameSignatureForDifferentRawFieldsWithSameSemanticFields() {
        SemanticSignatureBuilder builder = new SemanticSignatureBuilder();

        SemanticSignatureBuilder.Signature bizjson = builder.build(List.of(
                diff("CurrencyId", "currency_id", "111", "222"),
                diff("FcyCollCrspBnkLkg", "link_info", "A1/B1", "A/B")
        ));
        SemanticSignatureBuilder.Signature sop = builder.build(List.of(
                diff("HUOBDH", "currency_id", "111", "222"),
                diff("FAB251", "link_info", "A1/B1", "A/B")
        ));

        assertThat(bizjson.signature()).isEqualTo("currency_id:111->222|link_info:A1/B1->A/B");
        assertThat(sop.signature()).isEqualTo(bizjson.signature());
        assertThat(sop.hash()).hasSize(32);
    }

    private static SemanticSignatureBuilder.SignatureField diff(String rawFieldName,
                                                                String stdFieldName,
                                                                String origValue,
                                                                String destValue) {
        return new SemanticSignatureBuilder.SignatureField(rawFieldName, stdFieldName, origValue, destValue);
    }
}
