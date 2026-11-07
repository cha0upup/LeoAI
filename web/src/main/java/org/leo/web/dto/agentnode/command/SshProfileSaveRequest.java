package org.leo.web.dto.agentnode.command;

import org.leo.service.ssh.SshInputValidation;
import org.leo.service.ssh.SshProfileSaveCommand;
import org.leo.web.exception.SshApiExceptionMapper;

/** Null secrets retain their saved values; an explicit empty passphrase clears it. */
public record SshProfileSaveRequest(String sessionId, String profileId, Long version, String name,
                                    String host, Integer port, String username, String authType,
                                    String password, String privateKey, String passphrase) {
    public SshProfileSaveCommand toCommand() {
        return SshApiExceptionMapper.call(() -> {
            var command = new SshProfileSaveCommand(profileId, version, name, host, port, username, authType,
                    password, privateKey, passphrase);
            SshInputValidation.requireSessionId(sessionId);
            return command;
        });
    }

    @Override
    public String toString() {
        return "SshProfileSaveRequest[credentials redacted]";
    }
}
