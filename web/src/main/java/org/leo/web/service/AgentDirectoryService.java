package org.leo.web.service;

import org.leo.core.entity.Agent;
import org.leo.service.AgentService;
import org.leo.web.dto.platform.agent.AgentDirectoryEntry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class AgentDirectoryService {
    private final AgentService agentService;

    public AgentDirectoryService(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 只根据当前用户可见的直接子机显示展开箭头，保留调用方的工作区和权限规则。 */
    public List<AgentDirectoryEntry> describe(List<Agent> agents, Predicate<Agent> visible) {
        if (agents == null || agents.isEmpty()) return List.of();
        List<Agent> rows = agents.stream().filter(agent -> agent != null
                && agent.getAgentId() != null && !agent.getAgentId().isBlank())
                .filter(visible).toList();
        if (rows.isEmpty()) return List.of();
        List<String> parentIds = rows.stream().map(Agent::getAgentId).distinct().toList();
        Set<String> parentsWithChildren = agentService.findAgentsByParentAgentIds(parentIds).stream()
                .filter(child -> child != null && visible.test(child))
                .map(Agent::getParentAgentId).collect(Collectors.toSet());
        return rows.stream().map(agent -> new AgentDirectoryEntry(
                agent, parentsWithChildren.contains(agent.getAgentId()))).toList();
    }
}
