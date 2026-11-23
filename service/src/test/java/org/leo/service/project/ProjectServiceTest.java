package org.leo.service.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.Agent;
import org.leo.core.entity.Project;
import org.leo.core.entity.User;
import org.leo.core.util.json.JsonUtil;
import org.leo.dao.mapper.ProjectMapper;
import org.leo.dao.mapper.ProjectMapper.MembershipRow;
import org.leo.dao.mapper.ProjectMapper.ProjectAgentCount;
import org.leo.service.agentnode.AgentService;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ProjectServiceTest {
    private final ProjectMapper mapper = mock(ProjectMapper.class);
    private final AgentService agentService = mock(AgentService.class);
    private final ProjectService service = new ProjectService(mapper, agentService,
            mock(UserService.class), mock(TeamService.class));

    @ParameterizedTest
    @CsvSource({"false, normal", "true, normal", "false, leader", "true, leader"})
    void publicScopeRequiresAdminWithoutMutatingRejectedProjects(boolean update, String privilege) {
        User actor = actor(privilege);
        Project input = project("submitted-owner", "submitted-team", " public ");
        input.setProjectId("submitted-id");
        input.setProjectName(" Changed ");
        Project existing = projectWithId("existing-id");
        existing.setProjectName("Original");
        Project target = update ? existing : input;
        String before = JsonUtil.toJsonString(target);

        assertThrows(SecurityException.class, () -> {
            if (update) service.update(existing, input, actor);
            else service.create(input, actor);
        });

        assertEquals(before, JsonUtil.toJsonString(target));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidStatusIsRejectedBeforeAnyProjectFieldsChange(boolean update) {
        Project input = project("submitted-owner", "submitted-team", "team");
        input.setProjectId("submitted-id");
        input.setProjectName(" Changed ");
        input.setStatus("invalid");
        Project existing = projectWithId("existing-id");
        existing.setProjectName("Original");
        Project target = update ? existing : input;
        String before = JsonUtil.toJsonString(target);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> {
            if (update) service.update(existing, input, actor("normal"));
            else service.create(input, actor("normal"));
        });

        assertEquals("项目状态值无效", error.getMessage());
        assertEquals(before, JsonUtil.toJsonString(target));
        verifyNoInteractions(mapper);
    }

    @Test
    void skipsProjectLookupWithoutAUser() {
        assertTrue(service.listVisible(null).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void stillAppliesVisibilityRulesToCandidatesFromTheMapper() {
        Project owned = projectWithId("owned");
        owned.setOwnerUserId("user-1");
        Project hidden = projectWithId("hidden");
        hidden.setOwnerUserId("other-user");
        when(mapper.findVisibleProjectCandidates("user-1", "team-1", false)).thenReturn(List.of(hidden, owned));

        assertEquals(List.of(owned), service.listVisible(actor("normal")));
    }

    @Test
    void groupsRequestedHostCountsWithOneMapperCall() {
        List<String> projectIds = List.of("project-1", "project-2");
        String projectIdsJson = "[\"project-1\",\"project-2\"]";
        when(mapper.countAgentsByProject(projectIdsJson)).thenReturn(List.of(
                new ProjectAgentCount("project-1", 3),
                new ProjectAgentCount("project-2", 1)));

        assertEquals(Map.of("project-1", 3, "project-2", 1), service.countAgentsByProject(projectIds));
        verify(mapper).countAgentsByProject(projectIdsJson);
    }

    @Test
    void returnsEmptyCountsWhenNoHostRelationsExist() {
        when(mapper.countAgentsByProject("[\"project-1\"]")).thenReturn(List.of());

        assertTrue(service.countAgentsByProject(List.of("project-1")).isEmpty());
    }

    @Test
    void skipsHostAggregationWhenNoProjectsAreRequested() {
        assertTrue(service.countAgentsByProject(List.of()).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void inheritsProjectScopeFromAttachedAncestorAgent() {
        Agent child = new Agent();
        child.setAgentId("child");
        child.setParentAgentId("gateway");
        when(mapper.existsAgentRelation("project-1", "gateway")).thenReturn(true);
        when(agentService.findAgentById("child")).thenReturn(child);

        assertTrue(service.containsAgent("project-1", "child"));
    }

    @Test
    void batchesSharedAndMissingAncestorsWithoutRepeatingMembershipQueries() {
        User user = new User();
        user.setUserId("user-1");
        user.setPrivilege("normal");
        Project visible = project("user-1", null, "private");
        visible.setProjectId("project-1");
        Project hidden = project("other-user", null, "private");
        hidden.setProjectId("project-2");

        when(agentService.findAgentById("child-a")).thenReturn(agent("child-a", "gateway"));
        when(agentService.findAgentById("child-b")).thenReturn(agent("child-b", "gateway"));
        when(agentService.findAgentById("gateway")).thenReturn(agent("gateway", "root"));
        when(agentService.findAgentById("orphan-a")).thenReturn(agent("orphan-a", "missing"));
        when(agentService.findAgentById("orphan-b")).thenReturn(agent("orphan-b", "missing"));
        when(mapper.findVisibleMembershipsByEntryAgentIds("[\"gateway\"]", "user-1", null, false)).thenReturn(List.of(
                new MembershipRow("gateway", "project-1", visible), new MembershipRow("gateway", "project-2", hidden)));

        Map<String, List<Project>> result = service.listVisibleMemberships(
                Arrays.asList(" child-a ", "child-b", "gateway", "child-a", null, " ", "orphan-a", "orphan-b"), user);

        assertEquals(List.of("child-a", "child-b", "gateway", "orphan-a", "orphan-b"), List.copyOf(result.keySet()));
        for (String id : List.of("child-a", "child-b", "gateway")) assertEquals(List.of(visible), result.get(id));
        assertEquals(List.of(), result.get("orphan-a"));
        assertEquals(List.of(), result.get("orphan-b"));
        for (String id : List.of("child-a", "child-b", "gateway", "orphan-a", "orphan-b", "missing")) {
            verify(agentService).findAgentById(id);
        }
        verify(mapper).findVisibleMembershipsByEntryAgentIds("[\"gateway\"]", "user-1", null, false);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void laterMembershipQueriesReadChangedAncestry() {
        User user = new User();
        user.setUserId("user-1");
        Project first = projectWithId("first-project");
        first.setOwnerUserId("user-1");
        Project second = projectWithId("second-project");
        second.setOwnerUserId("user-1");
        when(agentService.findAgentById("child"))
                .thenReturn(agent("child", "first"), agent("child", "second"));
        when(agentService.findAgentById("first")).thenReturn(agent("first", "root"));
        when(agentService.findAgentById("second")).thenReturn(agent("second", "root"));
        when(mapper.findVisibleMembershipsByEntryAgentIds("[\"first\"]", "user-1", null, false))
                .thenReturn(List.of(new MembershipRow("first", "first-project", first)));
        when(mapper.findVisibleMembershipsByEntryAgentIds("[\"second\"]", "user-1", null, false))
                .thenReturn(List.of(new MembershipRow("second", "second-project", second)));

        assertEquals(List.of(first), service.listVisibleMemberships(List.of("child"), user).get("child"));
        assertEquals(List.of(second), service.listVisibleMemberships(List.of("child"), user).get("child"));
        verify(agentService, times(2)).findAgentById("child");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void relationChangesResolveSharedEntryOnceAndDeduplicateSelectedNodes(boolean attach) {
        when(agentService.findAgentById("child-a")).thenReturn(agent("child-a", "gateway"));
        when(agentService.findAgentById("child-b")).thenReturn(agent("child-b", "gateway"));
        when(agentService.findAgentById("gateway")).thenReturn(agent("gateway", "root"));
        List<String> ids = Arrays.asList(" child-a ", "child-b", "gateway", "child-a", null, " ");

        if (attach) {
            when(mapper.attachAgents(eq("project-1"), eq("[\"gateway\"]"), isNull(), isNull(),
                    eq("user-1"), anyString())).thenReturn(1);
            assertEquals(1, service.attachAgents("project-1", ids, null, null, "user-1"));
            verify(mapper).attachAgents(eq("project-1"), eq("[\"gateway\"]"), isNull(), isNull(),
                    eq("user-1"), anyString());
        } else {
            when(mapper.detachAgents("project-1", "[\"gateway\"]")).thenReturn(1);
            assertEquals(1, service.detachAgents("project-1", ids));
            verify(mapper).detachAgents("project-1", "[\"gateway\"]");
        }
        for (String id : List.of("child-a", "child-b", "gateway")) verify(agentService).findAgentById(id);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMissingOrCyclicAncestorsWithoutRepeatedMembershipChecks(boolean cycle) {
        when(agentService.findAgentById("child")).thenReturn(agent("child", "parent"));
        when(agentService.findAgentById("parent")).thenReturn(cycle ? agent("parent", "child") : null);

        assertEquals(0, service.detachAgents("project-1", List.of("child")));
        assertFalse(service.containsAgent("project-1", "child"));

        verify(agentService, times(2)).findAgentById("child");
        verify(agentService, times(2)).findAgentById("parent");
        verify(mapper).existsAgentRelation("project-1", "child");
        verify(mapper).existsAgentRelation("project-1", "parent");
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "normal,user-1,null,private,true,true,true",
            "normal,user-2,team-1,team,true,true,false",
            "normal,user-2,team-2,team,false,false,false",
            "normal,user-2,team-1,private,false,false,false",
            "normal,user-2,team-2,public,true,false,false",
            "admin,user-2,team-2,private,true,true,true"
    }, nullValues = "null")
    void separatesProjectVisibilityEditingAndManagement(
            String privilege, String owner, String team, String permission,
            boolean visible, boolean editable, boolean manageable) {
        User user = new User();
        user.setUserId("user-1");
        user.setTeamId("team-1");
        user.setPrivilege(privilege);
        Project project = project(owner, team, permission);

        assertEquals(visible, service.canView(project, user));
        assertEquals(editable, service.canEditContent(project, user));
        assertEquals(manageable, service.canManage(project, user));
    }

    @Test
    void deletesRelationsBeforeDeletingProject() {
        Project project = projectWithId("project-1");
        project.setOwnerUserId("user-1");
        when(mapper.deleteAgentRelations("project-1")).thenReturn(3);
        when(mapper.deleteProject("project-1", "user-1")).thenReturn(1);

        assertEquals(3, service.delete(project));

        InOrder order = inOrder(mapper);
        order.verify(mapper).deleteAgentRelations("project-1");
        order.verify(mapper).deleteProject("project-1", "user-1");
    }

    @Test
    void reportsConcurrentOrMissingProjectDeletion() {
        Project project = projectWithId("project-1");
        project.setOwnerUserId("user-1");
        when(mapper.deleteProject("project-1", "user-1")).thenReturn(0);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> service.delete(project));

        assertEquals("删除项目失败", error.getMessage());
    }

    private static Project project(String owner, String teamId, String permission) {
        Project project = new Project();
        project.setOwnerUserId(owner);
        project.setTeamId(teamId);
        project.setPermission(permission);
        return project;
    }

    private static User actor(String privilege) {
        User user = new User();
        user.setUserId("user-1");
        user.setTeamId("team-1");
        user.setPrivilege(privilege);
        return user;
    }

    private static Project projectWithId(String projectId) {
        Project project = new Project();
        project.setProjectId(projectId);
        return project;
    }

    private static Agent agent(String agentId, String parentAgentId) {
        Agent agent = new Agent();
        agent.setAgentId(agentId);
        agent.setParentAgentId(parentAgentId);
        return agent;
    }
}
