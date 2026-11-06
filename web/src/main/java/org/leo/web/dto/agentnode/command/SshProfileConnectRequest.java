package org.leo.web.dto.agentnode.command;

import org.leo.web.util.SshInputValidation;

/** The browser sends identifiers and the confirmed pin, never saved plaintext credentials. */
public record SshProfileConnectRequest(String sessionId, String processId, String profileId,
                                      Long version, String fingerprint) {
    public SshProfileConnectRequest normalized() {
        String pin = SshInputValidation.optionalFingerprint(fingerprint);
        return new SshProfileConnectRequest(SshInputValidation.requireSessionId(sessionId),
                SshInputValidation.requireSessionId(processId), SshInputValidation.normalizeProfileId(profileId),
                SshInputValidation.requireVersion(version), pin);
    }
}
