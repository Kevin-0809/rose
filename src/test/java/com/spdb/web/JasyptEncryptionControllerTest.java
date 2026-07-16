package com.spdb.web;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.iv.RandomIvGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JasyptEncryptionControllerTest {

    private final JasyptEncryptionService service = new JasyptEncryptionService();
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("JASYPT_ENCRYPTOR_PASSWORD", "test-master-password");
    private final JasyptEncryptionController controller = new JasyptEncryptionController(service, environment);

    @Test
    void encryptsPlainTextWithConfiguredMasterPassword() {
        JasyptEncryptionController.EncryptResponse response = controller.encrypt(
                new JasyptEncryptionController.EncryptRequest("database-secret")
        );

        assertThat(response.encryptedText()).isNotBlank();
        assertThat(response.encryptedText()).isNotEqualTo("database-secret");
        assertThat(response.propertyValue()).isEqualTo("ENC(" + response.encryptedText() + ")");
        assertThat(decrypt(response.encryptedText(), "test-master-password")).isEqualTo("database-secret");
    }

    @Test
    void rejectsMissingConfiguredMasterPassword() {
        JasyptEncryptionController controllerWithoutMasterPassword =
                new JasyptEncryptionController(service, new MockEnvironment());

        assertThatThrownBy(() -> controllerWithoutMasterPassword.encrypt(
                new JasyptEncryptionController.EncryptRequest("database-secret")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void rejectsBlankPlainText() {
        JasyptEncryptionController.EncryptRequest request =
                new JasyptEncryptionController.EncryptRequest("");

        assertThatThrownBy(() -> controller.encrypt(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String decrypt(String encryptedText, String masterPassword) {
        StandardPBEStringEncryptor encryptor = new StandardPBEStringEncryptor();
        encryptor.setAlgorithm("PBEWITHHMACSHA512ANDAES_256");
        encryptor.setPassword(masterPassword);
        encryptor.setIvGenerator(new RandomIvGenerator());
        return encryptor.decrypt(encryptedText);
    }
}
