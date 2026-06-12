package org.leo.jmg.mem.packer.translet;

import org.apache.tomcat.util.codec.binary.Base64;
import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.util.asm.ClassSuperClassUtils;

@PackerMeta(name = "JDKAbstractTransletPacker", group = "AbstractTranslet", order = 1, requiresAbstractTranslet = true)
public class JDKAbstractTransletPacker implements Packer {
    @Override
    public String pack(ClassPackerConfig config) {
        String superClassName = "com.sun.org.apache.xalan.internal.xsltc.runtime.AbstractTranslet";
        byte[] bytes = Base64.decodeBase64(config.getClassBytesBase64Str());
        byte[] newBytes = ClassSuperClassUtils.addSuperClass(bytes, superClassName);
        return Base64.encodeBase64String(newBytes);
    }
}
