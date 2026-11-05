package org.leo.web.service.ssh;

import org.leo.core.entity.AgentSshConnection;
import org.leo.core.session.AgentNodeSession;
import org.leo.dao.mapper.AgentSshConnectionMapper;
import org.leo.web.dto.agentnode.command.SshConnectRequest;
import org.leo.web.dto.agentnode.command.SshProfileConnectRequest;
import org.leo.web.dto.agentnode.command.SshProfileRequest;
import org.leo.web.dto.agentnode.command.SshProfileSaveRequest;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.*;

/** Agent-shared SSH profiles, matching database management. The caller must pass session/Agent access checks. */
@Service
public class SshConnectionProfiles {
    private static final int MAX_PROFILES = 100;
    private final AgentSshConnectionMapper mapper;
    private final SshProfileCryptoService crypto;

    public SshConnectionProfiles(AgentSshConnectionMapper mapper, SshProfileCryptoService crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    private String agentId(String userId, AgentNodeSession parent) {
        String agentId = parent.getAgentNode() != null && parent.getAgentNode().getAgent() != null
                ? parent.getAgentNode().getAgent().getAgentId() : parent.getAgentId();
        if (userId == null || userId.isBlank() || agentId == null || agentId.isBlank())
            throw ApiException.badRequest("SSH 配置缺少用户或 Agent 标识，请重新建立控制台会话");
        return agentId;
    }

    private AgentSshConnection read(String agentId, String id, boolean withCredentials) {
        id = SshProfileRequest.profileId(id);
        AgentSshConnection row = withCredentials
                ? mapper.selectById(agentId, id)
                : mapper.selectMetadataById(agentId, id);
        if (row == null) throw ApiException.notFound("SSH 连接配置不存在或已删除");
        return row;
    }

    private Map<String, Object> view(AgentSshConnection row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profileId", row.getProfileId()); result.put("version", row.getVersion());
        result.put("name", row.getName()); result.put("host", row.getHost()); result.put("port", row.getPort());
        result.put("username", row.getUsername()); result.put("authType", row.getAuthType());
        result.put("fingerprint", row.getFingerprint());
        result.put("hasPassword", row.getHasPassword()); result.put("hasPrivateKey", row.getHasPrivateKey());
        result.put("hasPassphrase", row.getHasPassphrase());
        result.put("updatedAt", row.getUpdatedAt()); result.put("lastConnectedAt", row.getLastConnectedAt());
        return result;
    }

    private void requireVersion(AgentSshConnection row, Long expected) {
        if (row.getVersion() != SshProfileRequest.requireVersion(expected)) throw conflict();
    }

    private ApiException conflict() {
        return ApiException.conflict("SSH 配置已被修改或删除，请刷新连接列表后重试");
    }

    private Map<String, Object> secrets(String agentId, AgentSshConnection row) throws Exception {
        Map<String, Object> envelope = crypto.decrypt(row.getCredentials());
        if (!agentId.equals(envelope.get("agentId"))) throw new IllegalStateException("SSH 凭据作用域不匹配");
        for (var field : Map.<String, Object>of("profileId", row.getProfileId(), "host", row.getHost(),
                "port", row.getPort(), "username", row.getUsername(), "authType", row.getAuthType()).entrySet()) {
            if (!Objects.equals(String.valueOf(field.getValue()), String.valueOf(envelope.get(field.getKey()))))
                throw new IllegalStateException("SSH 凭据与配置不匹配");
        }
        return envelope;
    }

    private String secret(Map<String, Object> envelope, String field) {
        return envelope.get(field) instanceof String value ? value : "";
    }

    public List<Map<String, Object>> list(String user, AgentNodeSession parent) {
        String agentId = agentId(user, parent);
        return mapper.selectMetadataByAgent(agentId).stream().map(this::view).toList();
    }

    public Map<String, Object> get(String user, AgentNodeSession parent, String id) {
        return view(read(agentId(user, parent), id, false));
    }

    public Map<String, Object> save(String user, AgentNodeSession parent, SshProfileSaveRequest body) throws Exception {
        SshProfileSaveRequest request = body.normalized();
        String agentId = agentId(user, parent);
        AgentSshConnection old = request.profileId() == null ? null : read(agentId, request.profileId(), true);
        if (old != null) requireVersion(old, request.version());
        boolean sameAuth = old != null && request.authType().equals(old.getAuthType());
        Map<String, Object> previous = sameAuth ? secrets(agentId, old) : Map.of();
        boolean keyAuth = "privateKey".equals(request.authType());
        String password = keyAuth ? "" : request.password() == null ? secret(previous, "password") : request.password();
        String privateKey = !keyAuth ? "" : request.privateKey() == null ? secret(previous, "privateKey") : request.privateKey();
        String passphrase = !keyAuth ? "" : request.passphrase() == null ? secret(previous, "passphrase") : request.passphrase();
        SshConnectRequest.validateCredentials(request.authType(), password, privateKey, passphrase);

        AgentSshConnection row = new AgentSshConnection();
        row.setProfileId(old == null ? UUID.randomUUID().toString() : old.getProfileId());
        row.setCreateUserId(old == null ? user : old.getCreateUserId()); row.setAgentId(agentId);
        row.setVersion(old == null ? 1L : old.getVersion() + 1);
        row.setName(request.name()); row.setHost(request.host()); row.setPort(request.port());
        row.setUsername(request.username()); row.setAuthType(request.authType());
        row.setFingerprint(""); row.setLastConnectedAt(0L);
        row.setHasPassword(!password.isEmpty()); row.setHasPrivateKey(!privateKey.isEmpty()); row.setHasPassphrase(!passphrase.isEmpty());
        row.setUpdatedAt(System.currentTimeMillis());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("profileId", row.getProfileId()); envelope.put("host", row.getHost()); envelope.put("port", row.getPort());
        envelope.put("username", row.getUsername()); envelope.put("authType", row.getAuthType()); envelope.put("agentId", agentId);
        envelope.put("password", password); envelope.put("privateKey", privateKey); envelope.put("passphrase", passphrase);
        row.setCredentials(crypto.encrypt(envelope));
        if (old == null) {
            if (mapper.insertWithinLimit(row, MAX_PROFILES) == 0)
                throw ApiException.tooManyRequests("SSH 配置已达上限，请先删除闲置配置");
        } else if (mapper.updateIfVersion(row, old.getVersion()) == 0) {
            throw conflict();
        }
        return view(read(agentId, row.getProfileId(), false));
    }

    public void delete(String user, AgentNodeSession parent, SshProfileRequest request) {
        String agentId = agentId(user, parent);
        String id = SshProfileRequest.profileId(request.profileId());
        long version = SshProfileRequest.requireVersion(request.version());
        requireVersion(read(agentId, id, false), version);
        if (mapper.deleteIfVersion(agentId, id, version) == 0) throw conflict();
    }

    public Map<String, Object> getForConnection(String user, AgentNodeSession parent, SshProfileConnectRequest request) {
        AgentSshConnection row = read(agentId(user, parent), request.profileId(), false);
        requireVersion(row, request.version());
        return view(row);
    }

    public SshConnectRequest resolveConnection(String user, AgentNodeSession parent, SshProfileConnectRequest request) throws Exception {
        String agentId = agentId(user, parent);
        AgentSshConnection row = read(agentId, request.profileId(), true);
        requireVersion(row, request.version());
        Map<String, Object> envelope = secrets(agentId, row);
        return new SshConnectRequest(request.processId(), row.getHost(), row.getPort(),
                row.getUsername(), row.getAuthType(), secret(envelope, "password"), secret(envelope, "privateKey"),
                secret(envelope, "passphrase"), request.fingerprint() == null ? row.getFingerprint() : request.fingerprint()).normalized();
    }

    public void recordFingerprint(String user, AgentNodeSession parent, String id, Long version, String fingerprint) {
        String agentId = agentId(user, parent);
        id = SshProfileRequest.profileId(id);
        long expected = SshProfileRequest.requireVersion(version);
        if (mapper.recordConnectionIfVersion(agentId, id, expected,
                fingerprint, System.currentTimeMillis()) == 0) throw conflict();
    }
}
