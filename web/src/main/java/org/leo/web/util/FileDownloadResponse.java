package org.leo.web.util;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Download responses for content already resolved and checked by its owning controller. */
public final class FileDownloadResponse {
    private FileDownloadResponse() {}

    public static ResponseEntity<byte[]> bytes(byte[] content, String filename, String contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachmentValue(filename))
                .body(content);
    }

    public static void write(Path file, String filename, HttpServletResponse response) throws IOException {
        try (var channel = Files.newByteChannel(file)) {
            long length = channel.size();
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachmentValue(filename));
            response.setContentLengthLong(length);
            try (var output = response.getOutputStream()) {
                Channels.newInputStream(channel).transferTo(output);
                output.flush();
            }
        }
    }

    private static String attachmentValue(String filename) {
        return ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build().toString();
    }
}
