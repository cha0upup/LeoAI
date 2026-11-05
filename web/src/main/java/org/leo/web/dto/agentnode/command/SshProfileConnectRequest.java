package org.leo.web.dto.agentnode.command;

import org.leo.web.exception.ApiException;

/** The browser sends identifiers and the confirmed pin, never saved plaintext credentials. */
public record SshProfileConnectRequest(String sessionId, String processId, String profileId,
                                      Long version, String fingerprint) {
    public SshProfileConnectRequest normalized() {
        if (fingerprint != null && !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"))
            throw ApiException.badRequest("SSH 主机指纹格式无效");
        return new SshProfileConnectRequest(SshConnectRequest.id(sessionId), SshConnectRequest.id(processId),
                SshProfileRequest.profileId(profileId), SshProfileRequest.requireVersion(version), fingerprint);
    }
}
