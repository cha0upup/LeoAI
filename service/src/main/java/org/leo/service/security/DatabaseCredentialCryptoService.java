package org.leo.service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.leo.core.security.AesGcmSecretCipher;

import java.nio.file.Path;

/** 使用 AES-256-GCM 加密数据库连接密码。 */
@Component
public class DatabaseCredentialCryptoService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCredentialCryptoService.class);
    static final String PREFIX = "enc:database:v1:";
    private final AesGcmSecretCipher cipher;

    public DatabaseCredentialCryptoService(
            @Value("${leo.database.secrets.master-key:}") String configuredMasterKey,
            @Value("${leo.database.secrets.key-file:.leo/database-secrets.key}") String configuredKeyFile) {
        boolean externalKey = configuredMasterKey != null && !configuredMasterKey.isBlank();
        Path keyFile = externalKey ? null : Path.of(configuredKeyFile == null || configuredKeyFile.isBlank()
                        ? ".leo/database-secrets.key" : configuredKeyFile).toAbsolutePath().normalize();
        this.cipher = new AesGcmSecretCipher(configuredMasterKey, keyFile, PREFIX, "leo-database-connection-secret:v1");
        if (externalKey) {
            log.info("Database Credential 使用外部主密钥");
        } else {
            log.info("Database Credential 使用本地密钥文件: {}", keyFile);
        }
    }

    public String encrypt(String plaintext) {
        try {
            return cipher.encrypt(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("Database Credential 加密失败", e);
        }
    }

    public String decrypt(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) return storedValue;
        if (!isEncrypted(storedValue)) {
            throw new IllegalArgumentException("Database Credential 必须使用当前密文格式存储");
        }
        try {
            return cipher.decrypt(storedValue);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Database Credential 解密失败，请检查 LEO_DATABASE_MASTER_KEY 或本地密钥文件", e);
        }
    }

    public boolean isEncrypted(String value) {
        return cipher.isEncrypted(value);
    }
}
