package org.leo.web.adapter.ai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class AiSseEmitterWriterTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sendsTerminalErrorAndClosesEvenAfterDisconnection(boolean disconnected) throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        var event = org.mockito.ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        if (disconnected) {
            doThrow(new IOException("disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        }

        AiSseEmitterWriter.safeSendError(emitter, "failed");

        var order = inOrder(emitter);
        order.verify(emitter).send(event.capture());
        order.verify(emitter).complete();
        var payload = event.getValue().build().stream().map(item -> item.getData()).toList();
        assertTrue(payload.contains("event:error\ndata:"));
        assertTrue(payload.contains("failed"));
    }
}
