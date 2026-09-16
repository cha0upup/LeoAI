package org.leo.web.controller.puppetnode.command;

import org.junit.jupiter.api.Test;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.TerminalCapable;
import org.leo.core.session.PuppetNodeSession;
import org.leo.web.dto.puppetnode.command.CommandExecRequest;
import org.leo.web.dto.puppetnode.command.TerminalBatchReadRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;

import java.util.Map;
import java.util.List;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandControllerTest {
    @Test
    void forwardsWriteOutputInOneCallAndKeepsOutputErrorsSeparateFromInputSuccess() throws Exception {
        TerminalCapable terminal = mock(TerminalCapable.class);
        PuppetNodeSession session = mock(PuppetNodeSession.class);
        Map<String, Object> result = Map.of("code", 200, "written", 4,
                "output", Map.of("code", 500, "msg", "output unavailable"));
        when(terminal.execTerminal("write-line", "pwd\n", "p", null, true)).thenReturn(result);
        try (var util = mockStatic(ControllerUtil.class)) {
            util.when(() -> ControllerUtil.getPuppetNodeSession("host")).thenReturn(session);
            util.when(() -> ControllerUtil.requireCapability(session, TerminalCapable.class)).thenReturn(terminal);
            CommandExecRequest request = new CommandExecRequest("host", "pwd\n", "write-line", "p", null, true);
            assertSame(result, new CommandController().execCommand(request).get("data"));
            verify(terminal).execTerminal("write-line", "pwd\n", "p", null, true);
            verify(terminal, never()).execCommand(any(), any(), any());
        }
        assertThrows(ApiException.class, () -> CommandExecRequest.normalize(
                new CommandExecRequest("host", "", "read", "p", null, true)));
    }

    @Test
    void validatesTheWholeBatchBeforeLookingUpTheNode() {
        try (var util = mockStatic(ControllerUtil.class)) {
            for (List<String> ids : java.util.Arrays.asList(null, List.<String>of(),
                    List.of("p", " p "), List.of("p", "../other"), Collections.nCopies(17, "p"))) {
                assertThrows(ApiException.class, () -> new CommandController().readBatch(
                        new TerminalBatchReadRequest("host", ids)));
            }
            util.verifyNoInteractions();
        }
    }

    @Test
    void authorizesOneSessionAndPreservesIndividualBatchErrors() throws Exception {
        TerminalCapable terminal = mock(TerminalCapable.class);
        PuppetNodeSession session = mock(PuppetNodeSession.class);
        Map<String, Object> results = Map.of("code", 200, "terminals", Map.of(
                "p", Map.of("code", 200, "data", new byte[]{65}),
                "q", Map.of("code", 500, "msg", "busy")));
        when(terminal.readTerminals(List.of("p", "q"))).thenReturn(results).thenReturn(Map.of("code", 200));
        try (var util = mockStatic(ControllerUtil.class); var audit = mockStatic(AuditLogUtil.class)) {
            util.when(() -> ControllerUtil.getPuppetNodeSession("host")).thenReturn(session);
            util.when(() -> ControllerUtil.requireCapability(session, TerminalCapable.class)).thenReturn(terminal);
            TerminalBatchReadRequest request = new TerminalBatchReadRequest(" host ", List.of(" p ", "q"));
            assertSame(results, new CommandController().readBatch(request).get("data"));
            verify(terminal).readTerminals(List.of("p", "q"));
            verify(terminal, never()).execCommand(any(), any(), any());
            verify(session).touchLastActiveTime();
            audit.verifyNoInteractions();
            assertThrows(ApiException.class, () -> new CommandController().readBatch(request));
        }
    }

    @Test
    void validatesAndForwardsLineInputWithoutTrimmingIt() throws Exception {
        assertThrows(ApiException.class, () -> CommandExecRequest.normalize(
                new CommandExecRequest("host", "partial", "write-line", "process")));
        assertThrows(ApiException.class, () -> CommandExecRequest.normalize(
                new CommandExecRequest("host", "中".repeat(400000) + "\n", "write-line", "process")));
        TerminalCapable terminal = mock(TerminalCapable.class);
        PuppetNodeSession session = mock(PuppetNodeSession.class);
        when(terminal.execTerminal("write-line", "  echo 中文\n", "process", null, false)).thenReturn(Map.of("code", 200));
        try (var util = mockStatic(ControllerUtil.class)) {
            util.when(() -> ControllerUtil.getPuppetNodeSession("host")).thenReturn(session);
            util.when(() -> ControllerUtil.requireCapability(session, TerminalCapable.class)).thenReturn(terminal);
            new CommandController().execCommand(new CommandExecRequest("host", "  echo 中文\n", "write-line", "process"));
            verify(terminal).execTerminal("write-line", "  echo 中文\n", "process", null, false);
        }
    }

    @Test
    void validatesInitializationAndReadWaitBeforeAccessingTheNode() {
        CommandExecRequest initialized = CommandExecRequest.normalize(
                new CommandExecRequest("host", null, "init", "p", "pipe", true));
        assertEquals("", initialized.cmd());
        assertEquals("init", initialized.type());
        assertTrue(initialized.includeOutput());
        assertEquals("init", CommandExecRequest.normalize(
                new CommandExecRequest("host", "init", "write", "p")).cmd());
        assertEquals("10000", CommandExecRequest.normalize(
                new CommandExecRequest("host", " 2147483647 ", "read", "p")).cmd());
        try (var util = mockStatic(ControllerUtil.class)) {
            for (String value : new String[]{"read", "-1", "1.2", "+3", "2147483648"}) {
                ApiException error = assertThrows(ApiException.class, () -> new CommandController().execCommand(
                        new CommandExecRequest("host", value, "read", "p")));
                assertEquals(400, error.getCode());
            }
            for (String type : new String[]{"init", "stop"}) {
                assertThrows(ApiException.class, () -> new CommandController().execCommand(
                        new CommandExecRequest("host", "unexpected", type, "p")));
            }
            assertThrows(ApiException.class, () -> new CommandController().execCommand(
                    new CommandExecRequest("host", "init", "write", "p", "pipe")));
            util.verifyNoInteractions();
        }
    }

    @Test
    void validatesModesOnlyOnInitializationAndForwardsTheChoice() throws Exception {
        assertThrows(ApiException.class, () -> CommandExecRequest.normalize(
                new CommandExecRequest("host", "echo x", "write", "process", "pipe")));
        assertThrows(ApiException.class, () -> CommandExecRequest.normalize(
                new CommandExecRequest("host", "", "init", "process", "unknown")));
        TerminalCapable terminal = mock(TerminalCapable.class);
        PuppetNodeSession session = mock(PuppetNodeSession.class);
        when(terminal.execTerminal("init", "", "process", "python-pty", false)).thenReturn(Map.of("code", 200, "pty", true));
        try (var util = mockStatic(ControllerUtil.class)) {
            util.when(() -> ControllerUtil.getPuppetNodeSession("host")).thenReturn(session);
            util.when(() -> ControllerUtil.requireCapability(session, TerminalCapable.class)).thenReturn(terminal);
            new CommandController().execCommand(new CommandExecRequest("host", "", "init", "process", "python-pty"));
            verify(terminal).execTerminal("init", "", "process", "python-pty", false);
            verify(terminal, never()).execCommand(any(), any(), any());
        }
    }

    @Test
    void normalizesRoutingIdentifiersWithoutChangingTerminalInput() {
        String input = "  echo text\r\u0003 ";
        CommandExecRequest request = CommandExecRequest.normalize(
                new CommandExecRequest(" host ", input, " write ", " process "));
        assertEquals(new CommandExecRequest("host", input, "write", "process"), request);
        assertEquals("", CommandExecRequest.normalize(
                new CommandExecRequest("host", null, "read", "process")).cmd());
        assertThrows(ApiException.class, () -> CommandExecRequest.normalize(
                new CommandExecRequest("host", "", "unknown", "process")));
        assertThrows(ApiException.class, () -> CommandExecRequest.normalize(
                new CommandExecRequest("host", "", "init", "../process")));
    }

    @Test
    void componentFailureProducesAnErrorAndNeverASuccessAudit() throws Exception {
        AbstractPuppetNode node = mock(AbstractPuppetNode.class, withSettings().extraInterfaces(TerminalCapable.class));
        TerminalCapable terminal = (TerminalCapable) node;
        PuppetNodeSession session = mock(PuppetNodeSession.class);
        when(terminal.execTerminal("init", "", "process", null, false)).thenReturn(Map.of("code", 500, "msg", "startup failed"));
        try (var util = mockStatic(ControllerUtil.class); var audit = mockStatic(AuditLogUtil.class)) {
            util.when(() -> ControllerUtil.getPuppetNodeSession("host")).thenReturn(session);
            util.when(() -> ControllerUtil.requireCapability(session, TerminalCapable.class)).thenReturn(terminal);
            ApiException error = assertThrows(ApiException.class, () -> new CommandController().execCommand(
                    new CommandExecRequest("host", "", "init", "process")));
            assertEquals(500, error.getCode());
            assertEquals("startup failed", error.getMessage());
            audit.verify(() -> AuditLogUtil.logSuccess(any(), any(), any(), any(), any(), any(), any(), any()), never());
            audit.verify(() -> AuditLogUtil.logFailure(eq(node), eq("COMMAND_INIT"), any(), eq("process"), any(), eq("startup failed"), any()));
        }
    }

    @Test
    void preservesSuccessfulOutputAndRejectsAnEmptyComponentResponse() throws Exception {
        TerminalCapable terminal = mock(TerminalCapable.class);
        PuppetNodeSession session = mock(PuppetNodeSession.class);
        Map<String, Object> output = Map.of("code", 200, "alive", false, "eof", true, "data", new byte[]{65});
        when(terminal.execTerminal("read", "", "process", null, false)).thenReturn(output).thenReturn(null);
        try (var util = mockStatic(ControllerUtil.class)) {
            util.when(() -> ControllerUtil.getPuppetNodeSession("host")).thenReturn(session);
            util.when(() -> ControllerUtil.requireCapability(session, TerminalCapable.class)).thenReturn(terminal);
            CommandExecRequest request = new CommandExecRequest("host", "", "read", "process");
            assertSame(output, new CommandController().execCommand(request).get("data"));
            assertThrows(ApiException.class, () -> new CommandController().execCommand(request));
        }
    }
}
