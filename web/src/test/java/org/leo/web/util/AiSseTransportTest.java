package org.leo.web.util;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.session.AiThread;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSseTransportTest {

    @Test
    void recordsAndSendsEventsThroughOneTransportSession() throws Exception {
        Fixture fixture = fixture();

        fixture.session.emit("delta", "hello");
        fixture.session.emitStatus("completed");

        assertEquals(2, fixture.persistedEvents.size());
        verify(fixture.emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void safelyRecordsEventWhenClientHasDisconnected() throws Exception {
        Fixture fixture = fixture();
        doThrow(new IOException("disconnected"))
                .when(fixture.emitter).send(any(SseEmitter.SseEventBuilder.class));

        fixture.session.emitSafely("warn", "still recorded");

        assertEquals(1, fixture.persistedEvents.size());
    }

    @Test
    void stopsPumpAndSynchronouslyFlushesRemainingQueue() throws Exception {
        Fixture fixture = fixture();
        AiSseEvent queued = fixture.runtime.offerSseEvent("node", Map.of("kind", "plan"));

        fixture.session.stopAndFlush();

        verify(fixture.eventPump).stop(fixture.handle);
        assertTrue(fixture.runtime.getAiSseEventQueue().isEmpty());
        assertEquals(List.of(queued), fixture.eventLog);
        verify(fixture.emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void extractsSubagentAssociation() {
        assertEquals("child-1", AiSseTransport.extractSubagentInvocationId(
                Map.of("subagentInvocationId", "child-1")));
    }

    private Fixture fixture() {
        AiSseEventPump eventPump = mock(AiSseEventPump.class);
        SseEmitter emitter = mock(SseEmitter.class);
        AiThread runtime = new AiThread("thread-1", "test");
        List<AiSseEvent> persistedEvents = new ArrayList<>();
        runtime.configureEventJournal(0L, persistedEvents::add);
        List<AiSseEvent> eventLog = new ArrayList<>();
        AiSseEventPump.Handle handle = new AiSseEventPump.Handle(
                new AtomicBoolean(false), mock(Future.class));
        when(eventPump.start(
                anyString(), same(runtime.getAiSseEventQueue()), same(eventLog),
                any(), any(), any())).thenReturn(handle);

        AiSseTransport transport =
                new AiSseTransport(eventPump, new AiSseEmitterWriter());
        AiSseTransport.Session session =
                transport.open("test", runtime, emitter, eventLog);
        return new Fixture(
                eventPump, emitter, runtime, eventLog,
                persistedEvents, handle, session);
    }

    private record Fixture(AiSseEventPump eventPump,
                           SseEmitter emitter,
                           AiThread runtime,
                           List<AiSseEvent> eventLog,
                           List<AiSseEvent> persistedEvents,
                           AiSseEventPump.Handle handle,
                           AiSseTransport.Session session) {
    }
}
