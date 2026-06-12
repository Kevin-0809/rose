package com.spdb.sampling.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SamplingConfigSnapshotTest {

    @Test
    void mapsBizjsonAndSopFieldsToSameSemanticField() {
        SamplingConfigSnapshot snapshot = SamplingConfigSnapshot.from(
                List.of(new SamplingConfigSnapshot.TranConfig(
                        "A825",
                        "S030030014FcyCollCrspBnkLkgQry",
                        "外币托收代理行联动查询",
                        "loan",
                        "张伟"
                )),
                List.of(
                        new SamplingConfigSnapshot.FieldConfig(
                                "A825",
                                "S030030014FcyCollCrspBnkLkgQry",
                                "currency_id",
                                "币种",
                                "HUOBDH",
                                "CurrencyId",
                                "CurrencyId"
                        ),
                        new SamplingConfigSnapshot.FieldConfig(
                                "A825",
                                "S030030014FcyCollCrspBnkLkgQry",
                                "link_info",
                                "联动信息",
                                "FAB251",
                                "FcyCollCrspBnkLkg",
                                "FcyCollCrspBnkLkg"
                        )
                )
        );

        FieldSemantic bizjson = snapshot.resolveField("A825", "S030030014FcyCollCrspBnkLkgQry", "bizjson", "CurrencyId");
        FieldSemantic sop = snapshot.resolveField("A825", "S030030014FcyCollCrspBnkLkgQry", "sop", "HUOBDH");

        assertThat(bizjson.stdFieldName()).isEqualTo("currency_id");
        assertThat(sop.stdFieldName()).isEqualTo("currency_id");
        assertThat(bizjson.mappingStatus()).isEqualTo("MAPPED");
        assertThat(sop.mappingStatus()).isEqualTo("MAPPED");
    }

    @Test
    void unmappedFieldFallsBackToRawName() {
        SamplingConfigSnapshot snapshot = SamplingConfigSnapshot.from(List.of(), List.of());

        FieldSemantic semantic = snapshot.resolveField("UNKNOWN", "MissingService", "bizjson", "RawField");

        assertThat(semantic.stdFieldName()).isEqualTo("RawField");
        assertThat(semantic.rawFieldName()).isEqualTo("RawField");
        assertThat(semantic.mappingStatus()).isEqualTo("UNMAPPED");
    }
}
