package org.leo.web.dto.agentnode.command;

import org.leo.service.ssh.SshInputValidation;
import org.leo.service.ssh.SshProfileSaveCommand;

/** Null secrets retain their saved values; an explicit empty passphrase clears it. */
public record SshProfileSaveRequest(String sessionId, String profileId, Long version, String name,
                                    String host, Integer port, String username, String authType,
                                    String password, String privateKey, String passphrase) {
    public SshProfileSaveCommand toCommand() {
        var command = new SshProfileSaveCommand(profileId, version, name, host, port, username, authType,
                password, privateKey, passphrase);
        SshInputValidation.requireSessionId(sessionId);
        return command;
    }

    @Override
    public String toString() {
        return "SshProfileSaveRequest[credentials redacted]";
    }
}
