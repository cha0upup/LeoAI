package org.leo.jmg.mem.packer.base64;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

@PackerMeta(name = "GzipBase64", group = "Base64", order = 3)
public class GzipBase64Packer implements Packer {
    @Override
    public String pack(ClassPackerConfig config) throws IOException {
        return Base64.getEncoder().encodeToString(gzipCompress(config.getClassBytes()));
    }

    public static byte[] gzipCompress(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        }
        return out.toByteArray();
    }
}