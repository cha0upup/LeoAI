package org.leo.web.service.ssh;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.keepalive.KeepAliveProvider;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.userauth.password.PasswordUtils;
import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.leo.core.engine.proxy.AgentRelaySocket;
import org.leo.core.engine.proxy.ProxySessionResources;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.web.dto.agentnode.command.CommandExecRequest;
import org.leo.web.dto.agentnode.command.SshConnectRequest;
import org.leo.web.exception.ApiException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.net.SocketFactory;
import java.net.InetAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Authenticated SSH child sessions; transport always traverses the selected Agent. */
@Service
public class AgentSshService {
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final ProxySessionResources relayResources = new ProxySessionResources(64, 4);
    private final ThreadPoolExecutor cleanup = ProxySessionResources.newDaemonExecutor("SSH-Cleanup", 4, 128);
    private final Consumer<String> parentClosed = id -> entries.values().stream()
            .filter(entry -> entry.parent.getSessionId().equals(id)).toList().forEach(this::remove);
    private volatile boolean stopped;
    @PostConstruct void register() { AgentNodeSessionContainer.registerDestroyListener(parentClosed); }
    @PreDestroy public synchronized void close() {
        stopped = true;
        AgentNodeSessionContainer.unregisterDestroyListener(parentClosed);
        entries.values().forEach(this::remove);
        relayResources.close(); cleanup.shutdown();
    }
    static String fingerprint(PublicKey key) {
        try {
            byte[] encoded = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (Exception error) { throw new IllegalStateException("SSH 指纹计算失败", error); }
    }
    private static String key(String browser, String parent, String process) { return browser + ":" + parent + ":" + process; }
    private synchronized Entry reserve(String user, String browser, AgentNodeSession parent, String process) {
        if (stopped) throw ApiException.serviceUnavailable("SSH 服务正在关闭");
        if (entries.size() >= 64 || entries.values().stream().filter(e -> e.parent == parent).count() >= 8)
            throw ApiException.tooManyRequests("SSH 会话已达上限，请先关闭闲置终端");
        String key = key(browser, parent.getSessionId(), process);
        Entry entry = new Entry(key, user, browser, parent);
        if (entries.putIfAbsent(key, entry) != null) throw ApiException.conflict("SSH 终端标识已存在");
        return entry;
    }
    private SSHClient establish(Entry entry, ComponentInvokeCapable node, String host, int port,
                                Predicate<String> verifyFingerprint) throws Exception {
        DefaultConfig config = new DefaultConfig();
        config.setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE);
        SSHClient client = new SSHClient(config);
        // SSHJ starts its keep-alive thread during connect(), not during authentication.
        client.getConnection().getKeepAlive().setKeepAliveInterval(10);
        synchronized (entry) { if (entry.closed) { client.close(); throw ApiException.conflict("SSH 连接已取消"); } entry.client = client; }
        AgentRelaySocket socket = AgentRelaySocket.open(node, host, port, relayResources, cleanup);
        synchronized (entry) { if (entry.closed) { socket.close(); throw ApiException.conflict("SSH 连接已取消"); } entry.socket = socket; }
        client.setSocketFactory(new SocketFactory() {
            @Override public Socket createSocket() { return socket; }
            @Override public Socket createSocket(String host, int port) { return socket; }
            @Override public Socket createSocket(String h, int p, InetAddress a, int lp) { return socket; }
            @Override public Socket createSocket(InetAddress h, int p) { return socket; }
            @Override public Socket createSocket(InetAddress h, int p, InetAddress a, int lp) { return socket; }
        });
        client.setTimeout(15000);
        client.addHostKeyVerifier(new net.schmizz.sshj.transport.verification.HostKeyVerifier() {
            @Override public boolean verify(String remoteHost, int remotePort, PublicKey key) {
                return host.equals(remoteHost) && port == remotePort && verifyFingerprint.test(fingerprint(key));
            }
            @Override public java.util.List<String> findExistingAlgorithms(String host, int port) { return java.util.List.of(); }
        });
        client.connect(host, port);
        return client;
    }
    public String probeFingerprint(String user, String browser, AgentNodeSession parent,
                                   ComponentInvokeCapable node, String host, int port) throws Exception {
        Entry entry = reserve(user, browser, parent, "probe-" + UUID.randomUUID());
        var observed = new AtomicReference<String>();
        try {
            // Probe completes key exchange only; credentials are never sent before explicit pin confirmation.
            establish(entry, node, host, port, fp -> { observed.set(fp); return true; });
            requireParent(entry);
            return observed.get();
        } finally { remove(entry); }
    }
    public void connect(String user, String browser, AgentNodeSession parent, ComponentInvokeCapable node,
                        SshConnectRequest request) throws Exception {
        Entry entry = reserve(user, browser, parent, request.processId());
        var observed = new AtomicReference<String>();
        try {
            String expected = request.fingerprint();
            SSHClient client = establish(entry, node, request.host(), request.port(), fp -> {
                observed.set(fp);
                return fp.equals(expected);
            });
            if ("privateKey".equals(request.authType())) {
                char[] passphrase = request.passphrase() == null ? new char[0] : request.passphrase().toCharArray();
                try { client.authPublickey(request.username(), client.loadKeys(request.privateKey(), null,
                        PasswordUtils.createOneOff(passphrase))); }
                finally { java.util.Arrays.fill(passphrase, '\0'); }
            } else {
                char[] password = request.password().toCharArray();
                try { client.authPassword(request.username(), password); }
                finally { java.util.Arrays.fill(password, '\0'); }
            }
            synchronized (entry) { requireParent(entry); entry.socket.setSoTimeout(0); }
            Session channel = client.startSession();
            channel.allocatePTY("xterm-256color", 80, 24, 0, 0, Map.of());
            Session.Shell shell = channel.startShell();
            synchronized (entry) {
                requireParent(entry);
                entry.terminal = new SshTerminalSession(shell, UUID.randomUUID().toString(), () -> release(entry));
                entry.terminal.start();
            }
        } catch (Exception error) {
            remove(entry);
            if (observed.get() != null && !observed.get().equals(request.fingerprint()))
                throw new HostKeyChangedException(observed.get());
            throw error;
        } catch (Error error) { remove(entry); throw error; }
    }
    public static final class HostKeyChangedException extends Exception {
        private final String fingerprint;
        HostKeyChangedException(String fingerprint) { super("SSH 主机指纹校验未通过"); this.fingerprint = fingerprint; }
        public String fingerprint() { return fingerprint; }
    }
    public Map<String, Object> command(String user, String browser, AgentNodeSession parent, CommandExecRequest request) throws Exception {
        Entry entry = entries.get(key(browser, parent.getSessionId(), request.processId()));
        if (entry == null) {
            if ("stop".equals(request.type())) return Map.of("code", 200);
            throw ApiException.notFound("SSH 终端已关闭或过期，请新建连接");
        }
        if (!entry.user.equals(user) || entry.parent != parent) throw ApiException.forbidden("SSH 会话不属于当前用户或 Agent");
        requireParent(entry);
        entry.lastAccess = System.currentTimeMillis();
        if ("stop".equals(request.type())) { remove(entry); return Map.of("code", 200); }
        SshTerminalSession terminal = entry.terminal;
        if (terminal == null) throw ApiException.conflict("SSH 仍在连接中");
        if (request.terminalMode() != null || "write-line".equals(request.type())) throw ApiException.badRequest("SSH 终端使用 PTY 字节流");
        return switch (request.type()) {
            case "read" -> terminal.read(request.cmd().isEmpty() ? 0 : Long.parseLong(request.cmd()));
            case "init" -> terminal.response(request.includeOutput());
            case "write" -> {
                terminal.write(request.cmd());
                yield terminal.response(request.includeOutput());
            }
            case "resize" -> { terminal.resize(request.cmd()); yield terminal.metadata(); }
            default -> throw ApiException.badRequest("SSH 终端操作无效");
        };
    }
    private void requireParent(Entry entry) {
        if (entry.closed || AgentNodeSessionContainer.getSession(entry.parent.getSessionId()) != entry.parent
                || !Objects.equals(entry.host, entry.parent.getCurrentHostId())) {
            remove(entry); throw ApiException.conflict("父 Agent 会话已变更，请重新连接 SSH");
        }
        entry.parent.touchLastActiveTime();
    }
    public void closeBrowser(String browser) { entries.values().stream().filter(e -> e.browser.equals(browser)).toList().forEach(this::remove); }
    @Scheduled(fixedDelay = 60000) public void expire() {
        long cutoff = System.currentTimeMillis() - 15 * 60_000L;
        entries.values().stream().filter(e -> e.lastAccess < cutoff).toList().forEach(this::remove);
    }
    private void remove(Entry entry) {
        synchronized (entry) { entry.closed = true; if (entry.terminal != null) entry.terminal.close(); }
        entries.remove(entry.key, entry); release(entry);
    }
    private void release(Entry entry) {
        SSHClient client;
        synchronized (entry) {
            if (entry.socket != null) { entry.socket.close(); entry.socket = null; }
            client = entry.client; entry.client = null;
        }
        if (client != null) {
            Runnable closeClient = () -> { try { client.close(); } catch (Exception ignored) { } };
            try { cleanup.execute(closeClient); }
            catch (RejectedExecutionException ignored) { closeClient.run(); }
        }
    }
    private static final class Entry {
        final String key, user, browser, host;
        final AgentNodeSession parent;
        volatile long lastAccess = System.currentTimeMillis();
        volatile boolean closed;
        SSHClient client;
        AgentRelaySocket socket;
        volatile SshTerminalSession terminal;
        Entry(String key, String user, String browser, AgentNodeSession parent) {
            this.key = key; this.user = user; this.browser = browser; this.parent = parent; host = parent.getCurrentHostId();
        }
    }
}
