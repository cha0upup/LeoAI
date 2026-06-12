package org.leo.jmg.mem.packer.translet;

import org.apache.tomcat.util.codec.binary.Base64;
import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;
import org.leo.jmg.util.asm.ClassSuperClassUtils;

@PackerMeta(name = "OracleAbstractTransletPacker", group = "AbstractTranslet", order = 3, requiresAbstractTranslet = true)
public class OracleAbstractTransletPacker implements Packer {
    @Override
    public String pack(ClassPackerConfig config) {
        String superClassName = "com.oracle.wls.shaded.org.apache.xalan.xsltc.runtime.AbstractTranslet";
        byte[] bytes = Base64.decodeBase64(config.getClassBytesBase64Str());
        byte[] newBytes = ClassSuperClassUtils.addSuperClass(bytes, superClassName);
        return Base64.encodeBase64String(newBytes);
    }
}
