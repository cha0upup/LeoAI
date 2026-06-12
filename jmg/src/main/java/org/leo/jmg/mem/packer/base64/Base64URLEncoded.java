package org.leo.jmg.mem.packer.base64;

import org.leo.jmg.mem.packer.ClassPackerConfig;
import org.leo.jmg.mem.packer.Packer;
import org.leo.jmg.mem.packer.PackerMeta;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@PackerMeta(name = "Base64URLEncoded", group = "Base64", order = 2)
public class Base64URLEncoded implements Packer {

    @Override
    public String pack(ClassPackerConfig config) throws UnsupportedEncodingException {
        return URLEncoder.encode(config.getClassBytesBase64Str(), StandardCharsets.UTF_8.name());
    }
}
