package org.leo.web.service.ssh;

import org.leo.core.security.AesGcmSecretCipher;
import org.leo.core.util.json.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

/** Dedicated SSH key domain; encrypt an entire nonblank envelope so even whitespace passwords are encrypted. */
@Component
public class SshProfileCryptoService {
    private final AesGcmSecretCipher cipher;

    public SshProfileCryptoService(@Value("${leo.ssh.secrets.master-key:}") String masterKey,
                                   @Value("${leo.ssh.secrets.key-file:.leo/ssh-secrets.key}") String keyFile) {
        cipher = new AesGcmSecretCipher(masterKey,
                masterKey == null || masterKey.isBlank()
                        ? Path.of(keyFile == null || keyFile.isBlank() ? ".leo/ssh-secrets.key" : keyFile).toAbsolutePath().normalize() : null,
                "enc:ssh:v1:", "leo-ssh-profile-secret:v1");
    }
    public String encrypt(Map<String, Object> envelope) {
        try { return cipher.encrypt(JsonUtil.toJsonString(envelope)); }
        catch (Exception error) { throw new IllegalStateException("SSH 凭据加密失败", error); }
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> decrypt(String value) {
        try {
            if (!cipher.isEncrypted(value)) throw new IllegalArgumentException("SSH 凭据格式无效");
            return JsonUtil.fromJsonString(cipher.decrypt(value), Map.class);
        } catch (Exception error) { throw new IllegalStateException("SSH 凭据解密失败，请检查 SSH 主密钥或密钥文件", error); }
    }
}
