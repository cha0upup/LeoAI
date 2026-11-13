package org.leo.web.dto.agentnode.command;

import org.leo.service.ssh.SshInputValidation;
import org.leo.service.ssh.SshProfileConnectCommand;

/** The browser sends identifiers and the confirmed pin, never saved plaintext credentials. */
public record SshProfileConnectRequest(String sessionId, String processId, String profileId,
                                      Long version, String fingerprint) {
    public SshProfileConnectCommand toCommand() {
        var command = new SshProfileConnectCommand(processId, profileId, version, fingerprint);
        SshInputValidation.requireSessionId(sessionId);
        return command;
    }
}
