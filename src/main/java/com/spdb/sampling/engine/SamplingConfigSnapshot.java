package com.spdb.sampling.engine;

import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SamplingConfigSnapshot {
    private static final String WILDCARD_TYPE = "*";

    private final Map<String, TranConfig> tranByServiceCode;
    private final Map<String, FieldConfig> fieldByTypedRawName;
    private final Map<String, FieldConfig> fieldByAnyRawName;

    private SamplingConfigSnapshot(Map<String, TranConfig> tranByServiceCode,
                                   Map<String, FieldConfig> fieldByTypedRawName,
                                   Map<String, FieldConfig> fieldByAnyRawName) {
        this.tranByServiceCode = tranByServiceCode;
        this.fieldByTypedRawName = fieldByTypedRawName;
        this.fieldByAnyRawName = fieldByAnyRawName;
    }

    public static SamplingConfigSnapshot from(List<TranConfig> tranConfigs, List<FieldConfig> fieldConfigs) {
        Map<String, TranConfig> tranByServiceCode = new HashMap<>();
        for (TranConfig tranConfig : tranConfigs) {
            tranByServiceCode.put(normalizeKey(tranConfig.serviceCode()), tranConfig);
        }

        Map<String, FieldConfig> typed = new HashMap<>();
        Map<String, FieldConfig> any = new HashMap<>();
        for (FieldConfig fieldConfig : fieldConfigs) {
            putTyped(typed, fieldConfig, "sop", fieldConfig.sopFieldName());
            putTyped(typed, fieldConfig, "soap", fieldConfig.soapFieldName());
            putTyped(typed, fieldConfig, "bizjson", fieldConfig.bizjsonFieldName());
            putAny(any, fieldConfig, fieldConfig.stdFieldName());
            putAny(any, fieldConfig, fieldConfig.sopFieldName());
            putAny(any, fieldConfig, fieldConfig.soapFieldName());
            putAny(any, fieldConfig, fieldConfig.bizjsonFieldName());
        }

        return new SamplingConfigSnapshot(tranByServiceCode, typed, any);
    }

    public TranConfig resolveTran(String serviceCode) {
        return tranByServiceCode.get(normalizeKey(serviceCode));
    }

    public FieldSemantic resolveField(String tranCode, String serviceCode, String messageType, String rawFieldName) {
        FieldConfig fieldConfig = fieldByTypedRawName.get(fieldKey(tranCode, serviceCode, MessageType.normalize(messageType), rawFieldName));
        if (fieldConfig == null) {
            fieldConfig = fieldByAnyRawName.get(fieldKey(tranCode, serviceCode, WILDCARD_TYPE, rawFieldName));
        }
        if (fieldConfig == null) {
            return new FieldSemantic(rawFieldName, rawFieldName, null, null, null, null, FieldSemantic.UNMAPPED);
        }
        return new FieldSemantic(
                rawFieldName,
                fieldConfig.stdFieldName(),
                fieldConfig.fieldCnName(),
                fieldConfig.sopFieldName(),
                fieldConfig.soapFieldName(),
                fieldConfig.bizjsonFieldName(),
                FieldSemantic.MAPPED
        );
    }

    private static void putTyped(Map<String, FieldConfig> target, FieldConfig fieldConfig, String messageType, String rawFieldName) {
        if (StringUtils.hasText(rawFieldName)) {
            target.put(fieldKey(fieldConfig.tranCode(), fieldConfig.serviceCode(), messageType, rawFieldName), fieldConfig);
        }
    }

    private static void putAny(Map<String, FieldConfig> target, FieldConfig fieldConfig, String rawFieldName) {
        if (StringUtils.hasText(rawFieldName)) {
            target.put(fieldKey(fieldConfig.tranCode(), fieldConfig.serviceCode(), WILDCARD_TYPE, rawFieldName), fieldConfig);
        }
    }

    private static String fieldKey(String tranCode, String serviceCode, String messageType, String rawFieldName) {
        return normalizeKey(tranCode) + "|" + normalizeKey(serviceCode) + "|" + normalizeKey(messageType) + "|" + normalizeKey(rawFieldName);
    }

    private static String normalizeKey(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    public record TranConfig(
            String tranCode,
            String serviceCode,
            String tranName,
            String moduleName,
            String owner
    ) {
    }

    public record FieldConfig(
            String tranCode,
            String serviceCode,
            String stdFieldName,
            String fieldCnName,
            String sopFieldName,
            String soapFieldName,
            String bizjsonFieldName
    ) {
    }
}
