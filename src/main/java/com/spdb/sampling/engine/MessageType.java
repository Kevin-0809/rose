package com.spdb.sampling.engine;

import org.springframework.util.StringUtils;

import java.util.Locale;

public final class MessageType {
    private MessageType() {
    }

    public static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
