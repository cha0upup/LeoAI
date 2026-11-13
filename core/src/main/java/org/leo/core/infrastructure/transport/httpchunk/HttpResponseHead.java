package org.leo.core.infrastructure.transport.httpchunk;

import org.leo.core.net.TransportException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Minimal bounded HTTP/1.x response-head parser. */
final class HttpResponseHead {
    private static final int MAX_LINE_BYTES = 8192;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_HEADER_LINES = 100;

    final int statusCode;
    final Map<String, String> headers;

    private HttpResponseHead(int statusCode, Map<String, String> headers) {
        this.statusCode = statusCode;
        this.headers = headers;
    }

    static HttpResponseHead readFinal(InputStream input) throws IOException {
        for (int informational = 0; informational < 5; informational++) {
            HttpResponseHead head = readOne(input);
            if (head.statusCode < 100 || head.statusCode >= 200) {
                return head;
            }
        }
        throw invalid("Too many informational HTTP responses");
    }

    private static HttpResponseHead readOne(InputStream input) throws IOException {
        Counter counter = new Counter();
        String statusLine = readLine(input, counter);
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw invalid("Invalid HTTP status line: " + statusLine);
        }
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw invalid("Invalid HTTP status code", e);
        }
        if (status < 100 || status > 599) {
            throw invalid("HTTP status code is out of range: " + status);
        }

        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (int lineIndex = 0; lineIndex < MAX_HEADER_LINES; lineIndex++) {
            String line = readLine(input, counter);
            if (line.isEmpty()) {
                return new HttpResponseHead(status, headers);
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw invalid("Invalid HTTP response header");
            }
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            String existing = headers.get(name);
            headers.put(name, existing == null ? value : existing + ", " + value);
        }
        throw invalid("HTTP response has too many header lines");
    }

    String header(String name) {
        return name == null ? null : headers.get(name.toLowerCase(Locale.ROOT));
    }

    boolean hasToken(String headerName, String expectedToken) {
        String value = header(headerName);
        if (value == null) return false;
        String[] tokens = value.split(",");
        for (String token : tokens) {
            if (expectedToken.equalsIgnoreCase(token.trim())) return true;
        }
        return false;
    }

    private static String readLine(InputStream input, Counter counter) throws IOException {
        byte[] bytes = new byte[MAX_LINE_BYTES];
        int length = 0;
        boolean carriageReturn = false;
        while (true) {
            int value = input.read();
            if (value < 0) throw new EOFException("HTTP response head ended early");
            counter.total++;
            if (counter.total > MAX_HEADER_BYTES) {
                throw invalid("HTTP response head exceeds limit");
            }
            if (carriageReturn) {
                if (value != '\n') throw invalid("HTTP response uses invalid line ending");
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            if (value == '\r') {
                carriageReturn = true;
            } else {
                if (length >= MAX_LINE_BYTES) throw invalid("HTTP response line exceeds limit");
                bytes[length++] = (byte) value;
            }
        }
    }

    private static TransportException invalid(String message) {
        return new TransportException(TransportException.Reason.FRAME_INVALID, message);
    }

    private static TransportException invalid(String message, Throwable cause) {
        return new TransportException(TransportException.Reason.FRAME_INVALID, message, cause);
    }

    private static final class Counter {
        private int total;
    }
}
