package com.spdb.web;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class JasyptEncryptionController {
    private final JasyptEncryptionService encryptionService;
    private final Environment environment;

    public JasyptEncryptionController(JasyptEncryptionService encryptionService, Environment environment) {
        this.encryptionService = encryptionService;
        this.environment = environment;
    }

    @PostMapping("/api/jasypt/encrypt")
    public EncryptResponse encrypt(@RequestBody EncryptRequest request) {
        if (request == null || !StringUtils.hasText(request.plainText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "plainText is required");
        }
        String masterPassword = configuredMasterPassword();
        String encryptedText = encryptionService.encrypt(request.plainText(), masterPassword);
        return new EncryptResponse(encryptedText, "ENC(" + encryptedText + ")");
    }

    private String configuredMasterPassword() {
        String masterPassword = environment.getProperty("jasypt.encryptor.password");
        if (!StringUtils.hasText(masterPassword)) {
            masterPassword = environment.getProperty("JASYPT_ENCRYPTOR_PASSWORD");
        }
        if (!StringUtils.hasText(masterPassword)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "JASYPT_ENCRYPTOR_PASSWORD is required");
        }
        return masterPassword;
    }

    public record EncryptRequest(String plainText) {
    }

    public record EncryptResponse(String encryptedText, String propertyValue) {
    }
}
