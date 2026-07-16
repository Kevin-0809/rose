package com.spdb.web;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.iv.RandomIvGenerator;
import org.springframework.stereotype.Service;

@Service
public class JasyptEncryptionService {
    private static final String DEFAULT_ALGORITHM = "PBEWITHHMACSHA512ANDAES_256";

    public String encrypt(String plainText, String masterPassword) {
        StandardPBEStringEncryptor encryptor = new StandardPBEStringEncryptor();
        encryptor.setAlgorithm(DEFAULT_ALGORITHM);
        encryptor.setPassword(masterPassword);
        encryptor.setIvGenerator(new RandomIvGenerator());
        return encryptor.encrypt(plainText);
    }
}
