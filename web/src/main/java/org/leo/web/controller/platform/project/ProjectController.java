package org.leo.web.controller.platform.project;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Project;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.core.util.ApiResponse;
import org.leo.service.project.ProjectService;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectChildrenRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectIdRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectMembershipSummary;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectAgentsRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectSummary;
import org.leo.web.dto.platform.project.ProjectDtos.AgentMembershipsRequest;
import org.leo.web.dto.platform.project.ProjectDtos.RelationMutationResponse;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionPolicy;
import org.leo.web.security.PermissionService;
import org.leo.web.service.AgentDirectoryService;
import org.leo.web.dto.platform.agent.AgentDirectoryEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/platform/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final PermissionService permissionService;
    private final AgentDirectoryService directoryService;

    public ProjectController(ProjectService projectService, PermissionService permissionService,
                             AgentDirectoryService directoryService) {
        this.projectService = projectService;
        this.permissionService = permissionService;
        this.directoryService = directoryService;
    }

    @GetMapping
    public Map<String, Object> listProjects(HttpServletRequest request) {
        User user = permissionService.requireLogin(request);
        List<Project> visibleProjects = projectService.listVisible(user);
        if (visibleProjects.isEmpty()) return ApiResponse.success(List.of());
        Map<String, Integer> hostCounts = projectService.countAgentsByProject();
        Map<String, Integer> sessionCounts = countActiveSessionsByProject(user);
        List<ProjectSummary> projects = visibleProjects.stream()
                .map(project -> new ProjectSummary(
                        project,
                        hostCounts.getOrDefault(project.getProjectId(), 0),
                        sessionCounts.getOrDefault(project.getProjectId(), 0),
                        projectService.canManage(project, user),
                        projectService.canEditContent(project, user)))
                .toList();
        return ApiResponse.success(projects);
    }

    @PostMapping
    public Map<String, Object> createProject(HttpServletRequest request,
                                              @RequestBody Project input) {
        User user = permissionService.requireLogin(request);
        if (input != null && "public".equals(input.getPermission())
                && !PermissionPolicy.isAdmin(user)) {
            throw ApiException.forbidden("只有管理员可以创建公开项目");
        }
        try {
            return ApiResponse.success(projectService.create(input, user));
        } catch (IllegalArgumentException error) {
            throw ApiException.badRequest(error.getMessage());
        }
    }

    @PostMapping("/update")
    public Map<String, Object> updateProject(HttpServletRequest request,
                                              @RequestBody Project input) {
        User user = permissionService.requireLogin(request);
        Project project = requireManageable(input != null ? input.getProjectId() : null, user);
        if ("public".equals(input.getPermission()) && !PermissionPolicy.isAdmin(user)) {
            throw ApiException.forbidden("只有管理员可以设置公开项目");
        }
        try {
            return ApiResponse.success(projectService.update(project, input));
        } catch (IllegalArgumentException error) {
            throw ApiException.badRequest(error.getMessage());
        }
    }

    @PostMapping("/archive")
    public Map<String, Object> archiveProject(HttpServletRequest request,
                                               @RequestBody ProjectIdRequest body) {
        User user = permissionService.requireLogin(request);
        Project project = requireManageable(body != null ? body.projectId() : null, user);
        return ApiResponse.success(projectService.archive(project));
    }

    @PostMapping("/delete")
    public Map<String, Object> deleteProject(HttpServletRequest request,
                                              @RequestBody ProjectIdRequest body) {
        User user = permissionService.requireLogin(request);
        Project project = requireManageable(body != null ? body.projectId() : null, user);
        String projectId = project.getProjectId();
        int detachedHostCount = projectService.delete(project);
        int retainedSessionCount = clearProjectFromActiveSessions(projectId);
        return ApiResponse.success(Map.of(
                "projectId", projectId,
                "detachedHostCount", detachedHostCount,
                "retainedSessionCount", retainedSessionCount));
    }

    @GetMapping("/{projectId}/agents")
    public Map<String, Object> listRootAgents(HttpServletRequest request,
                                               @PathVariable String projectId) {
        User user = permissionService.requireLogin(request);
        requireVisible(projectId, user);
        return ApiResponse.success(filterAccessible(projectService.listRootAgents(projectId), user));
    }

    @GetMapping("/unassigned/agents")
    public Map<String, Object> listUnassignedAgents(HttpServletRequest request) {
        User user = permissionService.requireLogin(request);
        return ApiResponse.success(filterAccessible(projectService.listUnassignedRootAgents(), user));
    }

    @PostMapping("/{projectId}/children")
    public Map<String, Object> listChildren(HttpServletRequest request,
                                            @PathVariable String projectId,
                                            @RequestBody ProjectChildrenRequest body) {
        User user = permissionService.requireLogin(request);
        requireVisible(projectId, user);
        String parentId = PermissionService.requireText(
                body != null ? body.parentAgentId() : null, "parentAgentId不能为空");
        return ApiResponse.success(filterAccessible(
                projectService.listAgentsByParent(projectId, parentId), user));
    }

    @PostMapping("/hosts/attach")
    public Map<String, Object> attachAgents(HttpServletRequest request,
                                              @RequestBody ProjectAgentsRequest body) {
        User user = permissionService.requireLogin(request);
        String projectId = body != null ? body.projectId() : null;
        Project project = requireContentEditable(projectId, user);
        if (Project.STATUS_ARCHIVED.equals(project.getStatus())) {
            throw ApiException.badRequest("已归档项目不能添加主机");
        }
        List<String> agentIds = body.agentIds() != null ? body.agentIds() : List.of();
        if (agentIds.isEmpty()) throw ApiException.badRequest("agentIds不能为空");
        for (String agentId : agentIds) {
            permissionService.requireAccessibleAgentChain(agentId, user);
        }
        int changed = projectService.attachAgents(projectId, agentIds,
                body.alias(), body.environment(), body.tags(), user.getUserId());
        return ApiResponse.success(new RelationMutationResponse(projectId, changed));
    }

    @PostMapping("/hosts/detach")
    public Map<String, Object> detachAgents(HttpServletRequest request,
                                              @RequestBody ProjectAgentsRequest body) {
        User user = permissionService.requireLogin(request);
        String projectId = body != null ? body.projectId() : null;
        requireContentEditable(projectId, user);
        List<String> agentIds = body.agentIds() != null ? body.agentIds() : List.of();
        if (agentIds.isEmpty()) throw ApiException.badRequest("agentIds不能为空");
        int changed = projectService.detachAgents(projectId, agentIds);
        return ApiResponse.success(new RelationMutationResponse(projectId, changed));
    }

    @PostMapping("/hosts/memberships")
    public Map<String, Object> listMemberships(HttpServletRequest request,
                                                @RequestBody AgentMembershipsRequest body) {
        User user = permissionService.requireLogin(request);
        List<String> agentIds = body != null && body.agentIds() != null
                ? body.agentIds().stream().filter(id -> id != null && !id.isBlank())
                .map(String::trim).distinct().limit(500).toList()
                : List.of();
        Map<String, List<ProjectMembershipSummary>> result = new LinkedHashMap<>();
        if (agentIds.isEmpty()) return ApiResponse.success(result);
        for (String agentId : agentIds) {
            permissionService.requireAccessibleAgentChain(agentId, user);
        }
        projectService.listVisibleMemberships(agentIds, user).forEach((agentId, projects) ->
                result.put(agentId, projects.stream().map(ProjectMembershipSummary::new).toList()));
        return ApiResponse.success(result);
    }

    private Project requireVisible(String projectId, User user) {
        String id = PermissionService.requireText(projectId, "projectId不能为空");
        Project project = projectService.findById(id);
        if (project == null) throw ApiException.notFound("项目不存在");
        if (!projectService.canView(project, user)) throw ApiException.forbidden("无权限访问此项目");
        return project;
    }

    private Project requireManageable(String projectId, User user) {
        Project project = requireVisible(projectId, user);
        if (!projectService.canManage(project, user)) throw ApiException.forbidden("无权限管理此项目");
        return project;
    }

    private Project requireContentEditable(String projectId, User user) {
        Project project = requireVisible(projectId, user);
        if (!projectService.canEditContent(project, user)) {
            throw ApiException.forbidden("无权限维护此项目的主机归属");
        }
        return project;
    }

    private List<AgentDirectoryEntry> filterAccessible(List<Agent> agents, User user) {
        return directoryService.describe(agents, agent -> permissionService.canAccessAgentChain(agent, user));
    }

    private Map<String, Integer> countActiveSessionsByProject(User user) {
        Map<String, Integer> counts = new HashMap<>();
        for (AgentNodeSession session : AgentNodeSessionContainer.getAllSessions().values()) {
            if (session == null) continue;
            String projectId = session.getProjectId();
            if (projectId != null && PermissionPolicy.canAccessSession(session, user)) {
                counts.merge(projectId, 1, Integer::sum);
            }
        }
        return counts;
    }

    private int clearProjectFromActiveSessions(String projectId) {
        int changed = 0;
        for (AgentNodeSession session : AgentNodeSessionContainer.getAllSessions().values()) {
            if (session != null && projectId.equals(session.getProjectId())) {
                session.setProjectId(null);
                changed++;
            }
        }
        return changed;
    }
}
