package org.leo.web.dto.agentnode.command;

import org.leo.service.ssh.SshInputValidation;
import org.leo.web.exception.SshApiExceptionMapper;

public record SshProfileRequest(String sessionId, String profileId, Long version) {
    public SshProfileRequest normalized() {
        return SshApiExceptionMapper.call(() -> {
            if (version != null) SshInputValidation.requireVersion(version);
            return new SshProfileRequest(SshInputValidation.requireSessionId(sessionId),
                    SshInputValidation.normalizeProfileId(profileId), version);
        });
    }
}
