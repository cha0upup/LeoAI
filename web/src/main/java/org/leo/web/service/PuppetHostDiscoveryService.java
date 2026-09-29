package org.leo.web.service;

import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.repository.session.PuppetHostCacheRepository;
import org.leo.core.rpc.PuppetRpcEnvelopeMapper;
import org.leo.service.puppetnode.PuppetNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Discovers the backend instances hidden behind one load-balanced session endpoint. */
@Service
public class PuppetHostDiscoveryService {

    static final int DEFAULT_PROBE_COUNT = 8;
    static final int DEFAULT_PROBE_CONCURRENCY = 3;
    static final long DEFAULT_PROBE_TIMEOUT_MILLIS = 1_500L;
    private static final Logger logger = LoggerFactory.getLogger(PuppetHostDiscoveryService.class);
    private final PuppetNodeFactory puppetNodeFactory;
    private final PuppetHostCacheRepository repository;

    public record DiscoveryResult(List<String> hostIds, String discoveredAt, boolean reused) { }

    public PuppetHostDiscoveryService(PuppetNodeFactory puppetNodeFactory, PuppetHostCacheRepository repository) {
        this.puppetNodeFactory = puppetNodeFactory;
        this.repository = repository;
    }

    /** 按连接配置复用已登记 HostId；forceRefresh 仅由用户主动刷新时使用。 */
    public DiscoveryResult discover(Puppet puppet, User user, boolean forceRefresh) throws Exception {
        String fingerprint = connectionFingerprint(puppet);
        Map<String, Object> saved = load(puppet, user, fingerprint);
        if (!forceRefresh) {
            List<String> known = hostIdsFrom(saved);
            if (!known.isEmpty()) return result(known, saved, true);
        }
        LinkedHashSet<String> discovered = new LinkedHashSet<>();
        // 超时任务可能仍在底层网络库中收尾，使用有界线程数避免阻塞后续探测。
        ExecutorService executor = Executors.newFixedThreadPool(DEFAULT_PROBE_CONCURRENCY);
        try {
            for (int batchStart = 0; batchStart < DEFAULT_PROBE_COUNT;
                 batchStart += DEFAULT_PROBE_CONCURRENCY) {
                int batchSize = Math.min(DEFAULT_PROBE_CONCURRENCY,
                        DEFAULT_PROBE_COUNT - batchStart);
                CompletionService<ProbeOutcome> completions = new ExecutorCompletionService<>(executor);
                List<Future<ProbeOutcome>> futures = new java.util.ArrayList<>(batchSize);
                for (int offset = 0; offset < batchSize; offset++) {
                    int probe = batchStart + offset;
                    futures.add(completions.submit(() -> probeOutcome(probe, puppet, user)));
                }

                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(DEFAULT_PROBE_TIMEOUT_MILLIS);
                for (int completed = 0; completed < batchSize; completed++) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    Future<ProbeOutcome> future = completions.poll(remaining, TimeUnit.NANOSECONDS);
                    if (future == null) break;
                    try {
                        handleProbeOutcome(future.get(), discovered, puppet);
                    } catch (CancellationException ex) {
                        logger.debug("HostId 探测任务已取消, puppetId={}", puppet.getPuppetId());
                    } catch (ExecutionException ex) {
                        logger.debug("HostId 探测任务失败, puppetId={}", puppet.getPuppetId(), ex);
                    }
                }
                for (int offset = 0; offset < futures.size(); offset++) {
                    Future<ProbeOutcome> future = futures.get(offset);
                    if (!future.isDone()) {
                        future.cancel(true);
                        logger.debug("HostId 探测超时, puppetId={}, probe={}, timeoutMs={}",
                                puppet.getPuppetId(), batchStart + offset + 1,
                                DEFAULT_PROBE_TIMEOUT_MILLIS);
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.debug("HostId 探测被中断, puppetId={}", puppet.getPuppetId());
        } finally {
            executor.shutdownNow();
        }
        List<String> result = List.copyOf(discovered);
        repository.saveHostDiscovery(
                user != null ? user.getUserId() : null, puppet.getPuppetId(), fingerprint, result);
        return result(result, load(puppet, user, fingerprint), false);
    }

    private Map<String, Object> probeOnce(Puppet puppet, User user) throws Exception {
        AbstractPuppetNode probeNode = null;
        try {
            // 每次使用全新的连接，避免负载均衡的连接/Cookie 亲和性固定到同一后端。
            probeNode = puppetNodeFactory.createLiveNode(puppet, user);
            return probeNode.testConnection();
        } finally {
            if (probeNode != null) {
                try { probeNode.close(); } catch (Exception ignored) { }
            }
        }
    }

    private ProbeOutcome probeOutcome(int probe, Puppet puppet, User user) {
        try {
            return new ProbeOutcome(probe, probeOnce(puppet, user), null);
        } catch (Exception ex) {
            return new ProbeOutcome(probe, null, ex);
        }
    }

    private void handleProbeOutcome(ProbeOutcome outcome,
                                    LinkedHashSet<String> discovered, Puppet puppet) {
        if (outcome.error() != null) {
            logger.debug("HostId 探测失败, puppetId={}, probe={}, type={}, message={}",
                    puppet.getPuppetId(), outcome.probe() + 1,
                    outcome.error().getClass().getName(), outcome.error().getMessage());
            return;
        }
        Map<String, Object> result = outcome.result();
        if (!PuppetRpcEnvelopeMapper.isCurrentPingResult(result)) {
            logger.debug("HostId 探测未成功, puppetId={}, probe={}, code={}",
                    puppet.getPuppetId(), outcome.probe() + 1,
                    result == null ? null : result.get("code"));
            return;
        }
        discovered.add(((String) result.get("hostId")).trim());
    }

    private record ProbeOutcome(int probe, Map<String, Object> result, Throwable error) { }

    private Map<String, Object> load(Puppet puppet, User user, String fingerprint) {
        return repository.loadHostDiscovery(
                user != null ? user.getUserId() : null, puppet.getPuppetId(), fingerprint);
    }

    private DiscoveryResult result(List<String> hostIds, Map<String, Object> details, boolean reused) {
        Object discoveredAt = details == null ? null : details.get("discoveredAt");
        return new DiscoveryResult(hostIds, discoveredAt == null ? null : String.valueOf(discoveredAt), reused);
    }

    private List<String> hostIdsFrom(Map<String, Object> data) {
        if (data == null || !(data.get("hostIds") instanceof Collection<?> raw)) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        raw.forEach(value -> { String id = normalized(value); if (id != null) ids.add(id); });
        return List.copyOf(ids);
    }

    private String connectionFingerprint(Puppet puppet) {
        String source = String.valueOf(puppet.getConnLink()) + "|" + String.valueOf(puppet.getProtocol())
                + "|" + String.valueOf(puppet.getHeaders()) + "|" + String.valueOf(puppet.getProxyType())
                + "|" + String.valueOf(puppet.getProxyHost()) + "|" + String.valueOf(puppet.getProxyPort());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception e) { return source; }
    }

    private String normalized(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

}
