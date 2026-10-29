package org.leo.web.util;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Streams a file already resolved and checked by its owning controller. */
public final class FileDownloadResponse {
    private FileDownloadResponse() {}

    public static void write(Path file, String filename, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, DownloadHeaders.attachmentValue(filename));
        response.setContentLengthLong(Files.size(file));
        try (var input = Files.newInputStream(file); var output = response.getOutputStream()) {
            input.transferTo(output);
            output.flush();
        }
    }
}
