package org.leo.core.payload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.net.TransportException;
import org.leo.core.net.TransportLimits;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PayloadFrameCodecTest {
    private final byte[] key = new byte[PayloadFrameCodec.KEY_BYTES];
    private final PayloadFrameCodec codec = new PayloadFrameCodec(key, "fixture");

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 15, 16, 17, 8192, 1048576})
    void interoperatesWithIndependentGzipAesEncoding(int size) throws Exception {
        byte[] payload = new byte[size];
        new Random(37).nextBytes(payload);
        byte[] frame = codec.encode(payload), original = frame.clone();
        assertArrayEquals(payload, codec.decode(referenceFrame(payload)));
        assertArrayEquals(payload, codec.decode(frame));
        assertArrayEquals(original, frame);
        assertFalse(Arrays.equals(Arrays.copyOf(frame, 16), Arrays.copyOf(codec.encode(payload), 16)));
        assertEquals(0, (frame.length - 16) % 16);
        Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, Arrays.copyOf(frame, 16));
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(cipher.doFinal(frame, 16, frame.length - 16)))) {
            assertArrayEquals(payload, gzip.readAllBytes());
        }
    }

    @Test
    void rejectsInvalidFramesWrongKeysAndCorruptCompressedData() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(null));
        for (int size : new int[]{0, 15, 31, 33}) {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[size]));
        }
        byte[] otherKey = key.clone();
        otherKey[0] = 1;
        assertThrows(Exception.class, () -> new PayloadFrameCodec(otherKey, "other").decode(codec.encode(new byte[128])));
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, new byte[16]);
        byte[] invalid = cipher.doFinal(new byte[]{1, 2, 3});
        byte[] frame = new byte[16 + invalid.length];
        System.arraycopy(invalid, 0, frame, 16, invalid.length);
        assertThrows(java.io.IOException.class, () -> codec.decode(frame));
    }

    @Test
    void enforcesFrameAndDecompressionLimits() throws Exception {
        TransportException oversized = assertThrows(TransportException.class,
                () -> codec.decode(new byte[TransportLimits.MAX_MESSAGE_BYTES + 32]));
        assertEquals(TransportException.Reason.MESSAGE_TOO_LARGE, oversized.getReason());
        byte[] payload = new byte[TransportLimits.MAX_MESSAGE_BYTES];
        assertEquals(payload.length, codec.decode(codec.encode(payload)).length);
        byte[] bomb = codec.encode(new byte[payload.length + 1]);
        assertEquals("fixture 解压结果超过限制",
                assertThrows(IllegalArgumentException.class, () -> codec.decode(bomb)).getMessage());
    }

    private byte[] referenceFrame(byte[] payload) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(payload); }
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, key, new byte[16]).doFinal(output.toByteArray());
        byte[] frame = new byte[16 + encrypted.length];
        System.arraycopy(encrypted, 0, frame, 16, encrypted.length);
        return frame;
    }

    private Cipher cipher(int mode, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher;
    }
}
