package org.leo.web.service;

import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.repository.session.AgentHostCacheRepository;
import org.leo.core.rpc.AgentRpcEnvelopeMapper;
import org.leo.service.agentnode.AgentNodeFactory;
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
public class AgentHostDiscoveryService {

    static final int DEFAULT_PROBE_COUNT = 8;
    static final int DEFAULT_PROBE_CONCURRENCY = 3;
    static final long DEFAULT_PROBE_TIMEOUT_MILLIS = 1_500L;
    private static final Logger logger = LoggerFactory.getLogger(AgentHostDiscoveryService.class);
    private final AgentNodeFactory agentNodeFactory;
    private final AgentHostCacheRepository repository;

    public record DiscoveryResult(List<String> hostIds, String discoveredAt, boolean reused) { }

    public AgentHostDiscoveryService(AgentNodeFactory agentNodeFactory, AgentHostCacheRepository repository) {
        this.agentNodeFactory = agentNodeFactory;
        this.repository = repository;
    }

    /** 按连接配置复用已登记 HostId；forceRefresh 仅由用户主动刷新时使用。 */
    public DiscoveryResult discover(Agent agent, User user, boolean forceRefresh) throws Exception {
        String fingerprint = connectionFingerprint(agent);
        Map<String, Object> saved = load(agent, user, fingerprint);
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
                    futures.add(completions.submit(() -> probeOutcome(probe, agent, user)));
                }

                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(DEFAULT_PROBE_TIMEOUT_MILLIS);
                for (int completed = 0; completed < batchSize; completed++) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    Future<ProbeOutcome> future = completions.poll(remaining, TimeUnit.NANOSECONDS);
                    if (future == null) break;
                    try {
                        handleProbeOutcome(future.get(), discovered, agent);
                    } catch (CancellationException ex) {
                        logger.debug("HostId 探测任务已取消, agentId={}", agent.getAgentId());
                    } catch (ExecutionException ex) {
                        logger.debug("HostId 探测任务失败, agentId={}", agent.getAgentId(), ex);
                    }
                }
                for (int offset = 0; offset < futures.size(); offset++) {
                    Future<ProbeOutcome> future = futures.get(offset);
                    if (!future.isDone()) {
                        future.cancel(true);
                        logger.debug("HostId 探测超时, agentId={}, probe={}, timeoutMs={}",
                                agent.getAgentId(), batchStart + offset + 1,
                                DEFAULT_PROBE_TIMEOUT_MILLIS);
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.debug("HostId 探测被中断, agentId={}", agent.getAgentId());
        } finally {
            executor.shutdownNow();
        }
        List<String> result = List.copyOf(discovered);
        repository.saveHostDiscovery(
                user != null ? user.getUserId() : null, agent.getAgentId(), fingerprint, result);
        return result(result, load(agent, user, fingerprint), false);
    }

    private Map<String, Object> probeOnce(Agent agent, User user) throws Exception {
        AbstractAgentNode probeNode = null;
        try {
            // 每次使用全新的连接，避免负载均衡的连接/Cookie 亲和性固定到同一后端。
            probeNode = agentNodeFactory.createLiveNode(agent, user);
            return probeNode.testConnection();
        } finally {
            if (probeNode != null) {
                try { probeNode.close(); } catch (Exception ignored) { }
            }
        }
    }

    private ProbeOutcome probeOutcome(int probe, Agent agent, User user) {
        try {
            return new ProbeOutcome(probe, probeOnce(agent, user), null);
        } catch (Exception ex) {
            return new ProbeOutcome(probe, null, ex);
        }
    }

    private void handleProbeOutcome(ProbeOutcome outcome,
                                    LinkedHashSet<String> discovered, Agent agent) {
        if (outcome.error() != null) {
            logger.debug("HostId 探测失败, agentId={}, probe={}, type={}, message={}",
                    agent.getAgentId(), outcome.probe() + 1,
                    outcome.error().getClass().getName(), outcome.error().getMessage());
            return;
        }
        Map<String, Object> result = outcome.result();
        if (!AgentRpcEnvelopeMapper.isCurrentPingResult(result)) {
            logger.debug("HostId 探测未成功, agentId={}, probe={}, code={}",
                    agent.getAgentId(), outcome.probe() + 1,
                    result == null ? null : result.get("code"));
            return;
        }
        discovered.add(((String) result.get("hostId")).trim());
    }

    private record ProbeOutcome(int probe, Map<String, Object> result, Throwable error) { }

    private Map<String, Object> load(Agent agent, User user, String fingerprint) {
        return repository.loadHostDiscovery(
                user != null ? user.getUserId() : null, agent.getAgentId(), fingerprint);
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

    private String connectionFingerprint(Agent agent) {
        String source = String.valueOf(agent.getConnLink()) + "|" + String.valueOf(agent.getProtocol())
                + "|" + String.valueOf(agent.getHeaders()) + "|" + String.valueOf(agent.getProxyType())
                + "|" + String.valueOf(agent.getProxyHost()) + "|" + String.valueOf(agent.getProxyPort());
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
