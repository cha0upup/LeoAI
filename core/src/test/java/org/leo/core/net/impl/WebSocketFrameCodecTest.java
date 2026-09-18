package org.leo.core.net.impl;

import org.junit.jupiter.api.Test;
import org.leo.core.net.TransportException;
import org.leo.core.net.TransportLimits;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebSocketFrameCodecTest {

    @Test
    void roundTripAcrossMultipleFragments() throws Exception {
        byte[] message = new byte[TransportLimits.MAX_FRAGMENT_PAYLOAD_BYTES * 2 + 17];
        for (int i = 0; i < message.length; i++) {
            message[i] = (byte) (i * 31);
        }

        WebSocketFrameCodec.Accumulator accumulator = null;
        byte[] completed = null;
        int count = WebSocketFrameCodec.fragmentCount(message.length);
        assertEquals(3, count);
        for (int i = 0; i < count; i++) {
            ByteBuffer encoded = WebSocketFrameCodec.encode(42L, message, i);
            assertTrue(encoded.remaining() <= TransportLimits.MAX_FRAME_BYTES);
            WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.decode(encoded);
            if (accumulator == null) {
                accumulator = new WebSocketFrameCodec.Accumulator(frame);
            }
            completed = accumulator.accept(frame);
            if (i < count - 1) {
                assertNull(completed);
            }
        }
        assertArrayEquals(message, completed);
    }

    @Test
    void emptyMessageStillUsesOneFrame() throws Exception {
        ByteBuffer encoded = WebSocketFrameCodec.encode(7L, new byte[0], 0);
        assertEquals(TransportLimits.WEBSOCKET_FRAME_HEADER_BYTES, encoded.remaining());
        WebSocketFrameCodec.Frame frame = WebSocketFrameCodec.decode(encoded);
        WebSocketFrameCodec.Accumulator accumulator = new WebSocketFrameCodec.Accumulator(frame);
        assertEquals(0, accumulator.accept(frame).length);
    }

    @Test
    void rejectsOversizedMetadataBeforeAllocatingPayload() {
        ByteBuffer invalid = ByteBuffer.allocate(TransportLimits.WEBSOCKET_FRAME_HEADER_BYTES);
        invalid.put(WebSocketFrameCodec.TYPE_DATA);
        invalid.putLong(1L);
        invalid.putInt(0);
        invalid.putInt(1);
        invalid.putInt(TransportLimits.MAX_MESSAGE_BYTES + 1);
        invalid.flip();

        TransportException error = assertThrows(TransportException.class,
                () -> WebSocketFrameCodec.decode(invalid));
        assertEquals(TransportException.Reason.MESSAGE_TOO_LARGE, error.getReason());
    }

    @Test
    void rejectsOutOfOrderFragments() throws Exception {
        byte[] message = new byte[TransportLimits.MAX_FRAGMENT_PAYLOAD_BYTES + 1];
        WebSocketFrameCodec.Frame second = WebSocketFrameCodec.decode(
                WebSocketFrameCodec.encode(9L, message, 1));
        TransportException error = assertThrows(TransportException.class,
                () -> new WebSocketFrameCodec.Accumulator(second));
        assertEquals(TransportException.Reason.FRAME_INVALID, error.getReason());
    }
}
