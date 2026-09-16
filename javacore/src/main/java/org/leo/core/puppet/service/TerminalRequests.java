package org.leo.core.puppet.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared wire encoding for terminal adapters and internal command execution. */
final class TerminalRequests {
    static final String COMPONENT = "ExecCommandComponent";
    private static final int WRITE = 0;
    private static final int READ = 1;
    private static final int STOP = 2;
    private static final int RESIZE = 3;
    private static final int WRITE_LINE = 4;
    private static final int READ_BATCH = 5;
    private static final int INIT = 6;
    private static final int MAX_READ_WAIT_MS = 10000;

    private TerminalRequests() {}

    static Map<String, Object> batchRead(List<String> processIds) {
        return Map.of("op", READ_BATCH,
                "processIds", String.join("\n", processIds).getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Object> create(String type, String command, String processId,
                                      String terminalMode, boolean includeOutput) throws IOException {
        int operation = switch (type) {
            case "init" -> INIT;
            case "write" -> WRITE;
            case "write-line" -> WRITE_LINE;
            case "read" -> READ;
            case "resize" -> RESIZE;
            case "stop" -> STOP;
            default -> throw new IllegalArgumentException("不支持的终端操作: " + type);
        };
        if (includeOutput && operation != INIT && operation != WRITE && operation != WRITE_LINE) {
            throw new IllegalArgumentException("output can only accompany terminal initialization or input");
        }
        if ((operation == INIT || operation == STOP) && command != null && !command.isEmpty()) {
            throw new IllegalArgumentException("init and stop do not accept cmd");
        }
        if (terminalMode != null) {
            if (!"pipe".equals(terminalMode) && !"python-pty".equals(terminalMode)) {
                throw new IllegalArgumentException("不支持的终端模式: " + terminalMode);
            }
            if (operation != INIT) {
                throw new IllegalArgumentException("terminal mode is only accepted during initialization");
            }
        }
        Map<String, Object> params = new HashMap<>();
        params.put("processId", processId.getBytes(StandardCharsets.UTF_8));
        params.put("op", operation);
        if (includeOutput) params.put("includeOutput", true);
        if (operation == READ) params.put("waitMs", parseReadWait(command));
        else if (operation == WRITE || operation == WRITE_LINE || operation == RESIZE) {
            if (command == null || command.isEmpty()) throw new IllegalArgumentException("cmd must not be empty");
            params.put("cmd", command.getBytes(StandardCharsets.UTF_8));
        }
        if (terminalMode != null) params.put("terminalMode", terminalMode);
        if ("python-pty".equals(terminalMode)) {
            // Sent only on initialization; the target does not need a script resource or file.
            try (InputStream source = TerminalRequests.class.getResourceAsStream("/terminal/pty_bridge.py")) {
                if (source == null) throw new IOException("Python PTY 桥接资源缺失");
                params.put("ptyBridge", source.readAllBytes());
            }
        }
        return params;
    }

    private static int parseReadWait(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            String text = value.trim();
            if (!text.matches("[0-9]+")) throw new NumberFormatException();
            return Math.min(MAX_READ_WAIT_MS, Integer.parseInt(text));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("read cmd must be a nonnegative integer within 32-bit range", error);
        }
    }
}
