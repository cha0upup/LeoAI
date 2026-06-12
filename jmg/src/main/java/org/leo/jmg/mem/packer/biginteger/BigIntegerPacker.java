package org.leo.jmg.mem.packer.biginteger;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

import java.math.BigInteger;

@PackerMeta(name = "BigInteger", order = 100)
public class BigIntegerPacker implements Packer {
    @Override
    public String pack(ClassPackerConfig config) {
        return new BigInteger(1, config.getClassBytes()).toString(Character.MAX_RADIX);
    }
}
