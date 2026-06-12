package org.leo.jmg.mem.packer.base64;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

@PackerMeta(name = "DefaultBase64", group = "Base64", order = 1)
public class DefaultBase64Packer implements Packer {
    @Override
    public String pack(ClassPackerConfig config) {
        return config.getClassBytesBase64Str();
    }
}
