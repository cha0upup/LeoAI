package org.leo.web.controller.platform.project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.entity.Project;
import org.leo.core.entity.User;
import org.leo.service.project.ProjectService;
import org.leo.web.application.agent.AgentDirectoryService;
import org.leo.web.application.agent.SessionLifecycleManager;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectIdRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectSummary;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectControllerTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final SessionLifecycleManager sessionLifecycle = mock(SessionLifecycleManager.class);
    private final ProjectController controller = new ProjectController(
            projectService, permissionService, mock(AgentDirectoryService.class), sessionLifecycle);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final User user = new User();

    @BeforeEach
    void login() {
        when(permissionService.requireLogin(request)).thenReturn(user);
    }

    @ParameterizedTest
    @CsvSource({"false, 400", "false, 403", "true, 400", "true, 403"})
    void translatesProjectWriteFailuresAtTheHttpBoundary(boolean update, int code) {
        Project input = project("project-1");
        RuntimeException failure = code == 403
                ? new SecurityException("没有公开项目权限") : new IllegalArgumentException("项目参数无效");
        if (update) {
            Project existing = project("project-1");
            when(projectService.findById("project-1")).thenReturn(existing);
            when(projectService.canView(existing, user)).thenReturn(true);
            when(projectService.canManage(existing, user)).thenReturn(true);
            when(projectService.update(existing, input, user)).thenThrow(failure);
        } else {
            when(projectService.create(input, user)).thenThrow(failure);
        }

        ApiException error = assertThrows(ApiException.class, () -> {
            if (update) controller.updateProject(request, input);
            else controller.createProject(request, input);
        });

        assertEquals(code, error.getCode());
        assertEquals(failure.getMessage(), error.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesGroupedHostAndSessionCountsWithoutChangingProjectOrder() {
        user.setUserId("user-1");
        Project first = project("project-2");
        Project second = project("project-1");
        Set<String> visibleProjectIds = Set.of("project-2", "project-1");
        when(projectService.listVisible(user)).thenReturn(List.of(first, second));
        when(projectService.countAgentsByProject(visibleProjectIds)).thenReturn(Map.of("project-1", 3));
        when(sessionLifecycle.countByProject(user, visibleProjectIds)).thenReturn(Map.of("project-1", 2));
        when(projectService.canManage(first, user)).thenReturn(true);
        when(projectService.canEditContent(first, user)).thenReturn(true);

        List<ProjectSummary> result = (List<ProjectSummary>) controller.listProjects(request).get("data");

        assertEquals(List.of(
                new ProjectSummary(first, 0, 0, true, true),
                new ProjectSummary(second, 3, 2, false, false)), result);
        verify(projectService).countAgentsByProject(visibleProjectIds);
        verify(sessionLifecycle).countByProject(user, visibleProjectIds);
    }

    @Test
    void skipsHostAndSessionAggregationWhenNoProjectIsVisible() {
        when(projectService.listVisible(user)).thenReturn(List.of());

        assertEquals(List.of(), controller.listProjects(request).get("data"));
        verify(projectService, never()).countAgentsByProject(anyCollection());
        verifyNoInteractions(sessionLifecycle);
    }

    @Test
    @SuppressWarnings("unchecked")
    void detachesSessionsOnlyAfterDeletingTheManageableProject() {
        Project project = project("project-1");
        when(projectService.findById("project-1")).thenReturn(project);
        when(projectService.canView(project, user)).thenReturn(true);
        when(projectService.canManage(project, user)).thenReturn(true);
        when(projectService.delete(project)).thenReturn(2);
        when(sessionLifecycle.detachProject("project-1")).thenReturn(1);

        Map<String, Object> response = controller.deleteProject(
                request, new ProjectIdRequest("project-1"));
        Map<String, Object> data = (Map<String, Object>) response.get("data");

        assertEquals(200, response.get("code"));
        assertEquals("project-1", data.get("projectId"));
        assertEquals(2, data.get("detachedHostCount"));
        assertEquals(1, data.get("retainedSessionCount"));
        var order = inOrder(projectService, sessionLifecycle);
        order.verify(projectService).delete(project);
        order.verify(sessionLifecycle).detachProject("project-1");
    }

    @Test
    void rejectsDeletionWhenProjectIsNotManageable() {
        Project project = project("project-1");
        when(projectService.findById("project-1")).thenReturn(project);
        when(projectService.canView(project, user)).thenReturn(true);
        when(projectService.canManage(project, user)).thenReturn(false);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.deleteProject(request, new ProjectIdRequest("project-1")));

        assertEquals(403, error.getCode());
        verify(projectService, never()).delete(project);
        verifyNoInteractions(sessionLifecycle);
    }

    private static Project project(String projectId) {
        Project project = new Project();
        project.setProjectId(projectId);
        project.setProjectName("测试项目");
        return project;
    }

}
