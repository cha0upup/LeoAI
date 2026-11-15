package org.leo.ai.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.concurrent.AiBackgroundExecutor;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.BasicInfoCapable;
import org.leo.core.agent.capability.CommandCapable;
import org.leo.core.agent.capability.NetworkInfoCapable;
import org.leo.core.runtime.CapabilitySet;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionWarmupServiceTest {

    private static final String SESSION_ID = "warmup-service-test";

    private final AiBackgroundExecutor executor = mock(AiBackgroundExecutor.class);
    private final SessionWarmupService service = new SessionWarmupService(executor);
    private final List<Runnable> tasks = new ArrayList<>();

    @BeforeEach
    void captureBackgroundWork() {
        when(executor.submitWarmup(any())).thenAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            return null;
        });
        service.registerSessionCleanup();
    }

    @AfterEach
    void releaseSessionAndListener() {
        service.unregisterSessionCleanup();
        AgentNodeSessionContainer.removeSession(SESSION_ID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "missing"})
    void ignoresMissingSessionsBeforeSubmittingWork(String sessionId) {
        service.warmupAsync(sessionId);

        verifyNoInteractions(executor);
    }

    @ParameterizedTest
    @CsvSource({"Windows,set,windows", "Linux,env,unix", "Mac OS X,env,unix"})
    void warmsEachSessionOnceAndUsesItsPlatform(String os, String command, String platform) throws Exception {
        Fixture fixture = session(os);

        service.warmupAsync(SESSION_ID);
        service.warmupAsync(SESSION_ID);
        assertEquals(1, tasks.size());
        tasks.get(0).run();
        service.warmupAsync(SESSION_ID);

        assertEquals(1, tasks.size());
        verify(fixture.basic).getBasicInfo();
        verify(fixture.commands).execSimpleCommand(command);
        verify(fixture.network).collectNetworkInfo();
        assertEquals(Map.of("os.name", os), fixture.session.getAiContextValue("basic-info"));
        assertEquals(platform, fixture.session.getAiContextValue("os-platform"));
        assertEquals(Map.of("data", "cached environment"), fixture.session.getAiContextValue("env-vars"));
        assertEquals(Map.of("interfaces", List.of()), fixture.session.getAiContextValue("network-info:all"));
    }

    @Test
    void cachedResultsAvoidRepeatedRemoteCallsAndRestoreMissingPlatform() throws Exception {
        Fixture fixture = session("Windows");
        fixture.session.putAiContextValue("basic-info", Map.of("os.name", "Windows"));
        fixture.session.putAiContextValue("env-vars", Map.of("data", "cached"));
        fixture.session.putAiContextValue("network-info:all", Map.of("interfaces", List.of()));

        service.warmupAsync(SESSION_ID);
        tasks.get(0).run();

        assertEquals("windows", fixture.session.getAiContextValue("os-platform"));
        verify(fixture.basic, never()).getBasicInfo();
        verify(fixture.commands, never()).execSimpleCommand(anyString());
        verify(fixture.network, never()).collectNetworkInfo();
    }

    @Test
    void failedWarmupCanBeRetried() throws Exception {
        Fixture fixture = session("Linux");
        when(fixture.basic.getBasicInfo()).thenThrow(new IOException("not ready"))
                .thenReturn(Map.of("os.name", "Linux"));

        service.warmupAsync(SESSION_ID);
        tasks.get(0).run();
        service.warmupAsync(SESSION_ID);
        tasks.get(1).run();

        verify(fixture.basic, times(2)).getBasicInfo();
        assertEquals("unix", fixture.session.getAiContextValue("os-platform"));
    }

    @Test
    void rejectedSubmissionReleasesTheWarmupMarker() throws Exception {
        session("Linux");
        doThrow(new RejectedExecutionException("busy")).doAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            return null;
        }).when(executor).submitWarmup(any());

        service.warmupAsync(SESSION_ID);
        service.warmupAsync(SESSION_ID);

        assertEquals(1, tasks.size());
        tasks.get(0).run();
    }

    @Test
    void evictedQueuedWarmupDoesNotRunOrRemoveANewerAttempt() throws Exception {
        Fixture fixture = session("Linux");
        service.warmupAsync(SESSION_ID);
        service.evict(SESSION_ID);
        service.warmupAsync(SESSION_ID);

        tasks.get(0).run();
        verify(fixture.basic, never()).getBasicInfo();
        tasks.get(1).run();
        service.warmupAsync(SESSION_ID);

        assertEquals(2, tasks.size());
        verify(fixture.basic).getBasicInfo();
    }

    @Test
    void destroyedSessionDoesNotRunQueuedWarmup() throws Exception {
        Fixture fixture = session("Linux");
        service.warmupAsync(SESSION_ID);
        AgentNodeSessionContainer.removeSession(SESSION_ID);

        tasks.get(0).run();

        verify(fixture.basic, never()).getBasicInfo();
        verify(fixture.commands, never()).execSimpleCommand(anyString());
        verify(fixture.network, never()).collectNetworkInfo();
    }

    @Test
    void lateFailureCannotRemoveTheReplacementSessionsSuccessfulMarker() throws Exception {
        Fixture old = session("Windows");
        when(old.basic.getBasicInfo()).thenAnswer(invocation -> {
            AgentNodeSessionContainer.removeSession(SESSION_ID);
            session("Linux");
            service.warmupAsync(SESSION_ID);
            tasks.get(1).run();
            throw new IOException("old request failed");
        });

        service.warmupAsync(SESSION_ID);
        tasks.get(0).run();
        service.warmupAsync(SESSION_ID);

        assertEquals(2, tasks.size());
        assertEquals("unix", AgentNodeSessionContainer.getSession(SESSION_ID).getAiContextValue("os-platform"));
    }

    @Test
    void lateResultCannotPopulateAReplacementSessionsCache() throws Exception {
        Fixture old = session("Windows");
        AtomicReference<Fixture> replacement = new AtomicReference<>();
        when(old.basic.getBasicInfo()).thenAnswer(invocation -> {
            AgentNodeSessionContainer.removeSession(SESSION_ID);
            replacement.set(session("Linux"));
            return Map.of("os.name", "Windows");
        });

        service.warmupAsync(SESSION_ID);
        tasks.get(0).run();

        AgentNodeSession current = replacement.get().session;
        assertNull(current.getAiContextValue("basic-info"));
        assertNull(current.getAiContextValue("os-platform"));
        assertNull(current.getAiContextValue("env-vars"));
        assertNull(current.getAiContextValue("network-info:all"));
        service.warmupAsync(SESSION_ID);
        tasks.get(1).run();
        assertEquals("unix", current.getAiContextValue("os-platform"));
    }

    private Fixture session(String os) throws Exception {
        AbstractAgentNode node = mock(AbstractAgentNode.class, withSettings().extraInterfaces(
                BasicInfoCapable.class, CommandCapable.class, NetworkInfoCapable.class));
        when(node.getCapabilitySet()).thenReturn(CapabilitySet.empty());
        BasicInfoCapable basic = (BasicInfoCapable) node;
        CommandCapable commands = (CommandCapable) node;
        NetworkInfoCapable network = (NetworkInfoCapable) node;
        when(basic.getBasicInfo()).thenReturn(Map.of("os.name", os));
        when(commands.execSimpleCommand(anyString()))
                .thenAnswer(invocation -> new HashMap<>(Map.of("data", "cached environment")));
        when(network.collectNetworkInfo()).thenReturn(Map.of("interfaces", List.of()));
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId(SESSION_ID);
        session.setAgentNode(node);
        AgentNodeSessionContainer.addSession(SESSION_ID, session);
        return new Fixture(session, basic, commands, network);
    }

    private record Fixture(AgentNodeSession session, BasicInfoCapable basic,
                           CommandCapable commands, NetworkInfoCapable network) {
    }
}
