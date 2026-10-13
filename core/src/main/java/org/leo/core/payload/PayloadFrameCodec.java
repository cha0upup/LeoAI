package org.leo.core.payload;

import org.leo.core.net.TransportLimits;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Server-side frame shared by Java serialization and PHP JSON: random IV + AES-CBC(GZIP). */
public final class PayloadFrameCodec {
    public static final int KEY_BYTES = 16;
    private static final int IV_BYTES = 16;
    private static final int BLOCK_BYTES = 16;
    private static final int BUFFER_BYTES = 8192;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final String label;

    public PayloadFrameCodec(byte[] keyBytes, String label) {
        if (keyBytes == null || keyBytes.length != KEY_BYTES) throw new IllegalArgumentException("AES key must contain 16 bytes");
        this.key = new SecretKeySpec(keyBytes, "AES");
        this.label = Objects.requireNonNull(label);
    }

    public byte[] encode(byte[] payload) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output, BUFFER_BYTES)) {
            gzip.write(payload);
        }
        byte[] compressed = output.toByteArray();
        TransportLimits.requireMessageSize(compressed);
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        byte[] frame = new byte[IV_BYTES + cipher.getOutputSize(compressed.length)];
        TransportLimits.requireMessageSize(frame);
        System.arraycopy(iv, 0, frame, 0, IV_BYTES);
        // Write full blocks into the frame; doFinal only handles the remaining tail/padding.
        int length = IV_BYTES + cipher.update(compressed, 0, compressed.length, frame, IV_BYTES);
        length += cipher.doFinal(frame, length);
        return length == frame.length ? frame : Arrays.copyOf(frame, length);
    }

    public byte[] decode(byte[] frame) throws Exception {
        if (frame == null || frame.length < IV_BYTES + BLOCK_BYTES
                || (frame.length - IV_BYTES) % BLOCK_BYTES != 0) {
            throw new IllegalArgumentException(label + " 数据长度无效");
        }
        TransportLimits.requireMessageSize(frame);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(frame, 0, IV_BYTES));
        byte[] compressed = cipher.doFinal(frame, IV_BYTES, frame.length - IV_BYTES);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed), BUFFER_BYTES)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int length;
            while ((length = gzip.read(buffer)) != -1) {
                if (length > TransportLimits.MAX_MESSAGE_BYTES - output.size()) {
                    throw new IllegalArgumentException(label + " 解压结果超过限制");
                }
                output.write(buffer, 0, length);
            }
        }
        return output.toByteArray();
    }
}
