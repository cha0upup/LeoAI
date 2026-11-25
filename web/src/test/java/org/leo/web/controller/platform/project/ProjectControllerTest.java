package org.leo.web.controller.platform.project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.Project;
import org.leo.core.entity.User;
import org.leo.service.project.ProjectService;
import org.leo.web.application.agent.AgentDirectoryService;
import org.leo.web.application.agent.SessionLifecycleManager;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectIdRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
