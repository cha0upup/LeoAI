package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.ai.service.ReconSummaryDigestService;
import org.leo.ai.service.ReconSummaryOrganizeService;
import org.leo.core.repository.session.AgentReconRepository;
import org.leo.core.session.AgentNodeSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconSummaryServiceTest {
    private final AgentReconRepository repository = mock(AgentReconRepository.class);
    private final ReconSummaryOrganizeService organizer = mock(ReconSummaryOrganizeService.class);
    private final ReconSummaryDigestService digest = mock(ReconSummaryDigestService.class);
    private final ReconSummaryService service = new ReconSummaryService(organizer, digest, repository);

    @Test
    void diskFailureNeverPublishesAnUnsavedSetClearOrOrganizedSummary() {
        AgentNodeSession session = new AgentNodeSession();
        session.setReconSummary("original");
        when(organizer.organize("original")).thenReturn("organized");
        when(repository.save(eq("session"), nullable(String.class)))
                .thenThrow(new IllegalStateException("fixture persistence failure"));
        assertThrows(IllegalStateException.class, () -> service.set("session", session, "replacement"));
        assertEquals("original", session.getReconSummary());
        assertThrows(IllegalStateException.class, () -> service.clear("session", session));
        assertEquals("original", session.getReconSummary());
        assertThrows(IllegalStateException.class, () -> service.organize("session", session));
        assertEquals("original", session.getReconSummary());
        verifyNoInteractions(digest);
    }

    @Test
    void appendFailureDoesNotFallBackToUnpersistedMemoryOrRewriteStorage() {
        AgentNodeSession session = new AgentNodeSession();
        session.setReconSummary("original");
        when(repository.append("session", "addition")).thenThrow(new IllegalStateException("fixture failure"));
        assertThrows(IllegalStateException.class, () -> service.append("session", session, "addition"));
        assertEquals("original", session.getReconSummary());
        verify(repository, never()).save(anyString(), nullable(String.class));
        verifyNoInteractions(digest);
    }

    @Test
    void failedReadPreservesMemoryButAnActuallyMissingFileClearsStaleMemory() {
        AgentNodeSession session = new AgentNodeSession();
        session.setReconSummary("original");
        when(repository.load("session")).thenThrow(new IllegalStateException("fixture read failure"));
        assertThrows(IllegalStateException.class, () -> service.load("session", session));
        assertEquals("original", session.getReconSummary());
        doReturn(null).when(repository).load("session");
        service.load("session", session);
        assertNull(session.getReconSummary());
        verify(repository, never()).save(anyString(), nullable(String.class));
    }
}
