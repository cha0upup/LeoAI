package org.leo.service;

import org.leo.core.entity.Puppet;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.dao.mapper.PuppetMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.stream.Collectors;

/**
 * Puppet 管理服务。
 *
 * <p>Puppet permission 值：private / team / public（默认 private）。
 */
@Service
public class PuppetService {

    private static final Logger log = LoggerFactory.getLogger(PuppetService.class);

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PuppetMapper puppetMapper;

    @Autowired
    public PuppetService(PuppetMapper puppetMapper) {
        this.puppetMapper = puppetMapper;
    }

    // ── 基础查询 ─────────────────────────────────────────────────────────────────

    public Puppet findPuppetById(String id) {
        if (id == null || id.isBlank()) return null;
        return puppetMapper.findPuppetById(id.trim());
    }

    public List<Puppet> findPuppetByCreateUserId(String createUserId) {
        if (createUserId == null || createUserId.isBlank()) return new ArrayList<>();
        List<Puppet> list = puppetMapper.findPuppetByCreateUser(createUserId.trim());
        return list != null ? list : new ArrayList<>();
    }

    public List<Puppet> findPuppetByParentPuppetId(String puppetId) {
        if (puppetId == null || puppetId.isBlank()) return new ArrayList<>();
        List<Puppet> list = puppetMapper.findPuppetByParentPuppetId(puppetId.trim());
        return list != null ? list : new ArrayList<>();
    }

    public List<Puppet> findPuppetByPermission(String permission) {
        if (permission == null || permission.isBlank()) return new ArrayList<>();
        List<Puppet> list = puppetMapper.findPuppetByPermission(permission.trim());
        return list != null ? list : new ArrayList<>();
    }

    public List<Puppet> getAllPuppet() {
        List<Puppet> list = puppetMapper.getAllPuppet();
        return list != null ? list : new ArrayList<>();
    }

    // ── 写操作 ───────────────────────────────────────────────────────────────────

    public boolean insertPuppet(Puppet puppet) {
        if (puppet == null) throw new IllegalArgumentException("puppet参数不能为空");
        validateRequestPolicy(puppet);
        String now = DATE_FORMAT.format(LocalDateTime.now());
        return puppetMapper.insertPuppet(
                puppet.getPuppetId(),
                puppet.getPuppetName(),
                puppet.getParentPuppetId(),
                puppet.getCreateByUserId(),
                puppet.getTeamId(),
                puppet.getConnLink(),
                puppet.getProtocol(),
                puppet.getHeaders(),
                puppet.getReqDisguiseId(),
                puppet.getRespDisguiseId(),
                puppet.getPayloadKey(),
                puppet.getProxyEnabled(),
                puppet.getProxyType(),
                puppet.getProxyHost(),
                puppet.getProxyPort(),
                puppet.getMaxReqCount(),
                puppet.getPermission(),
                puppet.getLastHeartbeat(),
                puppet.getHeartbeatInterval(),
                now, now,
                puppet.getRemark(),
                puppet.getUrlStrategy(),
                puppet.getPaddingStrategy(),
                puppet.getHeaderNoiseStrategy(),
                puppet.getTlsFingerprintStrategy(),
                puppet.getComponentClassNameStrategy(),
                puppet.getType()
        );
    }

    public boolean updatePuppetById(Puppet puppet) {
        if (puppet == null || puppet.getPuppetId() == null || puppet.getPuppetId().isBlank()) {
            throw new IllegalArgumentException("puppetId不能为空");
        }
        validateRequestPolicy(puppet);
        String now = DATE_FORMAT.format(LocalDateTime.now());
        return puppetMapper.updatePuppetById(
                puppet.getPuppetId(),
                puppet.getPuppetName(),
                puppet.getParentPuppetId(),
                puppet.getCreateByUserId(),
                puppet.getTeamId(),
                puppet.getConnLink(),
                puppet.getProtocol(),
                puppet.getHeaders(),
                puppet.getReqDisguiseId(),
                puppet.getRespDisguiseId(),
                puppet.getPayloadKey(),
                puppet.getProxyEnabled(),
                puppet.getProxyType(),
                puppet.getProxyHost(),
                puppet.getProxyPort(),
                puppet.getMaxReqCount(),
                puppet.getPermission(),
                puppet.getLastHeartbeat(),
                puppet.getHeartbeatInterval(),
                now,
                puppet.getRemark(),
                puppet.getUrlStrategy(),
                puppet.getPaddingStrategy(),
                puppet.getHeaderNoiseStrategy(),
                puppet.getTlsFingerprintStrategy(),
                puppet.getComponentClassNameStrategy(),
                puppet.getType()
        );
    }

    /**
     * 仅更新 last_heartbeat 字段。
     * 连接测试成功或 Puppet 初始化成功后调用，避免全量更新。
     */
    public boolean updateLastHeartbeat(String puppetId) {
        if (puppetId == null || puppetId.isBlank()) return false;
        String now = DATE_FORMAT.format(LocalDateTime.now());
        return puppetMapper.updateLastHeartbeat(puppetId.trim(), now, now);
    }

    /**
     * 删除指定 Puppet 及其全部后代节点。
     *
     * <p>数据库记录按叶子节点到根节点的顺序在同一事务中删除；事务提交后，
     * 再关闭这些节点的在线会话并清理对应工作目录，避免留下孤立资源。
     */
    @Transactional
    public boolean deletePuppetById(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id参数不能为空");
        Puppet root = puppetMapper.findPuppetById(id.trim());
        if (root == null) return false;

        List<Puppet> subtree = collectSubtree(root);
        List<Puppet> deletionOrder = new ArrayList<>(subtree);
        Collections.reverse(deletionOrder);
        for (Puppet puppet : deletionOrder) {
            puppetMapper.deleteProjectRelationsByPuppetId(puppet.getPuppetId());
            if (!puppetMapper.deletePuppetById(puppet.getPuppetId())) {
                throw new IllegalStateException("删除Puppet失败: " + puppet.getPuppetId());
            }
        }

        scheduleResourceCleanup(subtree);
        return true;
    }

    private List<Puppet> collectSubtree(Puppet root) {
        Map<String, Puppet> nodes = new LinkedHashMap<>();
        Deque<Puppet> pending = new ArrayDeque<>();
        pending.push(root);

        while (!pending.isEmpty()) {
            Puppet current = pending.pop();
            if (current == null || current.getPuppetId() == null || current.getPuppetId().isBlank()) {
                continue;
            }
            String puppetId = current.getPuppetId().trim();
            if (nodes.putIfAbsent(puppetId, current) != null) {
                continue;
            }
            List<Puppet> children = puppetMapper.findPuppetByParentPuppetId(puppetId);
            if (children == null) continue;
            for (Puppet child : children) {
                if (child != null) pending.push(child);
            }
        }
        return new ArrayList<>(nodes.values());
    }

    private void scheduleResourceCleanup(List<Puppet> deletedPuppets) {
        List<Puppet> snapshot = List.copyOf(deletedPuppets);
        Runnable cleanup = () -> cleanupDeletedPuppetResources(snapshot);
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

    private void cleanupDeletedPuppetResources(List<Puppet> deletedPuppets) {
        Set<String> deletedIds = deletedPuppets.stream()
                .map(Puppet::getPuppetId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        for (Map.Entry<String, PuppetNodeSession> entry
                : PuppetNodeSessionContainer.getAllSession().entrySet()) {
            PuppetNodeSession session = entry.getValue();
            if (session != null && deletedIds.contains(session.resolvePuppetId())) {
                PuppetNodeSessionContainer.removeSession(entry.getKey());
            }
        }

        for (Puppet puppet : deletedPuppets) {
            try {
                if (!PuppetNodeSessionWorkDirUtil.deletePuppetWorkDir(
                        puppet.getCreateByUserId(), puppet.getPuppetId())) {
                    log.warn("清理 Puppet 工作目录失败, puppetId={}", puppet.getPuppetId());
                }
            } catch (Exception e) {
                log.warn("清理 Puppet 工作目录异常, puppetId={}", puppet.getPuppetId(), e);
            }
        }
    }

    private void validateRequestPolicy(Puppet puppet) {
        Puppet.requireValidMaxRequestCount(puppet.getMaxReqCount());
        if ("java".equalsIgnoreCase(puppet.getType())
                && (puppet.getPayloadKey() == null || puppet.getPayloadKey().trim().isEmpty())) {
            throw new IllegalArgumentException("Java 节点 AES 密钥不能为空");
        }
    }
}
