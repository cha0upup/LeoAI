package org.leo.service.project;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Project;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.dao.mapper.ProjectMapper;
import org.leo.dao.entity.ProjectAgentCount;
import org.leo.service.AgentService;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class ProjectServiceTest {

    @Test
    void groupsAllHostCountsWithOneMapperCall() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        ProjectService service = new ProjectService(mapper, mock(AgentService.class));
        when(mapper.countAgentsByProject()).thenReturn(List.of(
                new ProjectAgentCount("project-1", 3),
                new ProjectAgentCount("project-2", 1)));

        assertEquals(Map.of("project-1", 3, "project-2", 1), service.countAgentsByProject());
        verify(mapper).countAgentsByProject();
    }

    @Test
    void returnsEmptyCountsWhenNoHostRelationsExist() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        ProjectService service = new ProjectService(mapper, mock(AgentService.class));
        when(mapper.countAgentsByProject()).thenReturn(List.of());

        assertTrue(service.countAgentsByProject().isEmpty());
    }

    @Test
    void inheritsProjectScopeFromAttachedAncestorAgent() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        AgentService agentService = mock(AgentService.class);
        ProjectService service = new ProjectService(mapper, agentService);

        Agent child = new Agent();
        child.setAgentId("child");
        child.setParentAgentId("gateway");
        when(mapper.containsAgent("project-1", "child")).thenReturn(0);
        when(mapper.containsAgent("project-1", "gateway")).thenReturn(1);
        when(agentService.findAgentById("child")).thenReturn(child);

        assertTrue(service.containsAgent("project-1", "child"));
    }

    @Test
    void resolvesChildSelectionToItsEntryAgent() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        AgentService agentService = mock(AgentService.class);
        ProjectService service = new ProjectService(mapper, agentService);

        Agent child = agent("child", "gateway");
        Agent gateway = agent("gateway", "root");
        when(agentService.findAgentById("child")).thenReturn(child);
        when(agentService.findAgentById("gateway")).thenReturn(gateway);

        assertEquals("gateway", service.resolveEntryAgentId("child"));
    }

    @Test
    void exposesEntryProjectMembershipsOnChildNodes() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        AgentService agentService = mock(AgentService.class);
        ProjectService service = new ProjectService(mapper, agentService);
        User user = new User();
        user.setUserId("user-1");
        user.setPrivilege("normal");
        Project visible = project("user-1", null, "private");
        visible.setProjectId("project-1");

        when(agentService.findAgentById("child")).thenReturn(agent("child", "gateway"));
        when(agentService.findAgentById("gateway")).thenReturn(agent("gateway", "root"));
        when(mapper.findProjectsByAgentId("gateway")).thenReturn(List.of(visible));

        Map<String, List<Project>> result = service.listVisibleMemberships(List.of("child"), user);

        assertEquals(List.of(visible), result.get("child"));
    }

    @Test
    void appliesOwnerAndTeamVisibilityWithoutGrantingPrivateProjects() {
        ProjectService service = new ProjectService(
                mock(ProjectMapper.class), mock(AgentService.class));
        User user = new User();
        user.setUserId("user-1");
        user.setTeamId("team-1");
        user.setPrivilege("normal");

        Project owned = project("user-1", null, "private");
        Project team = project("user-2", "team-1", "team");
        Project privateProject = project("user-2", "team-1", "private");

        assertTrue(service.canView(owned, user));
        assertTrue(service.canView(team, user));
        assertFalse(service.canView(privateProject, user));
    }

    @Test
    void separatesPublicVisibilityFromProjectContentEditing() {
        ProjectService service = new ProjectService(
                mock(ProjectMapper.class), mock(AgentService.class));
        User user = new User();
        user.setUserId("user-1");
        user.setTeamId("team-1");
        user.setPrivilege("normal");

        Project publicProject = project("user-2", "team-2", "public");
        Project teamProject = project("user-2", "team-1", "team");
        Project ownedProject = project("user-1", null, "private");

        assertTrue(service.canView(publicProject, user));
        assertFalse(service.canEditContent(publicProject, user));
        assertTrue(service.canEditContent(teamProject, user));
        assertTrue(service.canEditContent(ownedProject, user));
    }

    @Test
    void deletesRelationsBeforeDeletingProject() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        ProjectService service = new ProjectService(mapper, mock(AgentService.class));
        Project project = projectWithId("project-1");
        when(mapper.deleteAgentRelations("project-1")).thenReturn(3);
        when(mapper.deleteProject("project-1")).thenReturn(1);

        assertEquals(3, service.delete(project));

        InOrder order = inOrder(mapper);
        order.verify(mapper).deleteAgentRelations("project-1");
        order.verify(mapper).deleteProject("project-1");
    }

    @Test
    void reportsConcurrentOrMissingProjectDeletion() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        ProjectService service = new ProjectService(mapper, mock(AgentService.class));
        when(mapper.deleteProject("project-1")).thenReturn(0);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> service.delete(projectWithId("project-1")));

        assertEquals("删除项目失败", error.getMessage());
    }

    private static Project project(String owner, String teamId, String permission) {
        Project project = new Project();
        project.setOwnerUserId(owner);
        project.setTeamId(teamId);
        project.setPermission(permission);
        return project;
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
