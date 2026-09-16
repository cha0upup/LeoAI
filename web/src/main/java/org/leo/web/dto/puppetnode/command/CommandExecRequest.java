package org.leo.web.dto.puppetnode.command;

import org.leo.web.exception.ApiException;

import java.nio.charset.StandardCharsets;
import java.util.Set;

public record CommandExecRequest(String sessionId, String cmd, String type, String processId,
                                 String terminalMode, boolean includeOutput) {
    private static final Set<String> OPERATIONS = Set.of("init", "write", "write-line", "read", "resize", "stop");
    private static final Set<String> MODES = Set.of("pipe", "python-pty");
    private static final Set<String> INPUT_OPERATIONS = Set.of("write", "write-line", "resize");
    private static final int MAX_LINE_INPUT_BYTES = 1024 * 1024;

    public CommandExecRequest(String sessionId, String cmd, String type, String processId) {
        this(sessionId, cmd, type, processId, null, false);
    }

    public CommandExecRequest(String sessionId, String cmd, String type, String processId, String terminalMode) {
        this(sessionId, cmd, type, processId, terminalMode, false);
    }

    /** Normalize identifiers, but never trim terminal bytes (spaces, CR, Ctrl+C). */
    public static CommandExecRequest normalize(CommandExecRequest request) {
        if (request == null) throw ApiException.badRequest("请求体不能为空");
        String sessionId = requireText(request.sessionId, "sessionId");
        String type = requireText(request.type, "type");
        if (!OPERATIONS.contains(type)) throw ApiException.badRequest("type不支持");
        if (request.includeOutput && !Set.of("init", "write", "write-line").contains(type)) {
            throw ApiException.badRequest("includeOutput仅允许用于终端初始化或写入");
        }
        String processId = normalizeProcessId(request.processId);
        String command = request.cmd == null ? "" : request.cmd;
        if (INPUT_OPERATIONS.contains(type) && command.isEmpty()) {
            throw ApiException.badRequest("cmd不能为空");
        }
        if (("init".equals(type) || "stop".equals(type)) && !command.isEmpty()) {
            throw ApiException.badRequest("初始化和停止终端不接受cmd");
        }
        if ("read".equals(type)) {
            command = command.trim();
            if (!command.isEmpty()) {
                try {
                    if (!command.matches("[0-9]+")) throw new NumberFormatException();
                    command = String.valueOf(Math.min(10000, Integer.parseInt(command)));
                } catch (NumberFormatException error) {
                    throw ApiException.badRequest("读取等待时间必须是32位非负整数");
                }
            }
        }
        if ("write-line".equals(type) && (!command.endsWith("\n")
                || command.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_INPUT_BYTES)) {
            throw ApiException.badRequest("整行输入必须以换行符结束且不超过1 MiB");
        }
        String mode = request.terminalMode == null || request.terminalMode.isBlank()
                ? null : request.terminalMode.trim();
        if (mode != null) {
            if (!MODES.contains(mode)) throw ApiException.badRequest("terminalMode不支持");
            if (!"init".equals(type)) {
                throw ApiException.badRequest("terminalMode仅允许在初始化终端时指定");
            }
        }
        return new CommandExecRequest(sessionId, command, type, processId, mode, request.includeOutput);
    }

    static String normalizeProcessId(String value) {
        String processId = requireText(value, "processId");
        if (processId.length() > 128 || !processId.matches("[A-Za-z0-9._-]+")) {
            throw ApiException.badRequest("processId格式无效");
        }
        return processId;
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw ApiException.badRequest(field + "不能为空");
        return value.trim();
    }
}
