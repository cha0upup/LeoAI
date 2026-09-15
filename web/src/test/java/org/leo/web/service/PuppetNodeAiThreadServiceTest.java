package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.service.SessionWarmupService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.repository.session.PuppetAiCheckpointRepository;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PuppetNodeAiThreadServiceTest {

    @Test
    void createsAndPersistsFirstThreadOnDemand() {
        Fixture fixture = fixture();
        PuppetNodeSession session = cacheSession();

        Map<String, Object> info = fixture.service.createThread(session, null, null);

        AiThread thread = session.getAiThread((String) info.get("threadId"));
        assertNotNull(thread);
        assertEquals(1, session.listAiThreads().size());
        assertSame(thread, session.getActiveThread());
        assertEquals("对话 1", thread.getTitle());
        verify(fixture.conversationStore).createPuppetThread(
                eq("user-1"), eq("puppet-1"), eq("session-1"), same(thread), isNull());
        verify(fixture.conversationStore).attachEventJournal(thread.getThreadId(), thread);
        verify(fixture.sessionWarmupService).warmupAsync("session-1");
    }

    @Test
    void persistsTransientInMemoryThreadBeforeFirstUse() {
        Fixture fixture = fixture();
        PuppetNodeSession session = cacheSession();
        AiThread initialThread = session.createAiThread("initial-thread", "对话 1");
        when(fixture.conversationStore.findThread(initialThread.getThreadId())).thenReturn(null);

        PuppetNodeAiThreadService.ThreadResolution resolution =
                fixture.service.ensureThreadReady(session, initialThread.getThreadId(), null);

        assertSame(initialThread, resolution.thread());
        assertNull(resolution.errorMessage());
        verify(fixture.conversationStore).createPuppetThread(
                eq("user-1"), eq("puppet-1"), eq("session-1"), same(initialThread), isNull());
        verify(fixture.sessionWarmupService).warmupAsync("session-1");
    }

    @Test
    void doesNotRecreatePersistedThread() {
        Fixture fixture = fixture();
        PuppetNodeSession session = cacheSession();
        AiThread thread = session.createAiThread("persisted-thread", "已有对话");
        AiThreadRecord record = new AiThreadRecord();
        record.setThreadId(thread.getThreadId());
        when(fixture.conversationStore.findThread(thread.getThreadId())).thenReturn(record);

        PuppetNodeAiThreadService.ThreadResolution resolution =
                fixture.service.ensureThreadReady(session, thread.getThreadId(), null);

        assertSame(thread, resolution.thread());
        assertNull(resolution.errorMessage());
        verify(fixture.conversationStore, never()).createPuppetThread(
                eq("user-1"), eq("puppet-1"), eq("session-1"), same(thread), isNull());
    }

    private static PuppetNodeSession cacheSession() {
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId("session-1");
        session.setCreateByUser("user-1");
        session.setCacheMode(true);
        session.setPuppetId("puppet-1");
        return session;
    }

    private static Fixture fixture() {
        AiConversationStoreService conversationStore = mock(AiConversationStoreService.class);
        SessionWarmupService sessionWarmupService = mock(SessionWarmupService.class);
        PuppetNodeAiThreadService service = new PuppetNodeAiThreadService(
                mock(org.leo.ai.channel.AiModelConfigService.class),
                mock(AiModelChannelResolver.class),
                conversationStore,
                sessionWarmupService,
                mock(PuppetNodeAiAgentRegistry.class),
                mock(AiTurnProtocolService.class),
                mock(PuppetAiCheckpointRepository.class));
        return new Fixture(service, conversationStore, sessionWarmupService);
    }

    private record Fixture(PuppetNodeAiThreadService service,
                           AiConversationStoreService conversationStore,
                           SessionWarmupService sessionWarmupService) {
    }
}
