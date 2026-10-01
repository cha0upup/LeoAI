package org.leo.service.agentnode;

import org.leo.core.entity.Agent;
import org.leo.service.AgentService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Resolves one immutable child-to-transport Agent chain with cycle checks. */
final class AgentRouteResolver {

    private static final int MAX_DEPTH = 100;
    private final AgentService agentService;

    AgentRouteResolver(AgentService agentService) {
        this.agentService = agentService;
    }

    Route resolve(Agent requested) {
        if (requested == null) throw new IllegalArgumentException("Agent不能为空");

        List<Agent> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Agent current = requested;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            String currentId = normalized(current.getAgentId());
            if (currentId != null && !visited.add(currentId)) {
                throw new IllegalArgumentException("Agent 父链存在循环: " + currentId);
            }
            chain.add(current);

            String parentId = normalized(current.getParentAgentId());
            if (parentId == null || "root".equals(parentId)) {
                return new Route(requested, current, chain);
            }
            if (visited.contains(parentId)) {
                throw new IllegalArgumentException("Agent 父链存在循环: " + parentId);
            }
            if (agentService == null) {
                throw new IllegalStateException("AgentService 未初始化");
            }
            current = agentService.findAgentById(parentId);
            if (current == null) {
                throw new IllegalArgumentException("Agent 父节点不存在: " + parentId);
            }
        }
        throw new IllegalArgumentException("Agent 父链超过最大深度: " + MAX_DEPTH);
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static final class Route {
        private final Agent requested;
        private final Agent transport;
        private final List<Agent> chain;

        private Route(Agent requested, Agent transport, List<Agent> chain) {
            this.requested = requested;
            this.transport = transport;
            this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
        }

        Agent requested() { return requested; }
        Agent transport() { return transport; }
        List<Agent> chain() { return chain; }
    }
}
