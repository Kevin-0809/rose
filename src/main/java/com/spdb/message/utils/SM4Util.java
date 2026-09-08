package com.spdb.message.utils;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Security;

/**
 * Minimal SM4 ECB helper used by AuthUtil.
 */
public final class SM4Util {
    private static final String ALGORITHM = "SM4/ECB/PKCS5Padding";

    static {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
    }

    private SM4Util() {
    }

    public static byte[] encrypt(byte[] plainText, byte[] key) throws Exception {
        return crypt(Cipher.ENCRYPT_MODE, plainText, key);
    }

    public static byte[] decrypt(byte[] cipherText, byte[] key) throws Exception {
        return crypt(Cipher.DECRYPT_MODE, cipherText, key);
    }

    private static byte[] crypt(int mode, byte[] data, byte[] key) throws Exception {
        if (key == null || key.length != 16) throw new IllegalArgumentException("SM4 key must be 16 bytes");
        Cipher cipher = Cipher.getInstance(ALGORITHM, "BC");
        cipher.init(mode, new SecretKeySpec(key, "SM4"));
        return cipher.doFinal(data == null ? new byte[0] : data);
    }
}
