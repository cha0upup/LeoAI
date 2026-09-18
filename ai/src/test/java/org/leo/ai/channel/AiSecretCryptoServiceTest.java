package org.leo.ai.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSecretCryptoServiceTest {

    // Captured from the separate v1 implementation before extracting the shared cipher.
    private static final String LEGACY_CIPHERTEXT =
            "enc:v1:1BwLecA1XCPj6xat9L2e44bDElF9nMadxpqG9YuzFgWGosJpnu1UEHPyPlXOpzHv";

    @TempDir
    Path tempDir;

    @Test
    void encryptsWithRandomNonceAndDecrypts() {
        AiSecretCryptoService crypto = new AiSecretCryptoService("master-key-a", "unused");

        String first = crypto.encrypt("sk-sensitive");
        String second = crypto.encrypt("sk-sensitive");

        assertTrue(crypto.isEncrypted(first));
        assertNotEquals(first, second);
        assertEquals("sk-sensitive", crypto.decrypt(first));
        assertEquals("sk-sensitive", crypto.decrypt(second));
    }

    @Test
    void rejectsPlaintextDatabaseValues() {
        AiSecretCryptoService crypto = new AiSecretCryptoService("master-key-a", "unused");
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("plaintext-value"));
    }

    @Test
    void rejectsCiphertextEncryptedWithDifferentMasterKey() {
        AiSecretCryptoService first = new AiSecretCryptoService("master-key-a", "unused");
        AiSecretCryptoService second = new AiSecretCryptoService("master-key-b", "unused");

        String encrypted = first.encrypt("sk-sensitive");
        assertThrows(IllegalStateException.class, () -> second.decrypt(encrypted));
    }

    @Test
    void reusesGeneratedLocalKeyFileAcrossRestarts() {
        Path keyFile = tempDir.resolve("ai.key");
        AiSecretCryptoService first = new AiSecretCryptoService("", keyFile.toString());
        String encrypted = first.encrypt("header-secret");

        AiSecretCryptoService second = new AiSecretCryptoService("", keyFile.toString());
        assertEquals("header-secret", second.decrypt(encrypted));
    }

    @Test
    void decryptsExistingV1Ciphertext() {
        AiSecretCryptoService crypto = new AiSecretCryptoService("compatibility-test-key", "unused");
        assertEquals("legacy-secret-中文", crypto.decrypt(LEGACY_CIPHERTEXT));
    }

    @Test
    void rejectsTamperedCiphertextAndDatabaseCiphertextEvenWithAiPrefix() {
        AiSecretCryptoService crypto = new AiSecretCryptoService("compatibility-test-key", "unused");
        byte[] payload = Base64.getDecoder().decode(LEGACY_CIPHERTEXT.substring(AiSecretCryptoService.PREFIX.length()));
        payload[payload.length - 1] ^= 1;
        String tampered = AiSecretCryptoService.PREFIX + Base64.getEncoder().encodeToString(payload);
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));

        String databaseCiphertextWithAiPrefix =
                "enc:v1:k8cm9P/m6bD0Dub14squ64boErxqtnpqrCsROgJ2BLcOv1/FPc7ZS19Eup5uRwjp";
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(databaseCiphertextWithAiPrefix));
    }
}
