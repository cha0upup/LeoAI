package org.leo.ai.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.leo.core.security.AesGcmSecretCipher;

import java.nio.file.Path;

/** AES-256-GCM 加密模型 API Key 与敏感请求头。 */
@Component
public class AiSecretCryptoService {

    private static final Logger log = LoggerFactory.getLogger(AiSecretCryptoService.class);
    static final String PREFIX = "enc:v1:";
    private final AesGcmSecretCipher cipher;

    public AiSecretCryptoService(
            @Value("${leo.ai.secrets.master-key:}") String configuredMasterKey,
            @Value("${leo.ai.secrets.key-file:.leo/ai-secrets.key}") String configuredKeyFile) {
        boolean externalKey = configuredMasterKey != null && !configuredMasterKey.isBlank();
        Path keyFile = externalKey ? null : Path.of(configuredKeyFile == null || configuredKeyFile.isBlank()
                        ? ".leo/ai-secrets.key" : configuredKeyFile).toAbsolutePath().normalize();
        this.cipher = new AesGcmSecretCipher(configuredMasterKey, keyFile, PREFIX, "leo-ai-model-secret:v1");
        if (externalKey) {
            log.info("AI Secret 使用外部主密钥");
        } else {
            log.info("AI Secret 使用本地密钥文件: {}", keyFile);
        }
    }

    public String encrypt(String plaintext) {
        try {
            return cipher.encrypt(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("AI Secret 加密失败", e);
        }
    }

    public String decrypt(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) return storedValue;
        if (!isEncrypted(storedValue)) {
            throw new IllegalArgumentException("AI Secret 必须使用当前密文格式存储");
        }
        try {
            return cipher.decrypt(storedValue);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AI Secret 解密失败，请检查 LEO_AI_MASTER_KEY 或本地密钥文件是否与数据库匹配", e);
        }
    }

    public boolean isEncrypted(String value) {
        return cipher.isEncrypted(value);
    }
}
