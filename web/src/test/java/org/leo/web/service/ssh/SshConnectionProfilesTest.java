package org.leo.web.service.ssh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.leo.dao.mapper.AgentSshConnectionMapper;
import org.leo.core.entity.AgentSshConnection;
import org.leo.web.testing.SqliteTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.session.AgentNodeSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.leo.web.dto.agentnode.command.*;
import org.leo.web.exception.ApiException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshConnectionProfilesTest {
    @TempDir Path temp;
    final List<SqlSession> sessions = new ArrayList<>();
    SqlSessionFactory factory;
    JdbcTemplate jdbc;
    AgentSshConnectionMapper mapper;
    SshConnectionProfiles profiles;
    AgentNodeSession parent;
    final String pin = "SHA256:" + "A".repeat(43);

    @BeforeEach void setup() {
        var source = SqliteTestDatabase.create(temp.resolve("profiles.db"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.update("INSERT INTO users (user_id, user_name, password, create_time, update_time) VALUES ('owner', 'owner', 'hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO users (user_id, user_name, password, create_time, update_time) VALUES ('other', 'other', 'hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO agents (agent_id, agent_name, parent_agent_id, create_by_user_id, conn_link, req_disguise_id, resp_disguise_id, create_time, update_time) VALUES ('agent', 'agent', 'root', 'owner', '/', 'request', 'response', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        var config = SqliteTestDatabase.myBatisConfiguration(source);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.addMapper(AgentSshConnectionMapper.class);
        factory = new SqlSessionFactoryBuilder().build(config);
        mapper = openMapper();
        profiles = new SshConnectionProfiles(mapper, crypto());
        parent = mock(AgentNodeSession.class);
        when(parent.getAgentId()).thenReturn("agent");
        when(parent.getSessionId()).thenReturn("parent");
        when(parent.getCurrentHostId()).thenReturn("host-binding");
    }
    @AfterEach void close() { sessions.forEach(SqlSession::close); }
    AgentSshConnectionMapper openMapper() {
        SqlSession session = factory.openSession(true); sessions.add(session);
        return session.getMapper(AgentSshConnectionMapper.class);
    }
    SshProfileCryptoService crypto() {
        return new SshProfileCryptoService("", temp.resolve("ssh.key").toString());
    }
    SshConnectionProfiles restarted() { return new SshConnectionProfiles(openMapper(), crypto()); }
    SshProfileSaveRequest create(String auth, String password, String key, String phrase) {
        return new SshProfileSaveRequest("parent", null, null, "build", "ssh.internal", 22, "ops", auth, password, key, phrase);
    }
    String id(Map<String, Object> row) { return (String) row.get("profileId"); }
    Long version(Map<String, Object> row) { return ((Number) row.get("version")).longValue(); }
    SshProfileSaveRequest update(Map<String, Object> row, String host, String auth, String password, String key, String phrase) {
        return new SshProfileSaveRequest("parent", id(row), version(row), "renamed", host, 22, "ops", auth, password, key, phrase);
    }
    SshProfileConnectRequest connect(Map<String, Object> row) {
        return new SshProfileConnectRequest("parent", "child", id(row), version(row), pin).normalized();
    }
    AgentSshConnection stored(Map<String, Object> row) {
        return mapper.selectById("agent", id(row));
    }
    void assertPublic(Map<String, Object> row) {
        for (String field : List.of("password", "privateKey", "passphrase", "credentials", "scope")) assertFalse(row.containsKey(field));
    }
    @Test void encryptsCredentialsAndReusesTheSameKeyAfterRestartWithoutReturningSecrets() throws Exception {
        String password = "  exact password 中文 \n";
        Map<String, Object> saved = profiles.save("owner", parent, create("password", password, null, null));
        assertPublic(saved); assertEquals(true, saved.get("hasPassword"));
        assertTrue(stored(saved).getCredentials().contains("enc:ssh:v1:"));
        assertNull(mapper.selectMetadataById("agent", id(saved)).getCredentials());
        assertFalse(new ObjectMapper().writeValueAsString(stored(saved)).contains("credentials"));
        assertFalse(Files.exists(temp.resolve("ssh-connections")));
        assertFalse(stored(saved).getCredentials().contains("exact password"));
        assertPublic(profiles.get("owner", parent, id(saved)));
        profiles.list("owner", parent).forEach(this::assertPublic);
        assertEquals(password, restarted().resolveConnection("owner", parent, connect(saved)).password());
        Map<String, Object> whitespace = profiles.save("owner", parent, create("password", "   ", null, null));
        assertEquals("   ", profiles.resolveConnection("owner", parent, connect(whitespace)).password());
        assertNotEquals(id(saved), id(whitespace));
    }
    @Test void updatesRetainOmittedSecretsAndRejectStaleWritesAndDeletes() throws Exception {
        Map<String, Object> first = profiles.save("owner", parent, create("password", "secret", null, null));
        Map<String, Object> next = profiles.save("owner", parent, update(first, "ssh.internal", "password", null, null, null));
        assertEquals(2L, version(next)); assertEquals(id(first), id(next));
        assertEquals("secret", profiles.resolveConnection("owner", parent, connect(next)).password());
        assertEquals(409, assertThrows(ApiException.class, () -> profiles.save("owner", parent, update(first, "stale.internal", "password", "stale", null, null))).getCode());
        assertThrows(ApiException.class, () -> profiles.delete("owner", parent, new SshProfileRequest("parent", id(first), version(first))));
        profiles.delete("owner", parent, new SshProfileRequest("parent", id(next), version(next)));
        assertTrue(profiles.list("owner", parent).isEmpty());
        assertEquals(404, assertThrows(ApiException.class, () -> profiles.get("owner", parent, id(next))).getCode());
    }
    @Test void retainsOrReplacesPrivateKeysAndAllowsExplicitPassphraseRemoval() throws Exception {
        Map<String, Object> first = profiles.save("owner", parent, create("privateKey", null, "fixture-private-key", "fixture-passphrase"));
        String stored = stored(first).getCredentials();
        assertFalse(stored.contains("fixture-private-key")); assertFalse(stored.contains("fixture-passphrase"));
        Map<String, Object> kept = profiles.save("owner", parent, update(first, "ssh.internal", "privateKey", null, null, null));
        assertEquals("fixture-private-key", profiles.resolveConnection("owner", parent, connect(kept)).privateKey());
        assertEquals("fixture-passphrase", profiles.resolveConnection("owner", parent, connect(kept)).passphrase());
        Map<String, Object> cleared = profiles.save("owner", parent, update(kept, "ssh.internal", "privateKey", null, "new-private-key", ""));
        assertEquals(false, cleared.get("hasPassphrase"));
        assertEquals("", profiles.resolveConnection("owner", parent, connect(cleared)).passphrase());
        assertThrows(ApiException.class, () -> profiles.save("owner", parent, update(cleared, "ssh.internal", "password", null, null, null)));
        Map<String, Object> password = profiles.save("owner", parent, update(cleared, "ssh.internal", "password", "new-password", null, null));
        assertEquals(false, password.get("hasPrivateKey")); assertEquals(false, password.get("hasPassphrase"));
        assertNull(profiles.resolveConnection("owner", parent, connect(password)).privateKey());
    }
    @Test void sharesProfilesAndCredentialsAcrossUsersAndHostsWithoutChangingTheCreator() throws Exception {
        var saved = profiles.save("owner", parent, create("password", "secret", null, null));
        AgentNodeSession peer = mock(AgentNodeSession.class);
        when(peer.getAgentId()).thenReturn("agent");
        when(peer.getSessionId()).thenReturn("peer-console");
        when(peer.getCurrentHostId()).thenReturn("other-host");
        assertEquals(id(saved), profiles.list("other", peer).get(0).get("profileId"));
        assertEquals(id(saved), profiles.get("other", peer, id(saved)).get("profileId"));
        assertPublic(profiles.getForConnection("other", peer, connect(saved)));
        assertEquals("secret", profiles.resolveConnection("other", peer, connect(saved)).password());
        profiles.recordFingerprint("other", peer, id(saved), version(saved), pin);
        var edited = profiles.save("other", peer, update(saved, "ssh.internal", "password", null, null, null));
        assertEquals("owner", stored(edited).getCreateUserId());
        assertEquals("renamed", profiles.get("owner", parent, id(saved)).get("name"));
        assertEquals("secret", profiles.resolveConnection("other", peer, connect(edited)).password());
        assertEquals(pin, edited.get("fingerprint"));
        assertEquals(409, assertThrows(ApiException.class, () -> profiles.save("owner", parent,
                update(saved, "ssh.internal", "password", "stale", null, null))).getCode());
        profiles.delete("other", peer, new SshProfileRequest("peer-console", id(edited), version(edited)));
        assertTrue(profiles.list("owner", parent).isEmpty());
    }
    @Test void isolatesDifferentAgentsForReadsUpdatesDeletesAndCredentialResolution() throws Exception {
        var saved = profiles.save("owner", parent, create("password", "secret", null, null));
        when(parent.getAgentId()).thenReturn("other-agent");
        assertTrue(profiles.list("owner", parent).isEmpty());
        assertThrows(ApiException.class, () -> profiles.get("owner", parent, id(saved)));
        assertThrows(ApiException.class, () -> profiles.getForConnection("owner", parent, connect(saved)));
        assertThrows(ApiException.class, () -> profiles.resolveConnection("owner", parent, connect(saved)));
        assertThrows(ApiException.class, () -> profiles.save("owner", parent,
                update(saved, "ssh.internal", "password", "changed", null, null)));
        assertThrows(ApiException.class, () -> profiles.delete("owner", parent,
                new SshProfileRequest("parent", id(saved), version(saved))));
        when(parent.getAgentId()).thenReturn("agent");
        assertEquals("secret", profiles.resolveConnection("owner", parent, connect(saved)).password());
    }
    @Test void changesOfEndpointInvalidatePinsAndLateConnectionsNeverResurrectDeletedProfiles() throws Exception {
        Map<String, Object> first = profiles.save("owner", parent, create("password", "secret", null, null));
        profiles.recordFingerprint("owner", parent, id(first), version(first), pin);
        Map<String, Object> pinned = profiles.get("owner", parent, id(first));
        assertEquals(pin, pinned.get("fingerprint"));
        Map<String, Object> changed = profiles.save("owner", parent, update(pinned, "different.internal", "password", null, null, null));
        assertEquals("", changed.get("fingerprint"));
        assertThrows(ApiException.class, () -> profiles.recordFingerprint("owner", parent, id(pinned), version(pinned), pin));
        profiles.delete("owner", parent, new SshProfileRequest("parent", id(changed), version(changed)));
        assertThrows(ApiException.class, () -> profiles.recordFingerprint("owner", parent, id(changed), version(changed), pin));
        assertTrue(profiles.list("owner", parent).isEmpty());
    }
    @Test void rejectsAnotherKeySwappedCiphertextAndMetadataTampering() throws Exception {
        Map<String, Object> one = profiles.save("owner", parent, create("password", "first-secret", null, null));
        Map<String, Object> two = profiles.save("owner", parent, create("password", "second-secret", null, null));
        SshConnectionProfiles wrongKey = new SshConnectionProfiles(openMapper(), new SshProfileCryptoService("different-key", "unused"));
        assertThrows(IllegalStateException.class, () -> wrongKey.resolveConnection("owner", parent, connect(one)));
        jdbc.update("UPDATE agent_ssh_connections SET credentials=? WHERE profile_id=?", stored(two).getCredentials(), id(one));
        assertThrows(IllegalStateException.class, () -> profiles.resolveConnection("owner", parent, connect(one)));
        jdbc.update("UPDATE agent_ssh_connections SET host='tampered.internal' WHERE profile_id=?", id(two));
        assertThrows(IllegalStateException.class, () -> profiles.resolveConnection("owner", parent, connect(two)));
        var moved = profiles.save("owner", parent, create("password", "agent-bound-secret", null, null));
        jdbc.update("INSERT INTO agents (agent_id, agent_name, parent_agent_id, create_by_user_id, conn_link, req_disguise_id, resp_disguise_id, create_time, update_time) VALUES ('other-agent', 'other', 'root', 'owner', '/', 'request', 'response', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("UPDATE agent_ssh_connections SET agent_id='other-agent' WHERE profile_id=?", id(moved));
        when(parent.getAgentId()).thenReturn("other-agent");
        assertEquals("SSH 凭据作用域不匹配", assertThrows(IllegalStateException.class,
                () -> profiles.resolveConnection("other", parent, connect(moved))).getMessage());
    }
    @Test void validatesIdsAndCapacityWithoutDroppingEarlierProfiles() throws Exception {
        assertThrows(ApiException.class, () -> profiles.get("owner", parent, "../outside"));
        Map<String, Object> first = profiles.save("owner", parent, create("password", "secret", null, null));
        for (int i = 1; i < 100; i++) profiles.save("owner", parent, create("password", "secret", null, null));
        assertEquals(429, assertThrows(ApiException.class, () -> profiles.save("owner", parent, create("password", "secret", null, null))).getCode());
        assertEquals(100, profiles.list("owner", parent).size());
        assertEquals(id(first), profiles.get("owner", parent, id(first)).get("profileId"));
        assertFalse(create("password", "secret", null, null).toString().contains("secret"));
    }
    @Test void reusesServerPinsAndOrdersRecentConnectionsWithoutChangingCredentialVersions() throws Exception {
        Map<String, Object> first = profiles.save("owner", parent, create("password", "secret", null, null));
        Map<String, Object> second = profiles.save("owner", parent, create("password", "secret", null, null));
        var request = new SshProfileConnectRequest("parent", "child", id(first), version(first), null).normalized();
        assertPublic(profiles.getForConnection("owner", parent, request));
        profiles.recordFingerprint("owner", parent, id(first), version(first), pin);
        Map<String, Object> pinned = profiles.get("owner", parent, id(first));
        assertEquals(version(first), version(pinned));
        assertTrue(((Number) pinned.get("lastConnectedAt")).longValue() > 0);
        assertEquals(id(first), profiles.list("owner", parent).get(0).get("profileId"));
        assertEquals(pin, profiles.resolveConnection("owner", parent, request).fingerprint());
        assertEquals(0L, ((Number) second.get("lastConnectedAt")).longValue());
        Map<String, Object> updated = profiles.save("owner", parent, update(pinned, "ssh.internal", "password", null, null, null));
        assertEquals(pinned.get("lastConnectedAt"), updated.get("lastConnectedAt"));
        assertThrows(ApiException.class, () -> profiles.getForConnection("owner", parent, request));
    }
    @Test void conditionalWritesPreserveNewerConnectionStateAndRejectRacingOrCrossScopeMutations() throws Exception {
        var saved = profiles.save("owner", parent, create("password", "secret", null, null));
        var first = stored(saved); var stale = stored(saved);
        String newestPin = "SHA256:" + "B".repeat(43);
        profiles.recordFingerprint("owner", parent, id(saved), 1L, newestPin);
        long connectedAt = stored(saved).getLastConnectedAt();
        first.setName("first editor");
        assertEquals(1, mapper.updateIfVersion(first, 1L));
        stale.setName("stale editor");
        assertEquals(0, mapper.updateIfVersion(stale, 1L));
        var actual = profiles.get("owner", parent, id(saved));
        assertEquals("first editor", actual.get("name"));
        assertEquals(2L, version(actual));
        assertEquals(newestPin, actual.get("fingerprint"));
        assertEquals(connectedAt, actual.get("lastConnectedAt"));
        assertEquals(0, mapper.recordConnectionIfVersion("agent", id(saved), 1L, pin, connectedAt + 1));
        assertEquals(0, mapper.deleteIfVersion("agent", id(saved), 1L));
        assertEquals(0, mapper.deleteIfVersion("other-agent", id(saved), 2L));
        assertEquals(0, mapper.recordConnectionIfVersion("other-agent", id(saved), 2L, pin, connectedAt + 1));
        assertNull(mapper.selectById("other-agent", id(saved)));
    }
    @Test void concurrentCreatesRespectTheDatabaseCapacityLimitAcrossIndependentConnections() throws Exception {
        for (int i = 0; i < 99; i++) profiles.save("owner", parent, create("password", "secret", null, null));
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        java.util.function.Function<String, Integer> save = user -> {
            try (SqlSession session = factory.openSession(true)) {
                var service = new SshConnectionProfiles(session.getMapper(AgentSshConnectionMapper.class), crypto());
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { service.save(user, parent, create("password", "secret", null, null)); return 200; }
                catch (ApiException error) { return error.getCode(); }
            } catch (Exception error) { throw new RuntimeException(error); }
        };
        var first = CompletableFuture.supplyAsync(() -> save.apply("owner")); var second = CompletableFuture.supplyAsync(() -> save.apply("other"));
        assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
        assertEquals(Set.of(200, 429), Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
        assertEquals(100, profiles.list("owner", parent).size());
    }
    @Test void deletingTheCreatorKeepsSharedProfilesWhileDeletingTheAgentRemovesThem() throws Exception {
        profiles.save("owner", parent, create("password", "secret", null, null));
        profiles.save("other", parent, create("password", "other-secret", null, null));
        jdbc.update("DELETE FROM users WHERE user_id='owner'");
        assertEquals(2, profiles.list("other", parent).size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_ssh_connections WHERE create_user_id='owner'", Integer.class));
        jdbc.update("DELETE FROM agents WHERE agent_id='agent'");
        assertTrue(profiles.list("other", parent).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_ssh_connections", Integer.class));
    }
    @Test void aNewConsoleAndDifferentHostReuseAgentProfilesAfterRestart() throws Exception {
        when(parent.getCurrentHostId()).thenReturn(null);
        var saved = profiles.save("owner", parent, create("password", "secret", null, null));
        when(parent.getSessionId()).thenReturn("another-console");
        when(parent.getCurrentHostId()).thenReturn("another-host");
        assertEquals(id(saved), restarted().list("other", parent).get(0).get("profileId"));
        assertEquals("owner", jdbc.queryForObject("SELECT create_user_id FROM agent_ssh_connections WHERE profile_id=?", String.class, id(saved)));
    }
}
