package org.leo.ai.tools.agentnode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.AgentNodeSessionUtils;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.CommandCapable;
import org.leo.core.runtime.CapabilitySet;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.audit.AgentAuditService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class CommandToolsInteractiveTerminalTest {

    private static final String SESSION_ID = "interactive-terminal-session";

    private CommandCapable terminal;
    private CommandTools tools;

    @BeforeEach
    void setUp() {
        AbstractAgentNode node = mock(AbstractAgentNode.class,
                withSettings().extraInterfaces(CommandCapable.class));
        when(node.getCapabilitySet()).thenReturn(CapabilitySet.empty());
        terminal = (CommandCapable) node;

        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId(SESSION_ID);
        session.setAgentNode(node);
        AgentNodeSessionContainer.addSession(SESSION_ID, session);
        AiToolContext.setFromMemoryId(SESSION_ID + ":thread-1");
        tools = new CommandTools(mock(AgentAuditService.class));
    }

    @AfterEach
    void tearDown() {
        AiToolContext.clear();
        AgentNodeSessionContainer.removeSession(SESSION_ID);
    }

    @Test
    void writesRawControlCharactersWithoutAddingANewline() throws Exception {
        when(terminal.execCommand("write", "\u0003", "task-1"))
                .thenReturn(Map.of("pty", true, "backend", "python3-pty"));

        Map<String, Object> result = tools.writeTask("task-1", "\u0003", false);

        verify(terminal).execCommand("write", "\u0003", "task-1");
        assertEquals("written", result.get("status"));
        assertEquals(1, result.get("writtenChars"));
        assertEquals(true, result.get("pty"));
    }

    @Test
    void appendsANewlineForInteractivePromptAnswers() throws Exception {
        when(terminal.execCommand("write", "yes\n", "task-2"))
                .thenReturn(Map.of("alive", true));

        Map<String, Object> result = tools.writeTask("task-2", "yes", true);

        verify(terminal).execCommand("write", "yes\n", "task-2");
        assertEquals(4, result.get("writtenChars"));
        assertEquals(true, result.get("appendNewline"));
    }

    @Test
    void normalizesResizeBoundsAndReportsFixedBackends() throws Exception {
        when(terminal.execCommand("resize", "500,5", "task-3"))
                .thenReturn(Map.of("resizable", false, "backend", "unix-pipe"));

        Map<String, Object> result = tools.resizeTask("task-3", 900, 1);

        verify(terminal).execCommand("resize", "500,5", "task-3");
        assertEquals(500, result.get("cols"));
        assertEquals(5, result.get("rows"));
        assertEquals("fixed", result.get("status"));
    }

    @Test
    void initializesCrossRuntimeTerminalBeforeStartingAsyncCommand() throws Exception {
        when(terminal.execCommand(eq("init"), eq(""), anyString()))
                .thenReturn(Map.of("alive", true));
        when(terminal.execCommand(eq("write"), eq("tail -f app.log\n"), anyString()))
                .thenReturn(Map.of("alive", true));

        Map<String, Object> result = tools.exec("tail -f app.log", 0);

        String taskId = String.valueOf(result.get("taskId"));
        verify(terminal).execCommand("init", "", taskId);
        verify(terminal).execCommand("write", "tail -f app.log\n", taskId);
        verify(terminal, never()).execCommand(eq("read"), anyString(), anyString());
        assertEquals("running", result.get("status"));
    }

    @Test
    void cleansUpTerminalWhenInitialAsyncWriteFails() throws Exception {
        when(terminal.execCommand(eq("init"), eq(""), anyString()))
                .thenReturn(Map.of("alive", true));
        doThrow(new IllegalStateException("write failed"))
                .when(terminal).execCommand(eq("write"), eq("tail -f app.log\n"), anyString());

        assertThrows(IllegalStateException.class, () -> tools.exec("tail -f app.log", 0));

        verify(terminal).execCommand(eq("stop"), eq(""), anyString());
    }

    @Test
    void doesNotCacheArbitrarySynchronousCommands() throws Exception {
        when(terminal.execSimpleCommand("whoami"))
                .thenReturn(Map.of("data", "alice\n".getBytes(), "exitCode", 0));

        tools.exec("whoami", 0);
        tools.exec("whoami", 0);

        verify(terminal, times(2)).execSimpleCommand("whoami");
    }

    @Test
    void retainsTheExplicitEnvironmentCache() throws Exception {
        when(terminal.execSimpleCommand("env"))
                .thenReturn(Map.of("data", "HOME=/tmp\n".getBytes(), "exitCode", 0));

        tools.exec("env", 0);
        tools.exec("env", 0);

        verify(terminal).execSimpleCommand("env");
    }

    @Test
    void normalizesWarmupCommandCacheBeforeReturningItToTheModel() throws Exception {
        AgentNodeSessionUtils.putAiContextValue(SESSION_ID, "env-vars",
                Map.of("data", "HOME=/tmp\n".getBytes(), "exitCode", 0));

        Map<String, Object> result = tools.exec("env", 0);

        assertEquals("HOME=/tmp\n", result.get("output"));
        assertEquals("completed", result.get("status"));
        verify(terminal, never()).execSimpleCommand("env");
    }
}
