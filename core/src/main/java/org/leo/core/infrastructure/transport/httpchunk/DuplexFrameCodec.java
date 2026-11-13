package org.leo.core.infrastructure.transport.httpchunk;

import org.leo.core.net.TransportException;
import org.leo.core.net.TransportLimits;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Physical request correlation and control framing inside the HTTP body stream. */
final class DuplexFrameCodec {
    static final int TYPE_DATA = 1;
    static final int TYPE_PING = 2;
    static final int TYPE_PONG = 3;
    static final int TYPE_CLOSE = 4;

    private DuplexFrameCodec() {
    }

    static void write(DataOutputStream output, int type, long transportId, byte[] payload)
            throws IOException {
        byte[] body = payload == null ? new byte[0] : payload;
        TransportLimits.requireMessageSize(body);
        validateTypeAndLength(type, body.length);
        output.writeByte(type);
        output.writeLong(transportId);
        output.writeInt(body.length);
        output.write(body);
        output.flush();
    }

    static Frame read(DataInputStream input) throws IOException {
        int type = input.readUnsignedByte();
        long transportId = input.readLong();
        int length = input.readInt();
        if (length < 0) {
            throw invalid("Negative duplex frame length: " + length);
        }
        if (length > TransportLimits.MAX_MESSAGE_BYTES) {
            throw new TransportException(TransportException.Reason.MESSAGE_TOO_LARGE,
                    "Duplex frame exceeds limit: " + length);
        }
        validateTypeAndLength(type, length);
        byte[] payload = new byte[length];
        input.readFully(payload);
        return new Frame(type, transportId, payload);
    }

    private static void validateTypeAndLength(int type, int length) throws TransportException {
        if (type < TYPE_DATA || type > TYPE_CLOSE) {
            throw invalid("Unsupported duplex frame type: " + type);
        }
        if (type != TYPE_DATA && length != 0) {
            throw invalid("Control frame payload must be empty");
        }
    }

    private static TransportException invalid(String message) {
        return new TransportException(TransportException.Reason.FRAME_INVALID, message);
    }

    static final class Frame {
        final int type;
        final long transportId;
        final byte[] payload;

        Frame(int type, long transportId, byte[] payload) {
            this.type = type;
            this.transportId = transportId;
            this.payload = payload;
        }
    }
}
