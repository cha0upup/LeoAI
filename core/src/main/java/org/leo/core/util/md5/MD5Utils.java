package org.leo.core.util.md5;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MD5Utils {

    // 算法常量
    private static final String ALGORITHM_MD5 = "MD5";

    // 十六进制常量
    private static final int HEX_MASK = 0xff;
    private static final int HEX_PADDING = 1;

    /**
     * 计算字符串的MD5哈希值
     *
     * @param input 待计算的字符串
     * @return MD5哈希值的十六进制字符串表示
     * @throws IllegalArgumentException 如果输入字符串为空
     * @throws RuntimeException 如果MD5算法不可用
     */
    public static String getMD5Hash(String input) {
        if (input == null) {
            throw new IllegalArgumentException("输入字符串不能为空");
        }

        try {
            // 创建一个MessageDigest实例，用于计算MD5
            MessageDigest md = MessageDigest.getInstance(ALGORITHM_MD5);

            // 将字符串转换为字节数组并计算MD5
            byte[] messageDigest = md.digest(input.getBytes(StandardCharsets.UTF_8));

            // 将字节数组转换为十六进制字符串
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                // 将每个字节转换为两位的十六进制数
                String hex = Integer.toHexString(HEX_MASK & b);
                if (hex.length() == HEX_PADDING) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not found", e);
        }
    }
}