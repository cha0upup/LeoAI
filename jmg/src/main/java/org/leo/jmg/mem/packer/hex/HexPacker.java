package org.leo.jmg.mem.packer.hex;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

/**
 * Hex 编码打包器。
 * <p>
 * 将类字节码转为十六进制字符串，适用于 JNDI 注入、部分 WAF 绕过等场景。
 *
 * @author LeoSpring
 */
@PackerMeta(name = "Hex", group = "Hex", order = 1)
public class HexPacker implements Packer {

    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        byte[] bytes = config.getClassBytes();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            result[i * 2] = HEX_ARRAY[v >>> 4];
            result[i * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(result);
    }
}
