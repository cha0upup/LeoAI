package org.leo.core.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        Object component;
        if (payload) {
            try (var input = getClass().getResourceAsStream("/component/FileDownloadComponent.payload")) {
                component = new BytecodeLoader().load(input.readAllBytes()).getDeclaredConstructor().newInstance();
            }
        } else {
            component = new FileDownloadComponent();
        }
        for (long size : new long[]{2147483648L, 4294967296L, Long.MAX_VALUE}) {
            HashMap result = invoke(component, file, 0L, size);
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

        HashMap first = invoke(file, 0L, 4L);
        assertEquals(100, first.get("code"));
        assertEquals(4, first.get("bytesRead"));
        assertEquals(4L, first.get("nextOffset"));
        assertEquals(Boolean.FALSE, first.get("isComplete"));
        assertArrayEquals("0123".getBytes(StandardCharsets.UTF_8), (byte[]) first.get("data"));

        HashMap last = invoke(file, 4L, 32L);
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

        HashMap result = invoke(file, 0L, 1L);
        assertEquals(200, result.get("code"));
        assertEquals(0, result.get("bytesRead"));
        assertEquals(Boolean.TRUE, result.get("isComplete"));
        assertTrue(((byte[]) result.get("data")).length == 0);
    }

    @Test
    void acceptsStringNumbersFromTransportDecoders() throws Exception {
        Path file = tempDir.resolve("string-numbers.txt");
        Files.write(file, "012345".getBytes(StandardCharsets.UTF_8));

        HashMap result = invoke(file, "2", "3");

        assertEquals(100, result.get("code"));
        assertEquals(3, result.get("bytesRead"));
        assertEquals(5L, result.get("nextOffset"));
        assertArrayEquals("234".getBytes(StandardCharsets.UTF_8), (byte[]) result.get("data"));
    }

    private HashMap invoke(Path file, long offset, long size) throws Exception {
        return invoke(file, Long.valueOf(offset), Long.valueOf(size));
    }

    private HashMap invoke(Path file, Object offset, Object size) throws Exception {
        return invoke(new FileDownloadComponent(), file, offset, size);
    }

    private HashMap invoke(Object component, Path file, Object offset, Object size) throws Exception {
        HashMap params = new HashMap();
        params.put("path", file.toString().getBytes(StandardCharsets.UTF_8));
        params.put("offset", offset);
        params.put("size", size);
        HashMap results = new HashMap();
        setField(component, "params", params);
        setField(component, "results", results);
        component.getClass().getMethod("invoke").invoke(component);
        return results;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class BytecodeLoader extends ClassLoader {
        Class<?> load(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
}
