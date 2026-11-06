package org.leo.web.service.ssh;

import net.schmizz.sshj.userauth.UserAuthException;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.leo.core.agent.capability.LocalForwardCapable;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.runtime.CapabilitySet;
import org.leo.core.runtime.CapabilityStatus;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.dao.mapper.AgentSshConnectionMapper;
import org.leo.service.audit.AgentAuditService;
import org.leo.service.security.AgentAccessService;
import org.leo.service.ssh.SshConnectionException;
import org.leo.service.user.UserService;
import org.leo.web.testing.SqliteTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshConnectionManagementServiceTest {
    @TempDir Path temp;
    final String sessionId = "ssh-management-parent";
    final String pin = "SHA256:" + "A".repeat(43);
    final AgentSshService ssh = mock(AgentSshService.class);
    final UserService users = mock(UserService.class);
    final AgentAccessService access = mock(AgentAccessService.class);
    final AgentAuditService audit = mock(AgentAuditService.class);
    User actor;
    AbstractAgentNode node;
    AgentNodeSession parent;
    SshConnectionManagementService management;
    SshConnectionProfiles profiles;
    AgentSshConnectionMapper mapper;
    SqlSession database;

    @BeforeEach void setup() {
        var source = SqliteTestDatabase.create(temp.resolve("management.db"));
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO users (user_id, user_name, password, create_time, update_time) VALUES ('actor', 'actor', 'hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO agents (agent_id, agent_name, parent_agent_id, create_by_user_id, conn_link, req_disguise_id, resp_disguise_id, create_time, update_time) VALUES ('agent', 'agent', 'root', 'actor', '/', 'request', 'response', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        var configuration = SqliteTestDatabase.myBatisConfiguration(source);
        configuration.addMapper(AgentSshConnectionMapper.class);
        database = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = database.getMapper(AgentSshConnectionMapper.class);
        profiles = new SshConnectionProfiles(mapper, new SshProfileCryptoService("", temp.resolve("ssh.key").toString()));
        node = mock(AbstractAgentNode.class, withSettings().extraInterfaces(LocalForwardCapable.class, ComponentInvokeCapable.class));
        when(node.getCapabilitySet()).thenReturn(CapabilitySet.empty());
        Agent agent = new Agent(); agent.setAgentId("agent"); when(node.getAgent()).thenReturn(agent);
        parent = new AgentNodeSession(); parent.setSessionId(sessionId); parent.setAgentNode(node);
        AgentNodeSessionContainer.addSession(sessionId, parent);
        actor = new User(); actor.setUserId("actor"); actor.setStatus(1);
        when(users.getUserById("actor")).thenReturn(actor);
        when(access.canAccessSession(parent, actor)).thenReturn(true);
        management = new SshConnectionManagementService(profiles, ssh, users, access, audit);
    }

    @AfterEach void cleanup() {
        AgentNodeSessionContainer.removeSession(sessionId);
        if (database != null) database.close();
    }

    @Test void sharesEncryptedCrudWithThePanelAndPreservesOmittedSecretsAndOptimisticVersions() throws Exception {
        Map<String, Object> created = create(); String id = id(created);
        assertPublic(created); assertEquals(22, created.get("port"));
        assertEquals("actor", mapper.selectById("agent", id).getCreateUserId());
        assertFalse(mapper.selectById("agent", id).getCredentials().contains("fixture-secret"));
        assertEquals(id, management.list("actor", sessionId).get(0).get("profileId"));
        Map<String, Object> updated = management.update("actor", sessionId, id, 1L, Map.of("name", "renamed"));
        assertEquals(2L, updated.get("version")); assertEquals("renamed", updated.get("name")); assertPublic(updated);
        assertEquals("fixture-secret", profiles.resolveConnection("actor", parent,
                new org.leo.web.dto.agentnode.command.SshProfileConnectRequest(sessionId, "test", id, 2L, pin)).password());
        assertEquals(SshConnectionException.Kind.CONFLICT, assertThrows(SshConnectionException.class,
                () -> management.update("actor", sessionId, id, 1L, Map.of("host", "changed.internal"))).getKind());
        assertEquals(SshConnectionException.Kind.CONFLICT, assertThrows(SshConnectionException.class,
                () -> management.delete("actor", sessionId, id, 1L)).getKind());
        assertEquals(true, management.delete("actor", sessionId, id, 2L).get("deleted"));
        assertTrue(management.list("actor", sessionId).isEmpty());
        assertEquals(SshConnectionException.Kind.NOT_FOUND, assertThrows(SshConnectionException.class,
                () -> management.get("actor", sessionId, id)).getKind());
    }

    @Test void supportsPrivateKeyUpdatesAndExplicitPassphraseClearingWithoutReturningCredentials() {
        var created = management.create("actor", sessionId, Map.of("host", "ssh.internal", "username", "ops",
                "authType", "privateKey", "privateKey", "fixture-private-key", "passphrase", "fixture-passphrase"));
        assertEquals(true, created.get("hasPrivateKey")); assertPublic(created);
        var updated = management.update("actor", sessionId, id(created), 1L, Map.of("passphrase", ""));
        assertEquals(false, updated.get("hasPassphrase")); assertPublic(updated);
    }

    @Test void rejectsUnknownFieldsFractionalPortsAndInvalidUpdatesWithoutMutatingProfiles() {
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                Map.of("sessionId", "another-session"), Map.of("port", 22.5),
                Map.of("host", "ssh.internal", "username", "ops", "password", "secret", "port", 0))) {
            assertEquals(SshConnectionException.Kind.VALIDATION, assertThrows(SshConnectionException.class,
                    () -> management.create("actor", sessionId, invalid)).getKind());
        }
        assertTrue(management.list("actor", sessionId).isEmpty());
        var created = create();
        assertEquals(SshConnectionException.Kind.VALIDATION, assertThrows(SshConnectionException.class,
                () -> management.update("actor", sessionId, id(created), 1L, Map.of("authType", "privateKey"))).getKind());
        assertEquals(1L, management.get("actor", sessionId, id(created)).get("version"));
    }

    @Test void rechecksCurrentAccessAndActiveUserOnEveryOperation() {
        var created = create();
        when(access.canAccessSession(parent, actor)).thenReturn(false);
        assertEquals(SshConnectionException.Kind.FORBIDDEN, assertThrows(SshConnectionException.class,
                () -> management.list("actor", sessionId)).getKind());
        assertThrows(SshConnectionException.class, () -> management.get("actor", sessionId, id(created)));
        assertThrows(SshConnectionException.class, () -> management.update("actor", sessionId, id(created), 1L, Map.of("name", "x")));
        assertThrows(SshConnectionException.class, () -> management.delete("actor", sessionId, id(created), 1L));
        assertThrows(SshConnectionException.class, () -> management.test("actor", sessionId, id(created), 1L, null));
        when(access.canAccessSession(parent, actor)).thenReturn(true); actor.setStatus(0);
        assertThrows(SshConnectionException.class, () -> management.list("actor", sessionId));
        AgentNodeSessionContainer.removeSession(sessionId);
        assertEquals(SshConnectionException.Kind.SESSION_EXPIRED, assertThrows(SshConnectionException.class,
                () -> management.list("actor", sessionId)).getKind());
        verifyNoInteractions(ssh);
    }

    @Test void probesWithoutCredentialsThenAuthenticatesWithConfirmedOrSavedPinsAndRecordsSuccess() throws Exception {
        var created = create(); String id = id(created);
        when(ssh.probeFingerprint(eq("actor"), anyString(), eq(parent), any(), eq("ssh.internal"), eq(22))).thenReturn(pin);
        var initial = management.test("actor", sessionId, id, 1L, null);
        assertEquals(true, initial.get("confirmationRequired")); assertEquals(pin, initial.get("fingerprint"));
        verify(ssh, never()).testConnection(any(), any(), any(), any(), any());
        assertEquals("", management.get("actor", sessionId, id).get("fingerprint"));
        var confirmed = management.test("actor", sessionId, id, 1L, pin);
        assertEquals(true, confirmed.get("connected")); assertEquals(true, confirmed.get("profileSaved"));
        assertPublic(confirmed);
        assertEquals(pin, management.get("actor", sessionId, id).get("fingerprint"));
        assertTrue(((Number) management.get("actor", sessionId, id).get("lastConnectedAt")).longValue() > 0);
        assertEquals(true, management.test("actor", sessionId, id, 1L, null).get("connected"));
        verify(ssh, times(1)).probeFingerprint(any(), any(), any(), any(), any(), anyInt());
        verify(ssh, times(2)).testConnection(eq("actor"), anyString(), eq(parent), any(),
                argThat(spec -> "fixture-secret".equals(spec.password()) && pin.equals(spec.fingerprint())));
    }

    @Test void reportsChangedHostKeysWithoutReplacingThePreviouslyTrustedPin() throws Exception {
        var created = create(); String id = id(created);
        profiles.recordFingerprint("actor", parent, id, 1L, pin);
        String changed = "SHA256:" + "B".repeat(43);
        doThrow(new AgentSshService.HostKeyChangedException(changed)).when(ssh).testConnection(any(), any(), any(), any(), any());
        var result = management.test("actor", sessionId, id, 1L, null);
        assertEquals(true, result.get("confirmationRequired")); assertEquals(pin, result.get("previousFingerprint"));
        assertEquals(changed, result.get("fingerprint"));
        assertEquals(pin, management.get("actor", sessionId, id).get("fingerprint"));
    }

    @Test void sanitizesAuthenticationAndTransportFailuresAndKeepsFailedTestsOutOfConnectionHistory() throws Exception {
        var created = create(); String id = id(created);
        doThrow(new UserAuthException("fixture-secret")).when(ssh).testConnection(any(), any(), any(), any(), any());
        var auth = management.test("actor", sessionId, id, 1L, pin);
        assertEquals(false, auth.get("connected")); assertEquals("SSH_AUTHENTICATION_FAILED", auth.get("errorCode"));
        assertFalse(auth.toString().contains("fixture-secret"));
        doThrow(new IOException("fixture-private-key")).when(ssh).testConnection(any(), any(), any(), any(), any());
        var network = management.test("actor", sessionId, id, 1L, pin);
        assertEquals("SSH_CONNECTION_FAILED", network.get("errorCode"));
        assertFalse(network.toString().contains("fixture-private-key"));
        assertEquals(0L, management.get("actor", sessionId, id).get("lastConnectedAt"));
        assertEquals("", management.get("actor", sessionId, id).get("fingerprint"));
    }

    @Test void testsRequireBothAgentCapabilitiesButOfflineMetadataManagementDoesNot() {
        var created = create();
        when(node.getCapabilitySet()).thenReturn(new CapabilitySet(List.of(CapabilityStatus.unavailable("localForward", "fixture"))));
        assertEquals(id(created), management.get("actor", sessionId, id(created)).get("profileId"));
        assertEquals(SshConnectionException.Kind.UNSUPPORTED_CAPABILITY, assertThrows(SshConnectionException.class,
                () -> management.test("actor", sessionId, id(created), 1L, pin)).getKind());
        verifyNoInteractions(ssh);
    }

    @Test void auditUsesTheActualActorAndNeverReceivesSecrets() {
        var created = create();
        verify(audit).logOperationAs(eq(actor), eq(sessionId), eq(node), eq("SSH_PROFILE_SAVE"), anyString(),
                eq(id(created)), argThat(params -> id(created).equals(params.get("profileId")) && !params.toString().contains("fixture-secret")
                        && !params.containsKey("password") && !params.containsKey("privateKey")),
                eq(200), anyString(), eq("SUCCESS"), isNull());
    }

    @Test void successfulTestsNeverResurrectProfilesDeletedWhileAuthenticationWasInFlight() throws Exception {
        var created = create(); String id = id(created);
        doAnswer(invocation -> {
            profiles.delete("actor", parent, new org.leo.web.dto.agentnode.command.SshProfileRequest(sessionId, id, 1L));
            return null;
        }).when(ssh).testConnection(any(), any(), any(), any(), any());
        var result = management.test("actor", sessionId, id, 1L, pin);
        assertEquals(true, result.get("connected")); assertEquals(false, result.get("profileSaved"));
        assertTrue(management.list("actor", sessionId).isEmpty());
    }

    @Test void anotherAgentsProfileIdsStayOutsideTheCurrentScope() {
        var created = create();
        Agent other = new Agent(); other.setAgentId("other-agent"); when(node.getAgent()).thenReturn(other);
        assertTrue(management.list("actor", sessionId).isEmpty());
        assertEquals(SshConnectionException.Kind.NOT_FOUND, assertThrows(SshConnectionException.class,
                () -> management.get("actor", sessionId, id(created))).getKind());
        assertThrows(SshConnectionException.class, () -> management.update("actor", sessionId, id(created), 1L, Map.of("name", "x")));
        assertThrows(SshConnectionException.class, () -> management.delete("actor", sessionId, id(created), 1L));
        assertThrows(SshConnectionException.class, () -> management.test("actor", sessionId, id(created), 1L, pin));
        verifyNoInteractions(ssh);
    }

    private Map<String, Object> create() {
        return management.create("actor", sessionId, Map.of("host", "ssh.internal", "username", "ops", "password", "fixture-secret"));
    }
    private String id(Map<String, Object> profile) { return (String) profile.get("profileId"); }
    private void assertPublic(Map<String, Object> view) {
        for (String field : List.of("password", "privateKey", "passphrase", "credentials")) assertFalse(view.containsKey(field));
    }
}
