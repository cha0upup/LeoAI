package org.leo.jmg.mem.packer.rhino;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.mem.packer.PackerRegistry;

@PackerMeta(name = "Rhino", order = 100)
public class RhinoPacker implements Packer {

    @Override
    public String pack(ClassPackerConfig config) throws Exception {
        return PackerRegistry.getOrThrow("DefaultScriptEngine").pack(config);
    }
}
