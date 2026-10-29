package org.leo.service.security;

import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.security.AccessPolicy;
import org.leo.core.session.AgentNodeSession;
import org.leo.service.AgentService;
import org.leo.service.user.UserService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/** Shared current-resource checks for HTTP and AI entry points. */
@Service
public class AgentAccessService {

    private static final int MAX_PARENT_DEPTH = 100;
    private final AgentService agents;
    private final UserService users;

    public AgentAccessService(AgentService agents, UserService users) {
        this.agents = agents;
        this.users = users;
    }

    public boolean canAccessSession(AgentNodeSession session, User user) {
        if (!AccessPolicy.canAccessSession(session, user)) return false;
        if (AccessPolicy.isAdmin(user)) return true;
        String agentId = session.resolveAgentId();
        return agentId != null && canAccessAgentChain(agents.findAgentById(agentId), user);
    }

    public boolean canAccessAgentChain(Agent agent, User user) {
        Agent current = agent;
        Set<String> visited = new HashSet<>();
        for (int depth = 0; current != null && depth < MAX_PARENT_DEPTH; depth++) {
            if (current.getAgentId() == null || current.getAgentId().isBlank()
                    || !canAccessAgent(current, user) || !visited.add(current.getAgentId())) return false;
            String parentId = current.getParentAgentId();
            if (parentId == null || parentId.isBlank() || "root".equals(parentId)) return true;
            current = agents.findAgentById(parentId);
        }
        return false;
    }

    private boolean canAccessAgent(Agent agent, User user) {
        if (AccessPolicy.canAccessAgent(agent, user)) return true;
        if (agent == null || user == null || user.getTeamId() == null || user.getTeamId().isBlank()
                || !AccessPolicy.isTeamVisiblePermission(agent.getPermission())
                || agent.getTeamId() != null && !agent.getTeamId().isBlank()) return false;
        User owner = users.getUserById(agent.getCreateByUserId());
        return owner != null && user.getTeamId().equals(owner.getTeamId());
    }
}
