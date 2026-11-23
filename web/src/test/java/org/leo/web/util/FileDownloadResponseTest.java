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
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

class FileDownloadResponseTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"report.txt", "中文 报告.txt", "quote\"name.txt", "line\r\nname.txt"})
    void downloadsExactBytesWithAnEncodedName(String filename) throws Exception {
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
        for (String contentType : new String[]{"application/octet-stream", "application/json", "application/zip"}) {
            var download = FileDownloadResponse.bytes(content, filename, contentType);
            assertEquals(200, download.getStatusCode().value());
            assertEquals(contentType, download.getHeaders().getFirst("Content-Type"));
            assertEquals(header, download.getHeaders().getFirst("Content-Disposition"));
            assertSame(content, download.getBody());
            assertEquals(2, download.getHeaders().size());
        }
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
    void keepsLengthAndBodyConsistentWhenTheFileIsReplaced() throws Exception {
        byte[] content = {1, 2, 3};
        Path file = Files.write(temp.resolve("fixture.bin"), content);
        Path replacement = Files.write(temp.resolve("replacement.bin"), new byte[]{4, 5, 6, 7, 8});
        var response = new MockHttpServletResponse() {
            @Override
            public void setContentLengthLong(long contentLength) {
                super.setContentLengthLong(contentLength);
                assertDoesNotThrow(() -> Files.move(replacement, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
            }
        };

        FileDownloadResponse.write(file, "fixture.bin", response);

        assertEquals(content.length, response.getContentLengthLong());
        assertArrayEquals(content, response.getContentAsByteArray());
        assertArrayEquals(new byte[]{4, 5, 6, 7, 8}, Files.readAllBytes(file));
    }

    @Test
    void propagatesFileFailuresToTheOwningController() {
        var response = new MockHttpServletResponse();
        response.setStatus(404);
        assertThrows(IOException.class, () -> FileDownloadResponse.write(temp.resolve("missing.bin"),
                "missing.bin", response));
        assertEquals(404, response.getStatus());
        assertNull(response.getHeader("Content-Disposition"));
    }
}
