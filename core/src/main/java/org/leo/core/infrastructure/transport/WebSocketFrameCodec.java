package org.leo.core.infrastructure.transport;

import org.leo.core.net.TransportException;
import org.leo.core.net.TransportLimits;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Fixed-size application framing used on top of WebSocket binary messages. */
final class WebSocketFrameCodec {
    static final byte TYPE_DATA = 1;

    private WebSocketFrameCodec() {
    }

    static int fragmentCount(int totalLength) {
        return Math.max(1, (totalLength + TransportLimits.MAX_FRAGMENT_PAYLOAD_BYTES - 1)
                / TransportLimits.MAX_FRAGMENT_PAYLOAD_BYTES);
    }

    static ByteBuffer encode(long messageId, byte[] message, int fragmentIndex)
            throws TransportException {
        byte[] body = message == null ? new byte[0] : message;
        TransportLimits.requireMessageSize(body);
        int fragmentCount = fragmentCount(body.length);
        if (fragmentIndex < 0 || fragmentIndex >= fragmentCount) {
            throw invalid("Invalid WebSocket fragment index: " + fragmentIndex);
        }

        int offset = fragmentIndex * TransportLimits.MAX_FRAGMENT_PAYLOAD_BYTES;
        int payloadLength = Math.min(TransportLimits.MAX_FRAGMENT_PAYLOAD_BYTES,
                body.length - offset);
        ByteBuffer frame = ByteBuffer.allocate(
                TransportLimits.WEBSOCKET_FRAME_HEADER_BYTES + payloadLength);
        frame.put(TYPE_DATA);
        frame.putLong(messageId);
        frame.putInt(fragmentIndex);
        frame.putInt(fragmentCount);
        frame.putInt(body.length);
        frame.put(body, offset, payloadLength);
        frame.flip();
        return frame;
    }

    static Frame decode(ByteBuffer source) throws TransportException {
        if (source == null || source.remaining() < TransportLimits.WEBSOCKET_FRAME_HEADER_BYTES) {
            throw invalid("WebSocket frame header is incomplete");
        }
        ByteBuffer frame = source.slice();
        byte type = frame.get();
        long messageId = frame.getLong();
        int fragmentIndex = frame.getInt();
        int fragmentCount = frame.getInt();
        int totalLength = frame.getInt();

        if (type != TYPE_DATA) {
            throw invalid("Unsupported WebSocket frame type: " + type);
        }
        if (totalLength < 0) {
            throw invalid("Negative WebSocket message length: " + totalLength);
        }
        if (totalLength > TransportLimits.MAX_MESSAGE_BYTES) {
            throw new TransportException(TransportException.Reason.MESSAGE_TOO_LARGE,
                    "WebSocket message exceeds limit: " + totalLength);
        }
        int expectedCount = fragmentCount(totalLength);
        if (fragmentCount != expectedCount) {
            throw invalid("Invalid WebSocket fragment count: " + fragmentCount);
        }
        if (fragmentIndex < 0 || fragmentIndex >= fragmentCount) {
            throw invalid("Invalid WebSocket fragment index: " + fragmentIndex);
        }
        int expectedPayloadLength = Math.min(TransportLimits.MAX_FRAGMENT_PAYLOAD_BYTES,
                totalLength - fragmentIndex * TransportLimits.MAX_FRAGMENT_PAYLOAD_BYTES);
        if (frame.remaining() != expectedPayloadLength) {
            throw invalid("Invalid WebSocket fragment payload length: " + frame.remaining());
        }
        byte[] payload = new byte[frame.remaining()];
        frame.get(payload);
        return new Frame(messageId, fragmentIndex, fragmentCount, totalLength, payload);
    }

    private static TransportException invalid(String message) {
        return new TransportException(TransportException.Reason.FRAME_INVALID, message);
    }

    static final class Frame {
        final long messageId;
        final int fragmentIndex;
        final int fragmentCount;
        final int totalLength;
        final byte[] payload;

        Frame(long messageId, int fragmentIndex, int fragmentCount,
              int totalLength, byte[] payload) {
            this.messageId = messageId;
            this.fragmentIndex = fragmentIndex;
            this.fragmentCount = fragmentCount;
            this.totalLength = totalLength;
            this.payload = payload;
        }
    }

    static final class Accumulator {
        private final int fragmentCount;
        private final int totalLength;
        private final ByteArrayOutputStream output;
        private int nextFragmentIndex;

        Accumulator(Frame first) throws TransportException {
            if (first.fragmentIndex != 0) {
                throw invalid("WebSocket message does not start at fragment zero");
            }
            this.fragmentCount = first.fragmentCount;
            this.totalLength = first.totalLength;
            this.output = new ByteArrayOutputStream(first.totalLength);
        }

        synchronized byte[] accept(Frame frame) throws TransportException {
            if (frame.fragmentCount != fragmentCount || frame.totalLength != totalLength) {
                throw invalid("WebSocket fragment metadata changed during message");
            }
            if (frame.fragmentIndex != nextFragmentIndex) {
                throw invalid("Out-of-order WebSocket fragment: expected "
                        + nextFragmentIndex + " but received " + frame.fragmentIndex);
            }
            output.write(frame.payload, 0, frame.payload.length);
            nextFragmentIndex++;
            if (nextFragmentIndex < fragmentCount) {
                return null;
            }
            if (output.size() != totalLength) {
                throw new TransportException(TransportException.Reason.MESSAGE_INCOMPLETE,
                        "WebSocket message is incomplete: " + output.size() + " != " + totalLength);
            }
            return output.toByteArray();
        }
    }
}
