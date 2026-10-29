package org.leo.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SafeZipReaderTest {
    @Test
    void visitsMatchingEntriesInOrderAndClosesTheInput() throws Exception {
        var input = archive("folder/", "ignore.txt", "first.json", "second.json");
        List<String> visited = new ArrayList<>();
        SafeZipReader.forEach(input, name -> name.endsWith(".json"), null, (name, bytes) ->
                visited.add(name + ":" + new String(bytes, StandardCharsets.UTF_8)));
        assertEquals(List.of("first.json:data", "second.json:data"), visited);
        assertTrue(input.closed);
    }

    @ParameterizedTest
    @MethodSource("limits")
    void enforcesEntryCountIndividualSizeAndTotalSize(SafeZipReader.Limits limits) throws Exception {
        var input = archive("first.json", "second.json");
        assertThrows(SafeZipReader.ZipLimitExceededException.class,
                () -> SafeZipReader.forEach(input, null, limits, (name, bytes) -> {}));
        assertTrue(input.closed);
    }

    @Test
    void filteredEntriesStillCountTowardTheEntryLimit() throws Exception {
        var input = archive("ignore.txt", "first.json");
        List<String> visited = new ArrayList<>();
        assertThrows(SafeZipReader.ZipLimitExceededException.class, () ->
                SafeZipReader.forEach(input, name -> name.endsWith(".json"),
                        new SafeZipReader.Limits(1, 10, 20), (name, bytes) -> visited.add(name)));
        assertTrue(visited.isEmpty());
    }

    @Test
    void propagatesHandlerFailuresAndClosesTheInput() throws Exception {
        var input = archive("first.json", "second.json");
        IOException failure = new IOException("fixture failure");
        var error = assertThrows(IOException.class, () -> SafeZipReader.forEach(input, null, null,
                (name, bytes) -> { throw failure; }));
        assertSame(failure, error);
        assertTrue(input.closed);
    }

    private static Stream<SafeZipReader.Limits> limits() {
        return Stream.of(new SafeZipReader.Limits(1, 10, 20),
                new SafeZipReader.Limits(2, 3, 20), new SafeZipReader.Limits(2, 10, 7));
    }

    private TrackedInput archive(String... names) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) zip.write("data".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new TrackedInput(bytes.toByteArray());
    }

    private static final class TrackedInput extends ByteArrayInputStream {
        boolean closed;
        TrackedInput(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
