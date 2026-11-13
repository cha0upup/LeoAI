package org.leo.core.infrastructure.transport.httpchunk;

import org.leo.core.net.TransportException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Decodes an HTTP/1.1 chunked message body into one continuous byte stream. */
final class HttpChunkedBodyInputStream extends InputStream {
    private static final int MAX_CHUNK_LINE_BYTES = 8192;
    private static final int MAX_TRAILER_BYTES = 64 * 1024;
    private static final int MAX_INBOUND_CHUNK_BYTES = 1024 * 1024;

    private final InputStream input;
    private long chunkRemaining;
    private boolean firstChunk = true;
    private boolean finished;

    HttpChunkedBodyInputStream(InputStream input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        this.input = input;
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int read = read(single, 0, 1);
        return read < 0 ? -1 : single[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || length > bytes.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) return 0;
        if (!ensureChunk()) return -1;

        int wanted = (int) Math.min((long) length, chunkRemaining);
        int read = input.read(bytes, offset, wanted);
        if (read < 0) {
            throw new EOFException("HTTP chunk data ended early");
        }
        chunkRemaining -= read;
        return read;
    }

    private boolean ensureChunk() throws IOException {
        if (finished) return false;
        if (chunkRemaining > 0) return true;

        if (!firstChunk) {
            requireCrlf();
        }
        firstChunk = false;

        String line = readAsciiLine(MAX_CHUNK_LINE_BYTES);
        int extension = line.indexOf(';');
        String sizeToken = (extension >= 0 ? line.substring(0, extension) : line).trim();
        if (sizeToken.isEmpty()) {
            throw invalid("Missing HTTP chunk size");
        }
        for (int i = 0; i < sizeToken.length(); i++) {
            if (Character.digit(sizeToken.charAt(i), 16) < 0) {
                throw invalid("Invalid HTTP chunk size: " + sizeToken);
            }
        }

        long size;
        try {
            size = Long.parseLong(sizeToken, 16);
        } catch (NumberFormatException e) {
            throw invalid("Invalid HTTP chunk size: " + sizeToken, e);
        }
        if (size < 0 || size > MAX_INBOUND_CHUNK_BYTES) {
            throw invalid("HTTP chunk exceeds limit: " + size);
        }
        if (size == 0) {
            readTrailers();
            finished = true;
            return false;
        }
        chunkRemaining = size;
        return true;
    }

    private void readTrailers() throws IOException {
        int total = 0;
        while (true) {
            String trailer = readAsciiLine(MAX_CHUNK_LINE_BYTES);
            total += trailer.length() + 2;
            if (total > MAX_TRAILER_BYTES) {
                throw invalid("HTTP chunk trailers exceed limit");
            }
            if (trailer.isEmpty()) return;
            if (trailer.indexOf(':') <= 0) {
                throw invalid("Invalid HTTP trailer line");
            }
        }
    }

    private void requireCrlf() throws IOException {
        int first = input.read();
        int second = input.read();
        if (first != '\r' || second != '\n') {
            throw invalid("HTTP chunk is missing trailing CRLF");
        }
    }

    private String readAsciiLine(int limit) throws IOException {
        byte[] line = new byte[limit];
        int length = 0;
        boolean sawCarriageReturn = false;
        while (true) {
            int value = input.read();
            if (value < 0) throw new EOFException("HTTP chunk line ended early");
            if (sawCarriageReturn) {
                if (value != '\n') throw invalid("HTTP chunk line uses invalid line ending");
                return new String(line, 0, length, StandardCharsets.US_ASCII);
            }
            if (value == '\r') {
                sawCarriageReturn = true;
            } else {
                if (length >= limit) throw invalid("HTTP chunk line exceeds limit");
                line[length++] = (byte) value;
            }
        }
    }

    private static TransportException invalid(String message) {
        return new TransportException(TransportException.Reason.FRAME_INVALID, message);
    }

    private static TransportException invalid(String message, Throwable cause) {
        return new TransportException(TransportException.Reason.FRAME_INVALID, message, cause);
    }
}
