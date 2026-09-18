package org.leo.core.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;

/** Shared storage format: domain prefix followed by Base64(nonce + GCM ciphertext). */
public final class AesGcmSecretCipher {

    private static final int NONCE_BYTES = 12;
    private static final int KEY_BYTES = 32;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec key;
    private final String prefix;
    private final byte[] aad;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmSecretCipher(String masterKey, Path keyFile, String prefix, String aad) {
        this.prefix = prefix;
        this.aad = aad.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] keyBytes = masterKey != null && !masterKey.isBlank()
                    ? MessageDigest.getInstance("SHA-256").digest(masterKey.getBytes(StandardCharsets.UTF_8))
                    : loadOrCreateKeyFile(keyFile);
            this.key = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception error) {
            throw new IllegalStateException("无法加载或创建凭据加密密钥", error);
        }
    }

    public String encrypt(String plaintext) throws GeneralSecurityException {
        if (plaintext == null || plaintext.isBlank() || isEncrypted(plaintext)) return plaintext;
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] payload = Arrays.copyOf(nonce, nonce.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
        return prefix + Base64.getEncoder().encodeToString(payload);
    }

    public String decrypt(String storedValue) throws GeneralSecurityException {
        if (storedValue == null || storedValue.isBlank()) return storedValue;
        if (!isEncrypted(storedValue)) throw new IllegalArgumentException("凭据必须使用当前密文格式存储");
        byte[] payload = Base64.getDecoder().decode(storedValue.substring(prefix.length()));
        if (payload.length <= NONCE_BYTES) throw new IllegalArgumentException("密文长度无效");
        Cipher cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(payload, NONCE_BYTES));
        return new String(cipher.doFinal(payload, NONCE_BYTES, payload.length - NONCE_BYTES),
                StandardCharsets.UTF_8);
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(prefix);
    }

    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher;
    }

    private static byte[] loadOrCreateKeyFile(Path keyFile) throws java.io.IOException {
        Path parent = keyFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (!Files.exists(keyFile)) {
            byte[] generated = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(generated);
            try {
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(generated),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException ignored) {
                // Concurrent startup created the key; read the same file below.
            }
        }
        try {
            Files.setPosixFilePermissions(keyFile, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // Non-POSIX filesystems rely on host access control.
        }
        byte[] decoded = Base64.getDecoder().decode(Files.readString(keyFile).trim());
        if (decoded.length != KEY_BYTES) throw new IllegalStateException("密钥文件长度无效: " + keyFile);
        return decoded;
    }
}
