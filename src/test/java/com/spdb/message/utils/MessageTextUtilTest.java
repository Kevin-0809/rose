package com.spdb.message.utils;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTextUtilTest {

    @Test
    void decodesResponseBodyWithUtf8() {
        String text = "{\"returnMsg\":\"交易成功\"}";

        assertThat(MessageTextUtil.decode(text.getBytes(StandardCharsets.UTF_8))).isEqualTo(text);
    }

    @Test
    void stripsUtf8BomFromDecodedText() {
        byte[] bytes = "{\"returnCode\":\"000000\"}".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bytes.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(bytes, 0, withBom, 3, bytes.length);

        assertThat(MessageTextUtil.decode(withBom)).startsWith("{\"returnCode\"");
    }

    @Test
    void returnsEmptyTextForEmptyBody() {
        assertThat(MessageTextUtil.decode(null)).isEmpty();
        assertThat(MessageTextUtil.decode(new byte[0])).isEmpty();
    }
}
