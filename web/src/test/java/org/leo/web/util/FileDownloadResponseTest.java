package org.leo.web.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ContentDisposition;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileDownloadResponseTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"report.txt", "中文 报告.txt", "quote\"name.txt", "line\r\nname.txt"})
    void streamsExactBytesWithAnEncodedDownloadName(String filename) throws Exception {
        byte[] content = {0, 1, 2, (byte) 255};
        Path file = Files.write(temp.resolve("fixture.bin"), content);
        var response = new MockHttpServletResponse();
        FileDownloadResponse.write(file, filename, response);
        assertEquals(200, response.getStatus());
        assertEquals("application/octet-stream", response.getContentType());
        assertEquals(content.length, response.getContentLengthLong());
        assertArrayEquals(content, response.getContentAsByteArray());
        String header = response.getHeader("Content-Disposition");
        assertNotNull(header);
        assertFalse(header.contains("\r"));
        assertFalse(header.contains("\n"));
        assertEquals(filename, ContentDisposition.parse(header).getFilename());
    }

    @Test
    void streamsAnEmptyFile() throws Exception {
        Path file = Files.createFile(temp.resolve("empty.bin"));
        var response = new MockHttpServletResponse();
        FileDownloadResponse.write(file, "empty.bin", response);
        assertEquals(0, response.getContentLengthLong());
        assertArrayEquals(new byte[0], response.getContentAsByteArray());
    }

    @Test
    void propagatesFileFailuresToTheOwningController() {
        assertThrows(IOException.class, () -> FileDownloadResponse.write(temp.resolve("missing.bin"),
                "missing.bin", new MockHttpServletResponse()));
    }
}
