package org.leo.core.infrastructure.transport.httpchunk;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpChunkedBodyStreamTest {

    @Test
    void decodesExtensionsMultipleChunksAndTrailersAsContinuousBytes() throws Exception {
        String encoded = "3;source=test\r\nabc\r\n2\r\nde\r\n0\r\nX-End: yes\r\n\r\n";
        HttpChunkedBodyInputStream input = new HttpChunkedBodyInputStream(
                new ByteArrayInputStream(encoded.getBytes(StandardCharsets.US_ASCII)));
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        byte[] buffer = new byte[2];
        int length;
        while ((length = input.read(buffer)) != -1) {
            decoded.write(buffer, 0, length);
        }
        assertEquals("abcde", decoded.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void outputSplitsLargeWritesAndFinishesWithZeroChunk() throws Exception {
        byte[] payload = new byte[70 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        HttpChunkedBodyOutputStream output = new HttpChunkedBodyOutputStream(wire);
        output.write(payload);
        output.finish();

        HttpChunkedBodyInputStream decoded = new HttpChunkedBodyInputStream(
                new ByteArrayInputStream(wire.toByteArray()));
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = decoded.read(buffer)) != -1) collected.write(buffer, 0, length);
        assertArrayEquals(payload, collected.toByteArray());
    }

    @Test
    void rejectsMalformedChunkTermination() {
        byte[] wire = "1\r\naXX".getBytes(StandardCharsets.US_ASCII);
        HttpChunkedBodyInputStream input = new HttpChunkedBodyInputStream(
                new ByteArrayInputStream(wire));
        assertThrows(Exception.class, () -> {
            assertEquals('a', input.read());
            input.read();
        });
    }
}
