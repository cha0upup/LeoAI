package org.leo.core.engine.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Merge ready bytes only; an interactive packet never waits for a fuller batch. */
public final class ProxyIoBatch {
    public static final int BUFFER_BYTES = 64 * 1024;
    public static final int MAX_BYTES = 256 * 1024;
    static final byte[] EMPTY = new byte[0];
    private static final long DRAIN_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

    private ProxyIoBatch() { }

    public static byte[] read(InputStream input, byte[] buffer) throws IOException {
        int length = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES));
        if (length < 0) return null;
        if (length == 0) return EMPTY;
        int available = 0;
        try { available = input.available(); } catch (IOException ignored) { }
        if (available <= 0) return Arrays.copyOf(buffer, length);

        ByteArrayOutputStream batch = new ByteArrayOutputStream(
                length + Math.min(available, MAX_BYTES - length));
        batch.write(buffer, 0, length);
        long deadline = System.nanoTime() + DRAIN_NANOS;
        while (available > 0 && batch.size() < MAX_BYTES && System.nanoTime() < deadline) {
            try {
                int read = input.read(buffer, 0, Math.min(Math.min(buffer.length, available), MAX_BYTES - batch.size()));
                if (read <= 0) break;
                batch.write(buffer, 0, read);
                available = input.available();
            } catch (IOException error) {
                // Deliver consumed bytes; the next blocking read reports the failure.
                break;
            }
        }
        return batch.toByteArray();
    }
}
