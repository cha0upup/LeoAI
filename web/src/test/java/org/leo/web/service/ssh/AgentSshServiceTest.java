package org.leo.web.service.ssh;

import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.leo.core.component.ProxyForwardComponent;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.leo.dao.mapper.AgentSshConnectionMapper;
import org.leo.web.testing.SqliteTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.web.dto.agentnode.command.CommandExecRequest;
import org.leo.web.dto.agentnode.command.SshConnectRequest;
import org.leo.web.dto.agentnode.command.SshProfileSaveRequest;
import org.leo.web.dto.agentnode.command.SshProfileConnectRequest;
import org.leo.web.dto.agentnode.command.SshProfileRequest;
import org.leo.web.exception.ApiException;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSshServiceTest {
    @TempDir Path temp;
    SshServer server;
    AgentSshService ssh;
    AgentNodeSession parent;
    ComponentInvokeCapable node;
    KeyPair identity;
    String fingerprint;
    AtomicInteger opens, closes, resizes, passwordAttempts;

    @BeforeEach void start() throws Exception {
        opens = new AtomicInteger(); closes = new AtomicInteger(); resizes = new AtomicInteger(); passwordAttempts = new AtomicInteger();
        identity = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        server = SshServer.setUpDefaultServer(); server.setHost("127.0.0.1"); server.setPort(0);
        var hostKeys = new SimpleGeneratorHostKeyProvider(temp.resolve("hostkey"));
        hostKeys.setAlgorithm("RSA"); hostKeys.setKeySize(2048);
        server.setKeyPairProvider(hostKeys);
        server.setPasswordAuthenticator((user, password, session) -> {
            passwordAttempts.incrementAndGet();
            return user.equals("fixture") && password.equals("fixture-password");
        });
        server.setPublickeyAuthenticator((user, key, session) -> user.equals("fixture") && key.equals(identity.getPublic()));
        server.setShellFactory(channel -> new EchoShell()); server.start();
        fingerprint = AgentSshService.fingerprint(hostKeys.loadKeys(null).iterator().next().getPublic());
        assertEquals(org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(
                org.apache.sshd.common.digest.BuiltinDigests.sha256, hostKeys.loadKeys(null).iterator().next().getPublic()), fingerprint);
        parent = mock(AgentNodeSession.class);
        when(parent.getSessionId()).thenReturn("ssh-parent"); when(parent.getAgentId()).thenReturn("agent");
        when(parent.getCurrentHostId()).thenReturn("machine");
        AgentNodeSessionContainer.addSession("ssh-parent", parent);
        ssh = new AgentSshService(); ssh.register();
        node = (component, params) -> {
            assertEquals("ProxyForwardComponent", component);
            var copy = new HashMap<>(params);
            if (Integer.valueOf(0).equals(copy.get("op"))) {
                assertEquals("agent-only.invalid", copy.get("targetHost")); opens.incrementAndGet();
                // DNS substitution exists only inside this synthetic Agent fixture.
                copy.put("targetHost", "127.0.0.1");
            } else if (Integer.valueOf(3).equals(copy.get("op"))) closes.incrementAndGet();
            var proxy = new ProxyForwardComponent();
            var input = ProxyForwardComponent.class.getDeclaredField("params"); input.setAccessible(true); input.set(proxy, copy);
            var output = ProxyForwardComponent.class.getDeclaredField("results"); output.setAccessible(true);
            HashMap<String, Object> result = new HashMap<>(); output.set(proxy, result); proxy.invoke(); return result;
        };
    }
    @AfterEach void stop() throws Exception {
        if (ssh != null) ssh.close();
        AgentNodeSessionContainer.removeSession("ssh-parent");
        if (server != null) server.stop(true);
    }
    SshConnectRequest request(String process, String password, String key, String pin) {
        return new SshConnectRequest(process, "agent-only.invalid", server.getPort(), "fixture",
                key == null ? "password" : "privateKey", password, key, null, pin).normalized();
    }
    Map<String, Object> command(String process, String type, String cmd) throws Exception {
        return ssh.command("owner", "browser", parent, CommandExecRequest.normalize(
                new CommandExecRequest("ssh-parent", cmd, type, process)));
    }
    String read(String process) throws Exception {
        Map<String, Object> result = command(process, "read", "1000");
        assertTrue(((Number) result.get("outputSequence")).longValue() > 0);
        return new String(Base64.getDecoder().decode((String) result.get("data")), StandardCharsets.UTF_8);
    }
    String readUntil(String process, String expected) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!output.toString(StandardCharsets.UTF_8).contains(expected) && System.nanoTime() < deadline) {
            output.write(Base64.getDecoder().decode((String) command(process, "read", "1000").get("data")));
        }
        return output.toString(StandardCharsets.UTF_8);
    }
    @Test void authenticatesThroughAgentAndSupportsUtf8ResizeControlBytesAndIndependentClose() throws Exception {
        assertEquals(fingerprint, ssh.probeFingerprint("owner", "browser", parent, node, "agent-only.invalid", server.getPort()));
        assertEquals(0, passwordAttempts.get());
        ssh.connect("owner", "browser", parent, node, request("one", "fixture-password", null, fingerprint));
        ssh.connect("owner", "browser", parent, node, request("two", "fixture-password", null, fingerprint));
        assertEquals("ssh", command("one", "init", "").get("backend"));
        assertTrue(readUntil("one", "终端就绪").contains("终端就绪"));
        command("one", "write", "中文\r\u0003");
        assertTrue(readUntil("one", "中文\r\u0003").contains("中文\r\u0003"));
        command("one", "resize", "120,40");
        for (int i = 0; i < 100 && resizes.get() == 0; i++) Thread.sleep(10);
        assertTrue(resizes.get() > 0);
        command("one", "stop", ""); command("one", "stop", "");
        assertTrue(read("two").contains("终端就绪"));
        assertEquals(3, opens.get());
    }
    @Test void cancelsAnOpenRacingWithBrowserLogoutAndCompensatesTheLateRemoteSocket() throws Exception {
        CountDownLatch opening = new CountDownLatch(1), finish = new CountDownLatch(1);
        ComponentInvokeCapable delayed = (component, params) -> {
            if (Integer.valueOf(0).equals(params.get("op"))) { opening.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)); }
            return node.invokeComponent(component, params);
        };
        CompletableFuture<Exception> pending = CompletableFuture.supplyAsync(() -> {
            try { ssh.connect("owner", "browser", parent, delayed, request("late", "fixture-password", null, fingerprint)); return null; }
            catch (Exception error) { return error; }
        });
        try {
            assertTrue(opening.await(5, TimeUnit.SECONDS));
            ssh.closeBrowser("browser");
        } finally { finish.countDown(); }
        assertNotNull(pending.get(5, TimeUnit.SECONDS));
        for (int i = 0; i < 100 && closes.get() == 0; i++) Thread.sleep(10);
        assertEquals(1, opens.get()); assertEquals(1, closes.get());
        assertThrows(ApiException.class, () -> read("late"));
    }
    @Test void drainsFinalOutputBeforeReportingEofAndRetainsEndedStateUntilStop() throws Exception {
        ssh.connect("owner", "browser", parent, node, request("eof", "fixture-password", null, fingerprint));
        readUntil("eof", "终端就绪");
        command("eof", "write", "\u0004");
        StringBuilder output = new StringBuilder();
        Map<String, Object> result = null;
        for (int i = 0; i < 10; i++) {
            result = command("eof", "read", "1000");
            output.append(new String(Base64.getDecoder().decode((String) result.get("data")), StandardCharsets.UTF_8));
            if (Boolean.TRUE.equals(result.get("eof"))) break;
        }
        assertTrue(output.toString().contains("fixture finished"));
        assertEquals(true, result.get("eof")); assertEquals(false, result.get("alive"));
        assertEquals(false, command("eof", "init", "").get("alive"));
        command("eof", "stop", "");
    }
    @Test void streamsOutputLargerThanTheRelayPipeInBoundedOrderedChunks() throws Exception {
        ssh.connect("owner", "browser", parent, node, request("bulk", "fixture-password", null, fingerprint));
        readUntil("bulk", "终端就绪");
        String expected = "terminal fixture ".repeat(32768);
        command("bulk", "write", expected);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        long sequence = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (output.size() < expected.length() && System.nanoTime() < deadline) {
            var result = command("bulk", "read", "1000");
            byte[] bytes = Base64.getDecoder().decode((String) result.get("data"));
            assertTrue(bytes.length <= 64 * 1024); output.write(bytes);
            long next = ((Number) result.get("outputSequence")).longValue();
            assertTrue(next > sequence); sequence = next;
        }
        assertEquals(expected, output.toString(StandardCharsets.UTF_8));
    }
    @Test void attachesOrderedOutputToInitializationAndWrites() throws Exception {
        ssh.connect("owner", "browser", parent, node, request("attached", "fixture-password", null, fingerprint));
        Map<String, Object> initialized = ssh.command("owner", "browser", parent,
                CommandExecRequest.normalize(new CommandExecRequest("ssh-parent", "", "init", "attached", null, true)));
        Map<?, ?> first = (Map<?, ?>) initialized.get("output");
        assertEquals(initialized.get("instanceId"), first.get("instanceId"));
        assertEquals(1L, first.get("outputSequence")); assertEquals(false, first.get("eof"));
        Map<String, Object> written = ssh.command("owner", "browser", parent,
                CommandExecRequest.normalize(new CommandExecRequest("ssh-parent", "fixture message\r", "write", "attached", null, true)));
        Map<?, ?> second = (Map<?, ?>) written.get("output");
        assertEquals(2L, second.get("outputSequence")); assertEquals("ssh", second.get("backend"));
        assertNotNull(Base64.getDecoder().decode((String) second.get("data")));
        assertFalse(command("attached", "init", "").containsKey("output"));
    }
    @Test void supportsInMemoryPrivateKeysAndRejectsWrongCredentialsAndChangedPins() throws Exception {
        String key = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10})
                .encodeToString(identity.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        ssh.connect("owner", "browser", parent, node, request("key", null, key, fingerprint));
        assertTrue(read("key").contains("终端就绪"));
        assertThrows(Exception.class, () -> ssh.connect("owner", "browser", parent, node,
                request("wrong-password", "wrong", null, fingerprint)));
        int beforeMismatch = passwordAttempts.get();
        var mismatch = assertThrows(AgentSshService.HostKeyChangedException.class, () -> ssh.connect("owner", "browser", parent, node,
                request("wrong-pin", "fixture-password", null, "SHA256:" + "A".repeat(43))));
        assertEquals(fingerprint, mismatch.fingerprint());
        assertEquals(beforeMismatch, passwordAttempts.get());
    }
    @Test void isolatesOwnersBrowsersAndHostBindingsAndClosesOnParentAndBrowserEnd() throws Exception {
        ssh.connect("owner", "browser", parent, node, request("one", "fixture-password", null, fingerprint));
        var read = new CommandExecRequest("ssh-parent", "", "read", "one");
        assertThrows(ApiException.class, () -> ssh.command("other", "browser", parent, read));
        assertThrows(ApiException.class, () -> ssh.command("owner", "other-browser", parent, read));
        ssh.closeBrowser("other-browser"); assertEquals("ssh", command("one", "init", "").get("backend"));
        ssh.closeBrowser("browser"); assertThrows(ApiException.class, () -> read("one"));
        ssh.connect("owner", "browser", parent, node, request("two", "fixture-password", null, fingerprint));
        when(parent.getCurrentHostId()).thenReturn("different-machine");
        assertThrows(ApiException.class, () -> read("two"));
        when(parent.getCurrentHostId()).thenReturn("machine");
        ssh.connect("owner", "browser", parent, node, request("three", "fixture-password", null, fingerprint));
        AgentNodeSessionContainer.removeSession("ssh-parent");
        assertThrows(ApiException.class, () -> read("three"));
    }
    @Test void reusesEncryptedSavedCredentialsAndDeletingAProfileDoesNotCloseItsTerminal() throws Exception {
        var source = SqliteTestDatabase.create(temp.resolve("profiles.db"));
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO users (user_id, user_name, password, create_time, update_time) VALUES ('owner', 'owner', 'hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO agents (agent_id, agent_name, parent_agent_id, create_by_user_id, conn_link, req_disguise_id, resp_disguise_id, create_time, update_time) VALUES ('agent', 'agent', 'root', 'owner', '/', 'request', 'response', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        var configuration = SqliteTestDatabase.myBatisConfiguration(source);
        configuration.addMapper(AgentSshConnectionMapper.class);
        try (var database = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var profiles = new SshConnectionProfiles(database.getMapper(AgentSshConnectionMapper.class),
                    new SshProfileCryptoService("", temp.resolve("ssh.key").toString()));
            for (String auth : java.util.List.of("password", "privateKey")) {
                String key = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10})
                        .encodeToString(identity.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
                Map<String, Object> saved = profiles.save("owner", parent, new SshProfileSaveRequest("ssh-parent", null, null,
                        auth, "agent-only.invalid", server.getPort(), "fixture", auth,
                        auth.equals("password") ? "fixture-password" : null, auth.equals("privateKey") ? key : null, null));
                String id = (String) saved.get("profileId"); Long version = ((Number) saved.get("version")).longValue();
                var connection = profiles.resolveConnection("owner", parent,
                        new SshProfileConnectRequest("ssh-parent", auth, id, version, fingerprint));
                ssh.connect("owner", "browser", parent, node, connection);
                assertTrue(readUntil(auth, "终端就绪").contains("终端就绪"));
                profiles.delete("owner", parent, new SshProfileRequest("ssh-parent", id, version));
                assertEquals(true, command(auth, "init", "").get("alive"));
            }
        }
    }
    final class EchoShell implements Command {
        InputStream input; OutputStream output; ExitCallback exit; Thread worker;
        @Override public void setInputStream(InputStream input) { this.input = input; }
        @Override public void setOutputStream(OutputStream output) { this.output = output; }
        @Override public void setErrorStream(OutputStream error) { }
        @Override public void setExitCallback(ExitCallback exit) { this.exit = exit; }
        @Override public void start(ChannelSession channel, Environment environment) {
            environment.addSignalListener((ch, signal) -> resizes.incrementAndGet(), Signal.WINCH);
            worker = new Thread(() -> {
                try {
                    output.write("终端就绪\r\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                    byte[] bytes = new byte[8192]; int n;
                    while ((n = input.read(bytes)) >= 0) {
                        if (n == 1 && bytes[0] == 4) { output.write("fixture finished\r\n".getBytes(StandardCharsets.UTF_8)); output.flush(); break; }
                        output.write(bytes, 0, n); output.flush();
                    }
                } catch (Exception ignored) { }
                finally { exit.onExit(0); }
            }); worker.setDaemon(true); worker.start();
        }
        @Override public void destroy(ChannelSession channel) { if (worker != null) worker.interrupt(); }
    }
}
