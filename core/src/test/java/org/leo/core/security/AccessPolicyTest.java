package org.leo.core.security;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.session.AgentNodeSession;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessPolicyTest {

    @Test
    void isolatesAgentSessionsByOwnerUnlessCallerIsAdmin() {
        AgentNodeSession session = new AgentNodeSession();
        session.setCreateByUser("owner");

        assertTrue(AccessPolicy.canAccessSession(session, user("owner", "normal", "team-a")));
        assertFalse(AccessPolicy.canAccessSession(session, user("other", "leader", "team-a")));
        assertTrue(AccessPolicy.canAccessSession(session, user("admin", "admin", null)));
    }

    @Test
    void appliesPrivateTeamAndPublicAgentVisibility() {
        Agent agent = new Agent();
        agent.setCreateByUserId("owner");
        agent.setTeamId("team-a");
        agent.setPermission("private");

        assertTrue(AccessPolicy.canAccessAgent(agent, user("owner", "normal", "team-a")));
        assertFalse(AccessPolicy.canAccessAgent(agent, user("other", "normal", "team-a")));

        agent.setPermission("team");
        assertTrue(AccessPolicy.canAccessAgent(agent, user("other", "normal", "team-a")));
        assertFalse(AccessPolicy.canAccessAgent(agent, user("outsider", "normal", "team-b")));

        agent.setPermission("public");
        assertTrue(AccessPolicy.canAccessAgent(agent, user("outsider", "normal", "team-b")));
    }

    @Test
    void missingUserIdentityOrResourceNeverGrantsAccess() {
        User admin = user("admin", "admin", null);
        Agent agent = new Agent();
        AgentNodeSession session = new AgentNodeSession();
        for (User caller : new User[]{null, user(null, "admin", null)}) {
            assertFalse(AccessPolicy.canAccessSession(session, caller));
            assertFalse(AccessPolicy.canAccessAgent(agent, caller));
            assertFalse(AccessPolicy.canModifyAgent(agent, caller));
        }
        assertFalse(AccessPolicy.canAccessSession(null, admin));
        assertFalse(AccessPolicy.canAccessAgent(null, admin));
        assertFalse(AccessPolicy.canModifyAgent(null, admin));
        assertFalse(AccessPolicy.canAccessSession(session, user("other", "normal", null)));
    }

    @Test
    void onlyAdminCanModifyPublicAgentsEvenWhenCallerOwnsThem() {
        Agent agent = new Agent();
        agent.setCreateByUserId("owner");
        agent.setPermission("public");
        assertFalse(AccessPolicy.canModifyAgent(agent, user("owner", "normal", "team-a")));
        assertTrue(AccessPolicy.canModifyAgent(agent, user("admin", "admin", null)));
    }

    @Test
    void teamModificationRequiresOwnershipAdminOrSameTeamLeader() {
        Agent agent = new Agent();
        agent.setCreateByUserId("owner");
        agent.setTeamId("team-a");
        agent.setPermission("team");
        assertTrue(AccessPolicy.canModifyAgent(agent, user("owner", "normal", null)));
        assertTrue(AccessPolicy.canModifyAgent(agent, user("admin", "admin", "team-b")));
        assertTrue(AccessPolicy.canModifyAgent(agent, user("leader", "leader", "team-a")));
        assertFalse(AccessPolicy.canModifyAgent(agent, user("other", "normal", "team-a")));
        assertFalse(AccessPolicy.canModifyAgent(agent, user("leader", "leader", "team-b")));
        assertFalse(AccessPolicy.canModifyAgent(agent, user("leader", "leader", null)));
        agent.setPermission("private");
        assertFalse(AccessPolicy.canModifyAgent(agent, user("leader", "leader", "team-a")));
        agent.setPermission("team");
        for (String missingTeam : new String[]{null, "", " ", "\t"}) {
            agent.setTeamId(missingTeam);
            User unassignedLeader = user("leader", "leader", missingTeam);
            assertFalse(AccessPolicy.canAccessAgent(agent, unassignedLeader));
            assertFalse(AccessPolicy.canModifyAgent(agent, unassignedLeader));
        }
    }

    @Test
    void roleAndPermissionChecksAreNullSafeAndExact() {
        assertFalse(AccessPolicy.isAdmin(null));
        assertFalse(AccessPolicy.isLeader(null));
        assertFalse(AccessPolicy.isAdmin(user("user", "Admin", null)));
        assertFalse(AccessPolicy.isLeader(user("user", "normal", null)));
        assertTrue(AccessPolicy.isAdmin(user("admin", "admin", null)));
        assertTrue(AccessPolicy.isLeader(user("leader", "leader", null)));
        assertTrue(AccessPolicy.isTeamVisiblePermission("team"));
        assertFalse(AccessPolicy.isTeamVisiblePermission(null));
        assertFalse(AccessPolicy.isTeamVisiblePermission("public"));
    }

    private static User user(String id, String privilege, String teamId) {
        User user = new User();
        user.setUserId(id);
        user.setPrivilege(privilege);
        user.setTeamId(teamId);
        return user;
    }
}
