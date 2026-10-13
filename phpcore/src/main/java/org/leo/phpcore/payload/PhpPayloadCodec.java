package org.leo.phpcore.payload;

import org.leo.core.payload.PayloadFrameCodec;
import org.leo.core.util.json.PortableJsonCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

/** PHP-compatible payload codec: portable JSON -> GZIP -> AES-CBC. */
public final class PhpPayloadCodec {
    private final PayloadFrameCodec frameCodec;

    public PhpPayloadCodec(String userKey) {
        if (userKey == null || userKey.trim().isEmpty()) {
            throw new IllegalArgumentException("PHP PayloadCodec AES 密钥不能为空");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-512")
                    .digest(userKey.getBytes(StandardCharsets.UTF_8));
            this.frameCodec = new PayloadFrameCodec(Arrays.copyOf(digest, PayloadFrameCodec.KEY_BYTES), "PHP PayloadCodec");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 PHP PayloadCodec 失败", e);
        }
    }

    public byte[] encode(Map<String, Object> payload) throws Exception {
        if (payload == null) throw new IllegalArgumentException("payload 不能为空");
        return frameCodec.encode(PortableJsonCodec.encode(payload));
    }

    public Map<String, Object> decode(byte[] encoded) throws Exception {
        return PortableJsonCodec.decode(frameCodec.decode(encoded));
    }
}
