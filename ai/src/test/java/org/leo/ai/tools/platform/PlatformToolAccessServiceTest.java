package org.leo.ai.tools.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolContext;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.service.user.UserService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformToolAccessServiceTest {

    @AfterEach
    void clearContext() {
        AiToolContext.clear();
    }

    @Test
    void filtersAgentsByOwnerTeamAndPublicScope() {
        UserService users = mock(UserService.class);
        User caller = user("caller", "normal", "team-a");
        when(users.getUserById("caller")).thenReturn(caller);
        PlatformToolAccessService access = new PlatformToolAccessService(users);
        AiToolContext.setExecutionPolicy(AiExecutionPolicy.from(caller));

        List<Agent> visible = access.filterVisible(List.of(
                agent("owned", "caller", null, "private"),
                agent("team", "other", "team-a", "team"),
                agent("public", "other", null, "public"),
                agent("private", "other", null, "private")));

        assertEquals(List.of("owned", "team", "public"),
                visible.stream().map(Agent::getAgentId).toList());
    }

    @Test
    void onlyLeaderCanModifyTeamAgentOwnedByAnotherUser() {
        UserService users = mock(UserService.class);
        PlatformToolAccessService access = new PlatformToolAccessService(users);
        Agent teamAgent = agent("team", "other", "team-a", "team");
        User normal = user("normal", "normal", "team-a");
        User leader = user("leader", "leader", "team-a");

        assertFalse(access.canModify(teamAgent, normal));
        assertTrue(access.canModify(teamAgent, leader));
    }

    private static Agent agent(String id, String owner,
                                 String teamId, String permission) {
        Agent agent = new Agent();
        agent.setAgentId(id);
        agent.setCreateByUserId(owner);
        agent.setTeamId(teamId);
        agent.setPermission(permission);
        return agent;
    }

    private static User user(String id, String privilege, String teamId) {
        User user = new User();
        user.setUserId(id);
        user.setUserName(id);
        user.setPrivilege(privilege);
        user.setTeamId(teamId);
        user.setStatus(1);
        return user;
    }
}
