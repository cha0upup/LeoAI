package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.repository.session.PuppetReconRepository;
import org.leo.service.PuppetService;
import org.leo.service.puppetnode.PuppetNodeFactory;
import org.leo.web.dto.puppetnode.PuppetInitResponse;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class PuppetNodeLifecycleServiceTest {

    @TempDir
    Path vfsRoot;

    private MockedStatic<LeoConfig> config;
    private final PuppetNodeFactory nodeFactory = mock(PuppetNodeFactory.class);
    private final PuppetCacheService cacheService = mock(PuppetCacheService.class);
    private final PuppetNodeLifecycleService lifecycleService = new PuppetNodeLifecycleService(
            mock(PuppetService.class), nodeFactory, cacheService,
            mock(PuppetReconRepository.class), new SessionLifecycleManager());
    private final Puppet puppet = new Puppet();
    private final User user = new User();

    @BeforeEach
    void setUp() {
        config = mockStatic(LeoConfig.class);
        config.when(LeoConfig::getVfsPath).thenReturn(vfsRoot.toString());
        puppet.setPuppetId("puppet-1");
        user.setUserId("user-1");
    }

    @AfterEach
    void clearSessions() {
        try {
            PuppetNodeSessionContainer.clearAllSessions();
        } finally {
            config.close();
        }
    }

    @Test
    void liveConnectionsDoNotCreateAiThreads() throws Exception {
        AbstractPuppetNode node = mock(AbstractPuppetNode.class);
        when(nodeFactory.createLiveNode(puppet, user)).thenReturn(node);
        when(node.getPuppet()).thenReturn(puppet);
        when(node.testConnection()).thenReturn(Map.of("code", 200));

        PuppetInitResponse first = lifecycleService.initLiveSession(puppet, user);
        PuppetInitResponse second = lifecycleService.initLiveSession(puppet, user);

        assertNotEquals(first.sessionId(), second.sessionId());
        assertSame(node, assertSessionWithoutAiThreads(first, false).getPuppetNode());
        assertSame(node, assertSessionWithoutAiThreads(second, false).getPuppetNode());
    }

    @Test
    void cacheConnectionsDoNotCreateAiThreads() {
        when(cacheService.requireSelectedHostId("user-1", "puppet-1", null))
                .thenReturn("host-1");

        PuppetInitResponse first = lifecycleService.initCacheSession(puppet, user);
        PuppetInitResponse second = lifecycleService.initCacheSession(puppet, user);

        assertNotEquals(first.sessionId(), second.sessionId());
        assertSessionWithoutAiThreads(first, true);
        assertSessionWithoutAiThreads(second, true);
    }

    private PuppetNodeSession assertSessionWithoutAiThreads(PuppetInitResponse response, boolean cacheMode) {
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(response.sessionId());
        assertNotNull(session);
        assertEquals(cacheMode, response.cacheMode());
        assertEquals("user-1", session.getCreateByUser());
        assertEquals("puppet-1", session.resolvePuppetId());
        assertTrue(session.listAiThreads().isEmpty());
        assertNull(session.getActiveThreadId());
        return session;
    }
}
