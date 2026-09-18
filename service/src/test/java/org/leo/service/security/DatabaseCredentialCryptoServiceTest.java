package org.leo.service.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseCredentialCryptoServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptsAndDecryptsWithAuthenticatedCiphertext() {
        DatabaseCredentialCryptoService crypto =
                new DatabaseCredentialCryptoService("test-master-key", tempDir.resolve("unused.key").toString());

        String encrypted = crypto.encrypt("s3cret!");

        assertNotEquals("s3cret!", encrypted);
        assertTrue(crypto.isEncrypted(encrypted));
        assertEquals("s3cret!", crypto.decrypt(encrypted));
        assertEquals(encrypted, crypto.encrypt(encrypted));
    }

    @Test
    void rejectsPlaintextDatabaseValues() {
        DatabaseCredentialCryptoService crypto =
                new DatabaseCredentialCryptoService("test-master-key", tempDir.resolve("unused.key").toString());

        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt("plaintext-value"));
    }

    @Test
    void decryptsExistingV1Ciphertext() {
        DatabaseCredentialCryptoService crypto =
                new DatabaseCredentialCryptoService("compatibility-test-key", "unused");
        // Captured from the separate v1 implementation before extracting the shared cipher.
        String encrypted = "enc:database:v1:k8cm9P/m6bD0Dub14squ64boErxqtnpqrCsROgJ2BLcOv1/FPc7ZS19Eup5uRwjp";
        assertEquals("legacy-secret-中文", crypto.decrypt(encrypted));
    }

    @Test
    void reusesLocalKeyAndRejectsAnotherKey() {
        Path keyFile = tempDir.resolve("nested/database.key");
        DatabaseCredentialCryptoService first = new DatabaseCredentialCryptoService("", keyFile.toString());
        String encrypted = first.encrypt("database-password");

        DatabaseCredentialCryptoService restarted = new DatabaseCredentialCryptoService("", keyFile.toString());
        assertEquals("database-password", restarted.decrypt(encrypted));
        DatabaseCredentialCryptoService other =
                new DatabaseCredentialCryptoService("", tempDir.resolve("other.key").toString());
        assertThrows(IllegalStateException.class, () -> other.decrypt(encrypted));
    }

}
