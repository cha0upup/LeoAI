package org.leo.web.dto.puppetnode.command;

public record CommandExecRequest(String sessionId, String cmd, String type, String processId) {
}
