package org.leo.web.security;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.session.AgentNodeSession;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionPolicyTest {

    @Test
    void isolatesAgentSessionsByOwnerUnlessCallerIsAdmin() {
        AgentNodeSession session = new AgentNodeSession();
        session.setCreateByUser("owner");

        assertTrue(PermissionPolicy.canAccessSession(session, user("owner", "normal", "team-a")));
        assertFalse(PermissionPolicy.canAccessSession(session, user("other", "leader", "team-a")));
        assertTrue(PermissionPolicy.canAccessSession(session, user("admin", "admin", null)));
    }

    @Test
    void appliesPrivateTeamAndPublicAgentVisibility() {
        Agent agent = new Agent();
        agent.setCreateByUserId("owner");
        agent.setTeamId("team-a");
        agent.setPermission("private");

        assertTrue(PermissionPolicy.canAccessAgent(agent, user("owner", "normal", "team-a")));
        assertFalse(PermissionPolicy.canAccessAgent(agent, user("other", "normal", "team-a")));

        agent.setPermission("team");
        assertTrue(PermissionPolicy.canAccessAgent(agent, user("other", "normal", "team-a")));
        assertFalse(PermissionPolicy.canAccessAgent(agent, user("outsider", "normal", "team-b")));

        agent.setPermission("public");
        assertTrue(PermissionPolicy.canAccessAgent(agent, user("outsider", "normal", "team-b")));
    }

    private static User user(String id, String privilege, String teamId) {
        User user = new User();
        user.setUserId(id);
        user.setPrivilege(privilege);
        user.setTeamId(teamId);
        return user;
    }
}
