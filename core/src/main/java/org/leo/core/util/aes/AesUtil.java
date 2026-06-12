package org.leo.core.util.aes;


import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

public class AesUtil {

    // AES加密
    public static String encrypt(String content, String key) throws Exception {
        if (content == null) throw new IllegalArgumentException("内容不能为空");
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("密钥不能为空");

        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("utf-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        byte[] encrypted = cipher.doFinal(content.getBytes("utf-8")); // 指定 UTF-8
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public static String decrypt(String encryptedContent, String key) throws Exception {
        if (encryptedContent == null || encryptedContent.isEmpty()) throw new IllegalArgumentException("加密内容不能为空");
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("密钥不能为空");

        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("utf-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);

        byte[] decoded = Base64.getDecoder().decode(encryptedContent);
        byte[] decrypted = cipher.doFinal(decoded);

        return new String(decrypted, "utf-8"); // 指定 UTF-8
    }


}
