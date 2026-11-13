package org.leo.core.infrastructure.transport.httpchunk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Encodes a continuous byte stream as an HTTP/1.1 chunked message body. */
final class HttpChunkedBodyOutputStream extends OutputStream {
    private static final int MAX_OUTBOUND_CHUNK_BYTES = 64 * 1024;
    private static final byte[] CRLF = new byte[]{'\r', '\n'};

    private final OutputStream output;
    private boolean finished;

    HttpChunkedBodyOutputStream(OutputStream output) {
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        this.output = output;
    }

    @Override
    public synchronized void write(int value) throws IOException {
        byte[] single = new byte[]{(byte) value};
        write(single, 0, 1);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || length > bytes.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        ensureOpen();
        int cursor = offset;
        int remaining = length;
        while (remaining > 0) {
            int chunkLength = Math.min(remaining, MAX_OUTBOUND_CHUNK_BYTES);
            writeChunk(bytes, cursor, chunkLength);
            cursor += chunkLength;
            remaining -= chunkLength;
        }
    }

    private void writeChunk(byte[] bytes, int offset, int length) throws IOException {
        output.write(Integer.toHexString(length).getBytes(StandardCharsets.US_ASCII));
        output.write(CRLF);
        output.write(bytes, offset, length);
        output.write(CRLF);
    }

    @Override
    public synchronized void flush() throws IOException {
        ensureOpen();
        output.flush();
    }

    synchronized void finish() throws IOException {
        if (finished) return;
        output.write('0');
        output.write(CRLF);
        output.write(CRLF);
        output.flush();
        finished = true;
    }

    @Override
    public synchronized void close() throws IOException {
        finish();
    }

    private void ensureOpen() throws IOException {
        if (finished) {
            throw new IOException("HTTP chunked request body is finished");
        }
    }
}
