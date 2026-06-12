package org.leo.jmg.mem.packer.hex;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/**
 * Gzip + Hex 编码打包器。
 * <p>
 * 先 gzip 压缩再 hex 编码，减小传输体积。
 *
 * @author LeoSpring
 */
@PackerMeta(name = "GzipHex", group = "Hex", order = 2)
public class GzipHexPacker implements Packer {

    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        byte[] compressed = gzipCompress(config.getClassBytes());
        return encodeHex(compressed);
    }

    private static String encodeHex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            result[i * 2] = HEX_ARRAY[v >>> 4];
            result[i * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(result);
    }

    private static byte[] gzipCompress(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        }
        return out.toByteArray();
    }
}
