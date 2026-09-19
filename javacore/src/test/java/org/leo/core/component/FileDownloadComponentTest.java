package org.leo.core.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.core.component.ComponentTestSupport.*;

class FileDownloadComponentTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundsLongRangesBeforeConvertingToInt(boolean payload) throws Exception {
        Path file = tempDir.resolve("large.bin");
        try (RandomAccessFile sparse = new RandomAccessFile(file.toFile(), "rw")) {
            sparse.setLength(4294967296L);
        }
        Object component = component("FileDownloadComponent", payload);
        for (long size : new long[]{2147483648L, 4294967296L, Long.MAX_VALUE}) {
            Map<String, Object> result = invoke(component, file, 0L, size);
            assertEquals(100, result.get("code"));
            assertEquals(1048576, result.get("bytesRead"));
            assertEquals(1048576L, result.get("nextOffset"));
            assertEquals(1048576, ((byte[]) result.get("data")).length);
        }
    }

    @Test
    void readsBoundedChunksWithoutChangingTheWireContract() throws Exception {
        Path file = tempDir.resolve("chunk.txt");
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);

        Map<String, Object> first = invoke(file, 0L, 4L);
        assertEquals(100, first.get("code"));
        assertEquals(4, first.get("bytesRead"));
        assertEquals(4L, first.get("nextOffset"));
        assertEquals(Boolean.FALSE, first.get("isComplete"));
        assertArrayEquals("0123".getBytes(StandardCharsets.UTF_8), (byte[]) first.get("data"));

        Map<String, Object> last = invoke(file, 4L, 32L);
        assertEquals(200, last.get("code"));
        assertEquals(6, last.get("bytesRead"));
        assertEquals(10L, last.get("nextOffset"));
        assertEquals(Boolean.TRUE, last.get("isComplete"));
        assertArrayEquals("456789".getBytes(StandardCharsets.UTF_8), (byte[]) last.get("data"));
    }

    @Test
    void returnsTheExistingEmptyFileShape() throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.createFile(file);

        Map<String, Object> result = invoke(file, 0L, 1L);
        assertEquals(200, result.get("code"));
        assertEquals(0, result.get("bytesRead"));
        assertEquals(Boolean.TRUE, result.get("isComplete"));
        assertTrue(((byte[]) result.get("data")).length == 0);
    }

    @Test
    void acceptsStringNumbersFromTransportDecoders() throws Exception {
        Path file = tempDir.resolve("string-numbers.txt");
        Files.write(file, "012345".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> result = invoke(file, "2", "3");

        assertEquals(100, result.get("code"));
        assertEquals(3, result.get("bytesRead"));
        assertEquals(5L, result.get("nextOffset"));
        assertArrayEquals("234".getBytes(StandardCharsets.UTF_8), (byte[]) result.get("data"));
    }

    private Map<String, Object> invoke(Path file, Object offset, Object size) throws Exception {
        return invoke(new FileDownloadComponent(), file, offset, size);
    }

    private Map<String, Object> invoke(Object component, Path file, Object offset, Object size) throws Exception {
        return invokeComponent(component, params(
                "path", file.toString().getBytes(StandardCharsets.UTF_8),
                "offset", offset, "size", size));
    }
}
