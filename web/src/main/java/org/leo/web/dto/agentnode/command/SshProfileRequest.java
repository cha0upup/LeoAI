package org.leo.web.dto.agentnode.command;

import org.leo.web.util.SshInputValidation;

public record SshProfileRequest(String sessionId, String profileId, Long version) {
    public SshProfileRequest normalized() {
        if (version != null) SshInputValidation.requireVersion(version);
        return new SshProfileRequest(SshInputValidation.requireSessionId(sessionId),
                SshInputValidation.normalizeProfileId(profileId), version);
    }
}
