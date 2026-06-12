package org.leo.jmg.mem.packer.groovy;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.Util;

@PackerMeta(name = "GroovyClassDefiner", group = "Groovy", order = 1)
public class GroovyClassDefinerPacker implements Packer {
    private final String template = Util.loadTemplateFromResource("/memshell-template/shell.groovy");

    @Override
    public String pack(ClassPackerConfig config) {
        return template.replace("{{className}}", config.getClassName())
                .replace("{{base64Str}}", config.getClassBytesBase64Str());
    }
}
