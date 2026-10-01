package org.leo.core.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        AgentNodeSessionContainer.clearAllSessions();

        assertTrue(first.closed);
        assertTrue(second.closed);
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
