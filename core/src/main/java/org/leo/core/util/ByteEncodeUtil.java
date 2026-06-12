package org.leo.core.util;

import java.util.Base64;

/**
 * 字节编码工具类
 * 提供字节数组的压缩和编码功能
 */
public class ByteEncodeUtil {

    /**
     * 对字节数组进行 Base64 编码，如果结果尾部包含 '='，
     * 则在原始数据末尾依次追加空格，直到编码结果不再以 '=' 结尾。
     *
     * 说明：Base64 填充只与数据长度有关（按 3 字节一组补齐），
     * 最多补 2 个 '='，因此这里最多追加 2 个空格即可保证无 '='。
     *
     * @param data 原始字节数据
     * @return 尾部不带 '=' 的 Base64 字符串
     * @throws IllegalArgumentException 如果 data 为 null
     */
    public static String base64EncodeWithoutTailEquals(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data参数不能为空");
        }

        // 计算当前长度对 3 的余数，按需要一次性补空格，避免不必要循环
        int len = data.length;
        int mod = len % 3;
        byte[] toEncode;
        if (mod == 0) {
            toEncode = data;
        } else {
            int needSpaces = (mod == 1) ? 2 : 1;
            toEncode = new byte[len + needSpaces];
            System.arraycopy(data, 0, toEncode, 0, len);
            for (int i = 0; i < needSpaces; i++) {
                toEncode[len + i] = ' ';
            }
        }

        return Base64.getEncoder().encodeToString(toEncode);
    }

    /**
     * 对字符串（UTF-8）进行 Base64 编码，如果结果尾部包含 '='，
     * 则在原始字符串末尾追加空格，直到编码结果不再以 '=' 结尾。
     *
     * @param text 原始字符串
     * @return 尾部不带 '=' 的 Base64 字符串
     * @throws IllegalArgumentException 如果 text 为 null
     */
    public static String base64EncodeWithoutTailEquals(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text参数不能为空");
        }
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return base64EncodeWithoutTailEquals(bytes);
    }

}
