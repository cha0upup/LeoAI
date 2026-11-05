package org.leo.web.dto.agentnode.command;

import org.leo.web.exception.ApiException;
import java.util.UUID;

public record SshProfileRequest(String sessionId, String profileId, Long version) {
    public SshProfileRequest normalized() {
        if (version != null) requireVersion(version);
        return new SshProfileRequest(SshConnectRequest.id(sessionId), profileId(profileId), version);
    }
    public static String profileId(String value) {
        try {
            String id = UUID.fromString(value).toString();
            if (!id.equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException error) { throw ApiException.badRequest("SSH 配置标识无效"); }
    }
    public static long requireVersion(Long value) {
        if (value == null || value < 1 || value > Integer.MAX_VALUE) throw ApiException.badRequest("SSH 配置版本无效");
        return value;
    }
}
