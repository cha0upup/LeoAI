package org.leo.jmg.util.hex;

public class HexUtils {
    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    public static String encodeHexString(byte[] bytes) {
        char[] result = new char[bytes.length * 2];

        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF; // 防止负数

            result[i * 2]     = HEX_ARRAY[v >>> 4]; // 高4位
            result[i * 2 + 1] = HEX_ARRAY[v & 0x0F]; // 低4位
        }

        return new String(result);
    }
}
