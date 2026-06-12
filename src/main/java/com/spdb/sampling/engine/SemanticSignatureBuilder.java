package com.spdb.sampling.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public class SemanticSignatureBuilder {

    public Signature build(List<SignatureField> fields) {
        String signature = fields.stream()
                .sorted(Comparator
                        .comparing(SignatureField::stdFieldName)
                        .thenComparing(SignatureField::rawFieldName))
                .map(field -> field.stdFieldName() + ":" + value(field.origValue()) + "->" + value(field.destValue()))
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        return new Signature(signature, md5(signature));
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm is unavailable", e);
        }
    }

    public record Signature(String signature, String hash) {
    }

    public record SignatureField(String rawFieldName, String stdFieldName, String origValue, String destValue) {
    }
}
