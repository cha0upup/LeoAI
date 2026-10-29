package org.leo.service.security;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.session.AgentNodeSession;
import org.leo.service.AgentService;
import org.leo.service.user.UserService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentAccessServiceTest {

    private final AgentService agents = mock(AgentService.class);
    private final UserService users = mock(UserService.class);
    private final AgentAccessService access = new AgentAccessService(agents, users);

    @Test
    void rechecksSharingFromStorageRatherThanTheSessionSnapshot() {
        User user = user("reader", "team-a", "normal");
        Agent shared = agent("host", "root", "owner", "team-a", "team");
        AgentNodeSession session = session("reader", "host");
        when(agents.findAgentById("host")).thenReturn(shared);

        assertTrue(access.canAccessSession(session, user));
        shared.setPermission("private");
        assertFalse(access.canAccessSession(session, user));
        verify(agents, times(2)).findAgentById("host");
    }

    @Test
    void removingMembershipRevokesAPreviouslyCreatedSession() {
        when(agents.findAgentById("host")).thenReturn(agent("host", "root", "owner", "team-a", "team"));
        User user = user("reader", "team-a", "normal");
        AgentNodeSession session = session("reader", "host");
        assertTrue(access.canAccessSession(session, user));

        user.setTeamId("team-b");

        assertFalse(access.canAccessSession(session, user));
    }

    @Test
    void revokedAncestorBlocksChildSessionAccess() {
        Agent child = agent("child", "gateway", "reader", null, "private");
        Agent gateway = agent("gateway", "root", "owner", "team-a", "team");
        when(agents.findAgentById("child")).thenReturn(child);
        when(agents.findAgentById("gateway")).thenReturn(gateway);
        AgentNodeSession session = session("reader", "child");
        User reader = user("reader", "team-a", "normal");
        assertTrue(access.canAccessSession(session, reader));

        gateway.setPermission("private");

        assertFalse(access.canAccessSession(session, reader));
    }

    @Test
    void preservesOwnerAndAdministratorAccessWithoutSharingOtherUsersSessions() {
        when(agents.findAgentById("host")).thenReturn(agent("host", "root", "owner", null, "private"));
        AgentNodeSession session = session("owner", "host");

        assertTrue(access.canAccessSession(session, user("owner", null, "normal")));
        assertTrue(access.canAccessSession(session, user("admin", null, "admin")));
        assertFalse(access.canAccessSession(session, user("other", null, "normal")));
    }

    @Test
    void rejectsMissingResourcesAndCyclicRoutes() {
        User owner = user("owner", null, "normal");
        assertFalse(access.canAccessSession(session("owner", "missing"), owner));
        AgentNodeSession unbound = session("owner", null);
        assertFalse(access.canAccessSession(unbound, owner));
        Agent first = agent("a", "b", "owner", null, "private");
        Agent second = agent("b", "a", "owner", null, "private");
        when(agents.findAgentById("a")).thenReturn(first);
        when(agents.findAgentById("b")).thenReturn(second);

        assertFalse(access.canAccessAgentChain(first, owner));
        verify(agents, atMost(1)).findAgentById("b");
    }

    @Test
    void preservesLegacyTeamFallbackWhenTheAgentHasNoExplicitTeam() {
        Agent host = agent("host", "root", "owner", null, "team");
        User reader = user("reader", "team-a", "normal");
        when(users.getUserById("owner")).thenReturn(user("owner", "team-a", "normal"));

        assertTrue(access.canAccessAgentChain(host, reader));
        when(users.getUserById("owner")).thenReturn(user("owner", "team-b", "normal"));
        assertFalse(access.canAccessAgentChain(host, reader));
    }

    private static AgentNodeSession session(String owner, String agentId) {
        AgentNodeSession session = new AgentNodeSession();
        session.setCreateByUser(owner);
        session.setAgentId(agentId);
        return session;
    }

    private static User user(String id, String team, String role) {
        User user = new User();
        user.setUserId(id);
        user.setTeamId(team);
        user.setPrivilege(role);
        user.setStatus(1);
        return user;
    }

    private static Agent agent(String id, String parent, String owner, String team, String permission) {
        Agent agent = new Agent();
        agent.setAgentId(id);
        agent.setParentAgentId(parent);
        agent.setCreateByUserId(owner);
        agent.setTeamId(team);
        agent.setPermission(permission);
        return agent;
    }
}
