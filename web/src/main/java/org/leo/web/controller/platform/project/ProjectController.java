package org.leo.web.controller.platform.project;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Agent;
import org.leo.core.entity.Project;
import org.leo.core.entity.ProjectListItem;
import org.leo.core.entity.User;
import org.leo.core.security.AccessPolicy;
import org.leo.core.util.ApiResponse;
import org.leo.service.project.ProjectService;
import org.leo.web.application.agent.AgentDirectoryService;
import org.leo.web.application.agent.SessionLifecycleManager;
import org.leo.web.dto.platform.agent.AgentDirectoryEntry;
import org.leo.web.dto.platform.project.ProjectDtos.AgentMembershipsRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectAgentsRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectChildrenRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectIdRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectMembershipSummary;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectOwnerTransferRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectSummary;
import org.leo.web.dto.platform.project.ProjectDtos.RelationMutationResponse;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/platform/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final PermissionService permissionService;
    private final AgentDirectoryService directoryService;
    private final SessionLifecycleManager sessionLifecycle;

    public ProjectController(ProjectService projectService, PermissionService permissionService,
                             AgentDirectoryService directoryService, SessionLifecycleManager sessionLifecycle) {
        this.projectService = projectService;
        this.permissionService = permissionService;
        this.directoryService = directoryService;
        this.sessionLifecycle = sessionLifecycle;
    }

    @GetMapping
    public Map<String, Object> listProjects(HttpServletRequest request) {
        User user = permissionService.requireLogin(request);
        List<ProjectListItem> visibleProjects = projectService.listVisible(user);
        if (visibleProjects.isEmpty()) return ApiResponse.success(List.of());
        Set<String> visibleProjectIds = new HashSet<>();
        for (ProjectListItem row : visibleProjects) {
            visibleProjectIds.add(row.projectId());
        }
        Map<String, Integer> sessionCounts = sessionLifecycle.countByProject(user, visibleProjectIds);
        List<ProjectSummary> projects = new ArrayList<>(visibleProjects.size());
        for (ProjectListItem row : visibleProjects) {
            Project project = row.project();
            projects.add(new ProjectSummary(project, row.hostCount(), sessionCounts.getOrDefault(row.projectId(), 0),
                    projectService.canManage(project, user), projectService.canEditContent(project, user)));
        }
        return ApiResponse.success(projects);
    }

    @PostMapping
    public Map<String, Object> createProject(HttpServletRequest request,
                                              @RequestBody Project input) {
        User user = permissionService.requireLogin(request);
        try {
            return ApiResponse.success(projectService.create(input, user));
        } catch (SecurityException error) {
            throw ApiException.forbidden(error.getMessage());
        } catch (IllegalArgumentException error) {
            throw ApiException.badRequest(error.getMessage());
        }
    }

    @PostMapping("/update")
    public Map<String, Object> updateProject(HttpServletRequest request,
                                              @RequestBody Project input) {
        User user = permissionService.requireLogin(request);
        Project project = requireManageable(input != null ? input.getProjectId() : null, user);
        try {
            return ApiResponse.success(projectService.update(project, input, user));
        } catch (SecurityException error) {
            throw ApiException.forbidden(error.getMessage());
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

    @PostMapping("/restore")
    public Map<String, Object> restoreProject(HttpServletRequest request, @RequestBody ProjectIdRequest body) {
        User user = permissionService.requireLogin(request);
        Project project = requireManageable(body != null ? body.projectId() : null, user);
        return ApiResponse.success(projectService.restore(project));
    }

    @PostMapping("/transfer")
    public Map<String, Object> transferProject(HttpServletRequest request, @RequestBody ProjectOwnerTransferRequest body) {
        User user = permissionService.requireLogin(request);
        if (!AccessPolicy.isAdmin(user)) throw ApiException.forbidden("只有管理员可以交接项目负责人");
        Project project = requireManageable(body != null ? body.projectId() : null, user);
        String newOwnerId = PermissionService.requireText(body.newOwnerUserId(), "newOwnerUserId不能为空");
        if (!projectService.transferOwner(project, newOwnerId)) {
            throw ApiException.conflict("接任账号不存在或未启用，或项目负责人已变更");
        }
        return ApiResponse.success(Map.of("projectId", project.getProjectId(), "ownerUserId", newOwnerId));
    }

    @PostMapping("/delete")
    public Map<String, Object> deleteProject(HttpServletRequest request,
                                              @RequestBody ProjectIdRequest body) {
        User user = permissionService.requireLogin(request);
        Project project = requireManageable(body != null ? body.projectId() : null, user);
        String projectId = project.getProjectId();
        int detachedHostCount = projectService.delete(project);
        int retainedSessionCount = sessionLifecycle.detachProject(projectId);
        return ApiResponse.success(Map.of(
                "projectId", projectId,
                "detachedHostCount", detachedHostCount,
                "retainedSessionCount", retainedSessionCount));
    }

    @GetMapping("/{projectId}/agents")
    public Map<String, Object> listRootAgents(HttpServletRequest request,
                                               @PathVariable String projectId) {
        User user = permissionService.requireLogin(request);
        Project project = requireVisible(projectId, user);
        return ApiResponse.success(filterAccessible(projectService.listRootAgents(project.getProjectId()), user));
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
        Project project = requireVisible(projectId, user);
        String parentId = PermissionService.requireText(
                body != null ? body.parentAgentId() : null, "parentAgentId不能为空");
        return ApiResponse.success(filterAccessible(
                projectService.listAgentsByParent(project.getProjectId(), parentId), user));
    }

    @PostMapping("/hosts/attach")
    public Map<String, Object> attachAgents(HttpServletRequest request,
                                              @RequestBody ProjectAgentsRequest body) {
        User user = permissionService.requireLogin(request);
        Project project = requireContentEditable(body != null ? body.projectId() : null, user);
        String projectId = project.getProjectId();
        if (Project.STATUS_ARCHIVED.equals(project.getStatus())) {
            throw ApiException.badRequest("已归档项目不能添加主机");
        }
        List<String> agentIds = body.agentIds() != null ? body.agentIds() : List.of();
        if (agentIds.isEmpty()) throw ApiException.badRequest("agentIds不能为空");
        for (String agentId : agentIds) {
            permissionService.requireAccessibleAgentChain(agentId, user);
        }
        int changed = projectService.attachAgents(projectId, agentIds,
                body.alias(), body.tags(), user.getUserId());
        return ApiResponse.success(new RelationMutationResponse(projectId, changed));
    }

    @PostMapping("/hosts/detach")
    public Map<String, Object> detachAgents(HttpServletRequest request,
                                              @RequestBody ProjectAgentsRequest body) {
        User user = permissionService.requireLogin(request);
        Project project = requireContentEditable(body != null ? body.projectId() : null, user);
        String projectId = project.getProjectId();
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
        return AgentDirectoryEntry.from(
                directoryService.describe(agents, agent -> permissionService.canAccessAgentChain(agent, user)));
    }

}
