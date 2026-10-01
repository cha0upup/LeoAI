package org.leo.web.controller.platform.project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.Project;
import org.leo.core.entity.User;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.project.ProjectService;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectIdRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectSummary;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void usesOneSessionSnapshotAndOneGroupedHostCountWithoutChangingProjectOrder() {
        ProjectService projectService = mock(ProjectService.class);
        PermissionService permissionService = mock(PermissionService.class);
        ProjectController controller = new ProjectController(projectService, permissionService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        user.setUserId("user-1");
        Project first = project("project-2");
        Project second = project("project-1");
        when(permissionService.requireLogin(request)).thenReturn(user);
        when(projectService.listVisible(user)).thenReturn(List.of(first, second));
        when(projectService.countAgentsByProject()).thenReturn(Map.of("project-1", 3));
        when(projectService.canManage(first, user)).thenReturn(true);
        when(projectService.canEditContent(first, user)).thenReturn(true);

        try (var sessions = mockStatic(AgentNodeSessionContainer.class)) {
            sessions.when(AgentNodeSessionContainer::getAllSessions).thenReturn(Map.of(
                    "own-1", session("project-1", "user-1"),
                    "own-2", session("project-1", "user-1"),
                    "other", session("project-1", "user-2"),
                    "unassigned", session(null, "user-1"),
                    "hidden", session("hidden", "user-1")));

            List<ProjectSummary> result = (List<ProjectSummary>) controller.listProjects(request).get("data");

            assertEquals(List.of(
                    new ProjectSummary(first, 0, 0, true, true),
                    new ProjectSummary(second, 3, 2, false, false)), result);
            verify(projectService).countAgentsByProject();
            sessions.verify(AgentNodeSessionContainer::getAllSessions);
        }
    }

    @Test
    void skipsHostAndSessionAggregationWhenNoProjectIsVisible() {
        ProjectService projectService = mock(ProjectService.class);
        PermissionService permissionService = mock(PermissionService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        when(permissionService.requireLogin(request)).thenReturn(user);
        when(projectService.listVisible(user)).thenReturn(List.of());

        try (var sessions = mockStatic(AgentNodeSessionContainer.class)) {
            assertEquals(List.of(), new ProjectController(projectService, permissionService)
                    .listProjects(request).get("data"));
            verify(projectService, never()).countAgentsByProject();
            sessions.verifyNoInteractions();
        }
    }

    @AfterEach
    void clearSessions() {
        AgentNodeSessionContainer.clearAllSessions();
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletesManageableProjectAndKeepsLiveSessionInGlobalWorkspace() {
        ProjectService projectService = mock(ProjectService.class);
        PermissionService permissionService = mock(PermissionService.class);
        ProjectController controller = new ProjectController(projectService, permissionService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        Project project = project("project-1");
        when(permissionService.requireLogin(request)).thenReturn(user);
        when(projectService.findById("project-1")).thenReturn(project);
        when(projectService.canView(project, user)).thenReturn(true);
        when(projectService.canManage(project, user)).thenReturn(true);
        when(projectService.delete(project)).thenReturn(2);
        AgentNodeSession session = new AgentNodeSession();
        session.setProjectId("project-1");
        AgentNodeSessionContainer.addSession("session-1", session);

        Map<String, Object> response = controller.deleteProject(
                request, new ProjectIdRequest("project-1"));
        Map<String, Object> data = (Map<String, Object>) response.get("data");

        assertEquals(200, response.get("code"));
        assertEquals("project-1", data.get("projectId"));
        assertEquals(2, data.get("detachedHostCount"));
        assertEquals(1, data.get("retainedSessionCount"));
        assertNull(session.getProjectId());
    }

    @Test
    void rejectsDeletionWhenProjectIsNotManageable() {
        ProjectService projectService = mock(ProjectService.class);
        PermissionService permissionService = mock(PermissionService.class);
        ProjectController controller = new ProjectController(projectService, permissionService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        Project project = project("project-1");
        when(permissionService.requireLogin(request)).thenReturn(user);
        when(projectService.findById("project-1")).thenReturn(project);
        when(projectService.canView(project, user)).thenReturn(true);
        when(projectService.canManage(project, user)).thenReturn(false);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.deleteProject(request, new ProjectIdRequest("project-1")));

        assertEquals(403, error.getCode());
        verify(projectService, never()).delete(project);
    }

    private static Project project(String projectId) {
        Project project = new Project();
        project.setProjectId(projectId);
        project.setProjectName("测试项目");
        return project;
    }

    private static AgentNodeSession session(String projectId, String ownerId) {
        AgentNodeSession session = new AgentNodeSession();
        session.setProjectId(projectId);
        session.setCreateByUser(ownerId);
        return session;
    }
}
