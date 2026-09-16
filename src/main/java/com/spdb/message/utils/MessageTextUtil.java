package com.spdb.message.utils;

import java.nio.charset.StandardCharsets;

/** 报文文本解码工具：返回报文统一按 UTF-8 解码。 */
public final class MessageTextUtil {

    private static final char BOM = '\uFEFF';

    private MessageTextUtil() {
    }

    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }
}
