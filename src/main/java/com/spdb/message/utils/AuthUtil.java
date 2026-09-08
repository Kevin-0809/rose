package com.spdb.message.utils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Authentication token helper matching the service-registry token contract. */
public final class AuthUtil {
    private AuthUtil() { }

    public static byte[] getGK(String gk) {
        return Base64.getDecoder().decode(gk);
    }

    public static String getPK(String pk, byte[] gk) throws Exception {
        return decryptByBase64AndSm4(pk, gk);
    }

    public static String getWK(String wk, String pk) throws Exception {
        return decryptByBase64AndSm4(wk, pk.getBytes(StandardCharsets.UTF_8));
    }

    public static String encryptBySm4AndBase64(String plainInfo, String encryptKey) throws Exception {
        byte[] encrypted = SM4Util.encrypt(plainInfo.getBytes(StandardCharsets.UTF_8), encryptKey.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public static String decryptByBase64AndSm4(String cipherData, byte[] decryptKey) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(cipherData.getBytes(StandardCharsets.UTF_8));
        return new String(SM4Util.decrypt(decoded, decryptKey), StandardCharsets.UTF_8);
    }

    public static String genToken(String sid, String pk, String wk) throws Exception {
        return encryptBySm4AndBase64(pk + "," + sid + "," + System.currentTimeMillis(), wk);
    }

    public static String packToken(String sid, String gk, String pk, String wk) throws Exception {
        // TODO 临时返回固定 token，密钥解链问题排查清楚后恢复原逻辑
        return "FIXED_TOKEN";
        // String tpk = getPK(pk, getGK(gk));
        // String twk = getWK(wk, tpk);
        // return genToken(sid, tpk, twk);
    }
}
