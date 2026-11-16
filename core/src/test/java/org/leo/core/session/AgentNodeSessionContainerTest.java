package org.leo.core.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class AgentNodeSessionContainerTest {

    @AfterEach
    void cleanUp() {
        AgentNodeSessionContainer.clearAllSessions();
    }

    @Test
    void exposesAnImmutableSnapshotAndReportsMissingRemoval() {
        AgentNodeSession session = new AgentNodeSession();
        AgentNodeSessionContainer.addSession("session-1", session);

        var snapshot = AgentNodeSessionContainer.getAllSessions();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put("session-2", new AgentNodeSession()));
        assertTrue(AgentNodeSessionContainer.removeSession("session-1"));
        assertFalse(AgentNodeSessionContainer.removeSession("session-1"));
    }

    @Test
    void clearClosesEverySession() {
        TrackingSession first = new TrackingSession();
        TrackingSession second = new TrackingSession();
        AgentNodeSessionContainer.addSession("first", first);
        AgentNodeSessionContainer.addSession("second", second);

        var destroyed = new ArrayList<String>();
        Consumer<String> listener = destroyed::add;
        AgentNodeSessionContainer.registerDestroyListener(listener);
        try {
            AgentNodeSessionContainer.clearAllSessions();
            assertTrue(first.closed);
            assertTrue(second.closed);
            assertEquals(Set.of("first", "second"), Set.copyOf(destroyed));
            assertEquals(2, destroyed.size());
        } finally {
            AgentNodeSessionContainer.unregisterDestroyListener(listener);
        }
    }

    @Test
    void capacityEvictionKeepsSessionsReplacedAfterItsSnapshot() {
        TrackingSession oldest = spy(new TrackingSession());
        TrackingSession original = spy(new TrackingSession());
        TrackingSession replacement = new TrackingSession();
        TrackingSession incoming = new TrackingSession();
        when(oldest.getLastActiveTime()).thenReturn(0L);
        when(original.getLastActiveTime()).thenReturn(1L);
        AgentNodeSessionContainer.addSession("oldest", oldest);
        AgentNodeSessionContainer.addSession("replaced", original);
        for (int i = 2; i < 500; i++) {
            AgentNodeSessionContainer.addSession("session-" + i, new AgentNodeSession());
        }

        Consumer<String> listener = sessionId -> {
            if ("oldest".equals(sessionId)) {
                AgentNodeSessionContainer.removeSession("replaced");
                AgentNodeSessionContainer.addSession("replaced", replacement);
            }
        };
        AgentNodeSessionContainer.registerDestroyListener(listener);
        try {
            AgentNodeSessionContainer.addSession("incoming", incoming);
            assertTrue(oldest.closed);
            assertTrue(original.closed);
            assertSame(replacement, AgentNodeSessionContainer.getSession("replaced"));
            assertFalse(replacement.closed);
            assertSame(incoming, AgentNodeSessionContainer.getSession("incoming"));
            assertEquals(451, AgentNodeSessionContainer.getAllSessions().size());
        } finally {
            AgentNodeSessionContainer.unregisterDestroyListener(listener);
        }
    }

    private static final class TrackingSession extends AgentNodeSession {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }
}
