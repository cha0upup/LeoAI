package org.leo.core.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Persisted SSH profile; credentials hold only an encrypted envelope. */
public class AgentSshConnection {
    private String profileId;
    private String createUserId;
    private String agentId;
    private Long version;
    private String name;
    private String host;
    private Integer port;
    private String username;
    private String authType;
    private String credentials;
    private String fingerprint;
    private boolean hasPassword;
    private boolean hasPrivateKey;
    private boolean hasPassphrase;
    private Long updatedAt;
    private Long lastConnectedAt;

    public String getProfileId() { return profileId; }
    public void setProfileId(String profileId) { this.profileId = profileId; }
    public String getCreateUserId() { return createUserId; }
    public void setCreateUserId(String createUserId) { this.createUserId = createUserId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getCredentials() { return credentials; }
    public void setCredentials(String credentials) { this.credentials = credentials; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public boolean getHasPassword() { return hasPassword; }
    public void setHasPassword(boolean hasPassword) { this.hasPassword = hasPassword; }
    public boolean getHasPrivateKey() { return hasPrivateKey; }
    public void setHasPrivateKey(boolean hasPrivateKey) { this.hasPrivateKey = hasPrivateKey; }
    public boolean getHasPassphrase() { return hasPassphrase; }
    public void setHasPassphrase(boolean hasPassphrase) { this.hasPassphrase = hasPassphrase; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getLastConnectedAt() { return lastConnectedAt; }
    public void setLastConnectedAt(Long lastConnectedAt) { this.lastConnectedAt = lastConnectedAt; }
}
