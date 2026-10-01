package org.leo.service;

import org.leo.core.entity.Agent;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.core.util.session.AgentNodeSessionWorkDirUtil;
import org.leo.dao.mapper.AgentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Agent 管理服务。
 *
 * <p>Agent permission 值：private / team / public（默认 private）。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentMapper agentMapper;
    /**
     * SQLite serializes writers, so guard against concurrent delete transactions
     * trying to write the same database at once.
     */
    private final ReentrantLock agentDeletionLock = new ReentrantLock();

    public AgentService(AgentMapper agentMapper) {
        this.agentMapper = agentMapper;
    }

    // ── 基础查询 ─────────────────────────────────────────────────────────────────

    public Agent findAgentById(String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        return agentMapper.findAgentById(agentId.trim());
    }

    public List<Agent> findAgentsByParentAgentId(String parentAgentId) {
        if (parentAgentId == null || parentAgentId.isBlank()) return new ArrayList<>();
        List<Agent> agents = agentMapper.findAgentsByParentAgentId(parentAgentId.trim());
        return agents != null ? agents : new ArrayList<>();
    }

    public List<Agent> findAgentsByPermission(String permission) {
        if (permission == null || permission.isBlank()) return new ArrayList<>();
        List<Agent> agents = agentMapper.findAgentsByPermission(permission.trim());
        return agents != null ? agents : new ArrayList<>();
    }

    public List<Agent> getAllAgents() {
        List<Agent> agents = agentMapper.getAllAgents();
        return agents != null ? agents : new ArrayList<>();
    }

    // ── 写操作 ───────────────────────────────────────────────────────────────────

    public boolean insertAgent(Agent agent) {
        if (agent == null) throw new IllegalArgumentException("agent参数不能为空");
        validateRequestPolicy(agent);
        String now = DATE_FORMAT.format(LocalDateTime.now());
        return agentMapper.insertAgent(
                agent.getAgentId(),
                agent.getAgentName(),
                agent.getParentAgentId(),
                agent.getCreateByUserId(),
                agent.getTeamId(),
                agent.getConnLink(),
                agent.getProtocol(),
                agent.getHeaders(),
                agent.getReqDisguiseId(),
                agent.getRespDisguiseId(),
                agent.getPayloadKey(),
                agent.getProxyEnabled(),
                agent.getProxyType(),
                agent.getProxyHost(),
                agent.getProxyPort(),
                agent.getMaxReqCount(),
                agent.getPermission(),
                agent.getLastHeartbeat(),
                agent.getHeartbeatInterval(),
                now, now,
                agent.getRemark(),
                agent.getUrlStrategy(),
                agent.getPaddingStrategy(),
                agent.getHeaderNoiseStrategy(),
                agent.getTlsFingerprintStrategy(),
                agent.getComponentClassNameStrategy(),
                agent.getType()
        );
    }

    public boolean updateAgentById(Agent agent) {
        if (agent == null || agent.getAgentId() == null || agent.getAgentId().isBlank()) {
            throw new IllegalArgumentException("agentId不能为空");
        }
        validateRequestPolicy(agent);
        String now = DATE_FORMAT.format(LocalDateTime.now());
        return agentMapper.updateAgentById(
                agent.getAgentId(),
                agent.getAgentName(),
                agent.getParentAgentId(),
                agent.getCreateByUserId(),
                agent.getTeamId(),
                agent.getConnLink(),
                agent.getProtocol(),
                agent.getHeaders(),
                agent.getReqDisguiseId(),
                agent.getRespDisguiseId(),
                agent.getPayloadKey(),
                agent.getProxyEnabled(),
                agent.getProxyType(),
                agent.getProxyHost(),
                agent.getProxyPort(),
                agent.getMaxReqCount(),
                agent.getPermission(),
                agent.getLastHeartbeat(),
                agent.getHeartbeatInterval(),
                now,
                agent.getRemark(),
                agent.getUrlStrategy(),
                agent.getPaddingStrategy(),
                agent.getHeaderNoiseStrategy(),
                agent.getTlsFingerprintStrategy(),
                agent.getComponentClassNameStrategy(),
                agent.getType()
        );
    }

    /**
     * 仅更新 last_heartbeat 字段。
     * 连接测试成功或 Agent 初始化成功后调用，避免全量更新。
     */
    public boolean updateLastHeartbeat(String agentId) {
        if (agentId == null || agentId.isBlank()) return false;
        String now = DATE_FORMAT.format(LocalDateTime.now());
        return agentMapper.updateLastHeartbeat(agentId.trim(), now, now);
    }

    /**
     * 删除指定 Agent 及其全部后代节点。
     *
     * <p>数据库记录按叶子节点到根节点的顺序在同一事务中删除；事务提交后，
     * 再关闭这些节点的在线会话并清理对应工作目录，避免留下孤立资源。
     */
    @Transactional
    public boolean deleteAgentById(String agentId) {
        agentDeletionLock.lock();
        boolean unlockAfterTransaction = false;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        agentDeletionLock.unlock();
                    }
                });
                unlockAfterTransaction = true;
            }
            if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId参数不能为空");
            Agent root = agentMapper.findAgentById(agentId.trim());
            if (root == null) return false;

            List<Agent> subtree = collectSubtree(root);
            List<Agent> deletionOrder = new ArrayList<>(subtree);
            Collections.reverse(deletionOrder);
            for (Agent agent : deletionOrder) {
                agentMapper.deleteProjectRelationsByAgentId(agent.getAgentId());
                if (!agentMapper.deleteAgentById(agent.getAgentId())) {
                    throw new IllegalStateException("删除Agent失败: " + agent.getAgentId());
                }
            }

            scheduleResourceCleanup(subtree);
            return true;
        } finally {
            // Spring commits the transaction after this method returns. Keep
            // the lock until afterCompletion so the next request cannot enter
            // while the current SQLite write transaction is still committing.
            if (!unlockAfterTransaction) {
                agentDeletionLock.unlock();
            }
        }
    }

    private List<Agent> collectSubtree(Agent root) {
        Map<String, Agent> nodes = new LinkedHashMap<>();
        Deque<Agent> pending = new ArrayDeque<>();
        pending.push(root);

        while (!pending.isEmpty()) {
            Agent current = pending.pop();
            if (current == null || current.getAgentId() == null || current.getAgentId().isBlank()) {
                continue;
            }
            String agentId = current.getAgentId().trim();
            if (nodes.putIfAbsent(agentId, current) != null) {
                continue;
            }
            List<Agent> children = agentMapper.findAgentsByParentAgentId(agentId);
            if (children == null) continue;
            for (Agent child : children) {
                if (child != null) pending.push(child);
            }
        }
        return new ArrayList<>(nodes.values());
    }

    private void scheduleResourceCleanup(List<Agent> deletedAgents) {
        List<Agent> snapshot = List.copyOf(deletedAgents);
        Runnable cleanup = () -> cleanupDeletedAgentResources(snapshot);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
            return;
        }
        cleanup.run();
    }

    private void cleanupDeletedAgentResources(List<Agent> deletedAgents) {
        Set<String> deletedIds = deletedAgents.stream()
                .map(Agent::getAgentId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        for (Map.Entry<String, AgentNodeSession> entry
                : AgentNodeSessionContainer.getAllSessions().entrySet()) {
            AgentNodeSession session = entry.getValue();
            if (session != null && deletedIds.contains(session.resolveAgentId())) {
                AgentNodeSessionContainer.removeSession(entry.getKey());
            }
        }

        for (Agent agent : deletedAgents) {
            try {
                if (!AgentNodeSessionWorkDirUtil.deleteAgentWorkDir(
                        agent.getCreateByUserId(), agent.getAgentId())) {
                    log.warn("清理 Agent 工作目录失败, agentId={}", agent.getAgentId());
                }
            } catch (Exception e) {
                log.warn("清理 Agent 工作目录异常, agentId={}", agent.getAgentId(), e);
            }
        }
    }

    private void validateRequestPolicy(Agent agent) {
        Agent.requireValidMaxRequestCount(agent.getMaxReqCount());
        if ("java".equalsIgnoreCase(agent.getType())
                && (agent.getPayloadKey() == null || agent.getPayloadKey().trim().isEmpty())) {
            throw new IllegalArgumentException("Java 节点 AES 密钥不能为空");
        }
    }
}
