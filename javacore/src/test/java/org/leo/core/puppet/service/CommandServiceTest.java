package org.leo.core.puppet.service;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandServiceTest {
    private final CommandService service = new CommandService(bytes -> new byte[0], List.of(), List.of()) {
        @Override
        public Map<String, Object> invokeComponent(String name, Map<String, Object> params) {
            assertEquals("ExecCommandComponent", name);
            return params;
        }
    };

    @Test
    void requestsOutputOnWritesAndPtyInitializationWithoutAnExtraNodeCall() throws Exception {
        Map<String, Object> line = service.execTerminal("write-line", "pwd\n", "p", null, true);
        assertEquals(4, line.get("op"));
        assertEquals(true, line.get("includeOutput"));
        Map<String, Object> pty = service.execTerminal("init", "", "p", "python-pty", true);
        assertEquals(6, pty.get("op"));
        assertFalse(pty.containsKey("cmd"));
        assertEquals(true, pty.get("includeOutput"));
        assertNotNull(pty.get("ptyBridge"));
        assertThrows(IllegalArgumentException.class, () -> service.execTerminal("read", "", "p", null, true));
        assertThrows(IllegalArgumentException.class, () -> service.execTerminal("init", "", "p", "unknown", true));
        assertThrows(IllegalArgumentException.class, () -> service.execTerminal("write", "pwd\n", "p", "python-pty", true));
        // Internal command runners still choose when to consume their output.
        assertFalse(service.execTerminal("write", "pwd\n", "p", null, false).containsKey("includeOutput"));
        Map<String, Object> literal = service.execTerminal("write", "init", "p", null, false);
        assertEquals(0, literal.get("op"));
        assertArrayEquals("init".getBytes(java.nio.charset.StandardCharsets.UTF_8), (byte[]) literal.get("cmd"));
        assertThrows(IllegalArgumentException.class, () -> service.execTerminal("init", "init", "p", null, false));
    }

    @Test
    void sendsPythonSourceOnlyWhenCreatingAnExplicitPty() throws Exception {
        Map<String, Object> pipe = service.execTerminal("init", "", "pipe", "pipe", false);
        assertEquals("pipe", pipe.get("terminalMode"));
        assertFalse(pipe.containsKey("ptyBridge"));
        Map<String, Object> pty = service.execTerminal("init", "", "pty", "python-pty", false);
        assertEquals("python-pty", pty.get("terminalMode"));
        try (InputStream source = getClass().getResourceAsStream("/terminal/pty_bridge.py")) {
            assertNotNull(source);
            assertArrayEquals(source.readAllBytes(), (byte[]) pty.get("ptyBridge"));
        }
        assertFalse(service.execTerminal("write", "pwd\r", "pty", null, false).containsKey("ptyBridge"));
        Map<String, Object> line = service.execTerminal("write-line", "pwd\n", "pipe", null, false);
        assertEquals(4, line.get("op"));
        assertArrayEquals("pwd\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), (byte[]) line.get("cmd"));
        assertFalse(line.containsKey("terminalMode"));
        assertEquals(2000, service.execTerminal("read", "2000", "pipe", null, false).get("waitMs"));
        assertThrows(IllegalArgumentException.class, () -> service.execTerminal("init", "", "p", "unknown", false));
    }

    @Test
    void boundsReadWaitToTenSeconds() throws Exception {
        assertEquals(10000, service.execTerminal("read", "10000", "pipe", null, false).get("waitMs"));
        assertEquals(10000, service.execTerminal("read", String.valueOf(Integer.MAX_VALUE), "pipe", null, false).get("waitMs"));
        assertEquals(0, service.execTerminal("read", "0", "pipe", null, false).get("waitMs"));
        for (String value : new String[]{null, "", "  "}) {
            assertEquals(0, service.execTerminal("read", value, "pipe", null, false).get("waitMs"));
        }
        for (String value : new String[]{"-1", "1.5", "not-a-number", "2147483648", "+1"}) {
            assertThrows(IllegalArgumentException.class, () -> service.execTerminal("read", value, "pipe", null, false));
        }
    }

    @Test
    void encodesABatchAsOneComponentOperation() throws Exception {
        Map<String, Object> params = service.readTerminals(List.of("first", "second"));
        assertEquals(5, params.get("op"));
        assertArrayEquals("first\nsecond".getBytes(java.nio.charset.StandardCharsets.UTF_8), (byte[]) params.get("processIds"));
        assertFalse(params.containsKey("waitMs"));
        assertFalse(params.containsKey("cmd"));
    }
}
