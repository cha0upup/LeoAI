package org.leo.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 密码工具类，提供 MD5 哈希与校验能力。
 */
public final class PasswordUtil {

    private PasswordUtil() {}

    /**
     * 将明文密码转为小写 MD5 十六进制字符串。
     */
    public static String md5(String input) {
        if (input == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    /**
     * 校验明文密码与存储的 MD5 是否匹配。
     */
    public static boolean verify(String rawPassword, String storedMd5) {
        if (rawPassword == null || storedMd5 == null) return false;
        return storedMd5.equals(md5(rawPassword));
    }
}
