package org.leo.service.project;

import org.leo.core.entity.Project;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.security.AccessPolicy;
import org.leo.core.util.json.JsonUtil;
import org.leo.dao.mapper.ProjectMapper;
import org.leo.service.agentnode.AgentService;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> PERMISSIONS = Set.of("private", "team", "public");
    private static final Set<String> STATUSES = Set.of(Project.STATUS_ACTIVE, Project.STATUS_ARCHIVED);
    private static final int MAX_PARENT_DEPTH = 100;

    private final ProjectMapper projectMapper;
    private final AgentService agentService;
    private final UserService users;
    private final TeamService teams;

    public ProjectService(ProjectMapper projectMapper, AgentService agentService, UserService users, TeamService teams) {
        this.projectMapper = projectMapper;
        this.agentService = agentService;
        this.users = users;
        this.teams = teams;
    }

    public Project findById(String projectId) {
        if (projectId == null || projectId.isBlank()) return null;
        return projectMapper.findProjectById(projectId.trim());
    }

    public List<Project> listVisible(User user) {
        if (user == null) return List.of();
        List<Project> projects = projectMapper.findVisibleProjectCandidates(user.getUserId(),
                visibilityTeamId(user), AccessPolicy.isAdmin(user));
        if (projects == null) return List.of();
        return projects.stream().filter(project -> canView(project, user)).toList();
    }

    private static String visibilityTeamId(User user) {
        String teamId = user.getTeamId();
        return teamId == null || teamId.isBlank() ? null : teamId;
    }

    public boolean canView(Project project, User user) {
        return project != null && user != null
                && ("public".equals(project.getPermission()) || canEditContent(project, user));
    }

    public boolean canManage(Project project, User user) {
        return project != null && user != null
                && (AccessPolicy.isAdmin(user)
                || user.getUserId() != null && user.getUserId().equals(project.getOwnerUserId()));
    }

    /**
     * 项目内容（主机归属）允许项目负责人、管理员以及同团队成员维护。
     * public 仅代表可见，避免任意可见用户修改他人的项目资产清单。
     */
    public boolean canEditContent(Project project, User user) {
        if (canManage(project, user)) return true;
        return project != null && user != null
                && "team".equals(project.getPermission())
                && user.getTeamId() != null
                && !user.getTeamId().isBlank()
                && user.getTeamId().equals(project.getTeamId());
    }

    public Project create(Project input, User owner) {
        if (input == null || owner == null) throw new IllegalArgumentException("项目参数不能为空");
        String permission = normalizePermission(input.getPermission(), owner);
        String name = requireName(input.getProjectName());
        String status = normalizeStatus(input.getStatus());
        String teamId = resolveTeamId(permission, null, owner);
        String now = now();
        input.setProjectId(UUID.randomUUID().toString());
        input.setProjectName(name);
        input.setProjectCode(trimToNull(input.getProjectCode()));
        input.setDescription(trimToNull(input.getDescription()));
        input.setStatus(status);
        input.setPermission(permission);
        input.setOwnerUserId(owner.getUserId());
        input.setTeamId(teamId);
        input.setCreateTime(now);
        input.setUpdateTime(now);
        if (!projectMapper.insertProject(input)) throw new IllegalStateException("创建项目失败");
        return input;
    }

    public Project update(Project existing, Project changes, User actor) {
        if (existing == null || changes == null || actor == null) throw new IllegalArgumentException("项目参数不能为空");
        String permission = normalizePermission(changes.getPermission(), actor);
        String name = requireName(changes.getProjectName());
        String status = normalizeStatus(changes.getStatus());
        String teamId = resolveTeamId(permission, existing, actor);
        existing.setProjectName(name);
        existing.setProjectCode(trimToNull(changes.getProjectCode()));
        existing.setDescription(trimToNull(changes.getDescription()));
        existing.setPermission(permission);
        existing.setStatus(status);
        existing.setTeamId(teamId);
        existing.setUpdateTime(now());
        if (!projectMapper.updateProject(existing)) throw new IllegalStateException("更新项目失败");
        return existing;
    }

    private String resolveTeamId(String permission, Project existing, User actor) {
        if (!AccessPolicy.PERMISSION_TEAM.equals(permission)) return null;
        // Keep the existing audience when editing a shared project, even if its owner moved teams.
        String teamId = existing != null && AccessPolicy.PERMISSION_TEAM.equals(existing.getPermission())
                ? trimToNull(existing.getTeamId()) : null;
        if (teamId == null) {
            User owner = existing == null || Objects.equals(existing.getOwnerUserId(), actor.getUserId())
                    ? actor : users.getUserById(existing.getOwnerUserId());
            teamId = owner == null ? null : trimToNull(owner.getTeamId());
            if (teamId == null) throw new IllegalArgumentException("团队协作需要项目负责人先加入团队");
        }
        if (!teams.existsTeamById(teamId)) {
            throw new IllegalArgumentException("项目所属团队不存在，请检查团队归属或调整共享范围");
        }
        return teamId;
    }

    public Project archive(Project project) {
        Project archived = projectMapper.archiveProject(project.getProjectId(), project.getOwnerUserId(), now());
        if (archived == null) throw new IllegalStateException("归档项目失败");
        return archived;
    }

    public boolean transferOwner(Project project, String newOwnerId) {
        return projectMapper.transferOwner(project.getProjectId(), project.getOwnerUserId(), newOwnerId, now());
    }

    /**
     * 永久删除项目及其主机归属关系，保留主机资产本身。
     * 运行中会话的项目关联由 Web 应用层解除。
     *
     * @return 被解除的入口主机归属数量
     */
    @Transactional
    public int delete(Project project) {
        if (project == null || project.getProjectId() == null || project.getProjectId().isBlank()) {
            throw new IllegalArgumentException("项目参数不能为空");
        }
        String projectId = project.getProjectId().trim();
        int detachedAgents = projectMapper.deleteAgentRelations(projectId);
        if (projectMapper.deleteProject(projectId, project.getOwnerUserId()) != 1) {
            throw new IllegalStateException("删除项目失败");
        }
        return detachedAgents;
    }

    @Transactional
    public int attachAgents(String projectId, Collection<String> agentIds,
                             String alias, String tags, String userId) {
        List<String> entryIds = normalizedEntryIds(agentIds);
        if (entryIds.isEmpty()) return 0;
        return projectMapper.attachAgents(projectId, JsonUtil.toJsonString(entryIds),
                trimToNull(alias), trimToNull(tags), userId, now());
    }

    @Transactional
    public int detachAgents(String projectId, Collection<String> agentIds) {
        List<String> entryIds = normalizedEntryIds(agentIds);
        return entryIds.isEmpty() ? 0 : projectMapper.detachAgents(projectId, JsonUtil.toJsonString(entryIds));
    }

    public boolean containsAgent(String projectId, String agentId) {
        if (projectId == null || agentId == null) return false;
        String currentId = agentId;
        Set<String> visited = new HashSet<>();
        for (int depth = 0; depth < MAX_PARENT_DEPTH && currentId != null
                && !currentId.isBlank() && visited.add(currentId); depth++) {
            if (projectMapper.containsAgent(projectId, currentId) > 0) return true;
            Agent current = agentService.findAgentById(currentId);
            if (current == null || current.getParentAgentId() == null
                    || "root".equals(current.getParentAgentId())) return false;
            currentId = current.getParentAgentId();
        }
        return false;
    }

    public Map<String, Integer> countAgentsByProject(Collection<String> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) return Map.of();
        Map<String, Integer> counts = new HashMap<>();
        for (var count : projectMapper.countAgentsByProject(JsonUtil.toJsonString(projectIds))) {
            counts.put(count.projectId(), count.agentCount());
        }
        return counts;
    }

    public List<Agent> listRootAgents(String projectId) {
        return listAgentsByParent(projectId, "root");
    }

    public List<Agent> listAgentsByParent(String projectId, String parentAgentId) {
        if ("root".equals(parentAgentId)) {
            List<Agent> roots = projectMapper.findAgentsByProjectAndParent(projectId, parentAgentId);
            return roots != null ? roots : List.of();
        }
        if (!containsAgent(projectId, parentAgentId)) return List.of();
        return agentService.findAgentsByParentAgentId(parentAgentId);
    }

    public List<Agent> listUnassignedRootAgents() {
        List<Agent> list = projectMapper.findUnassignedRootAgents();
        return list != null ? list : List.of();
    }

    public Map<String, List<Project>> listVisibleMemberships(Collection<String> agentIds, User user) {
        Map<String, String> entryByAgentId = new LinkedHashMap<>();
        Map<String, Agent> ancestors = new HashMap<>();
        for (String agentId : normalizedIds(agentIds)) {
            entryByAgentId.put(agentId, resolveEntryAgentId(agentId, ancestors));
        }
        List<String> entryIds = entryByAgentId.values().stream().filter(Objects::nonNull).distinct().toList();
        Map<String, List<Project>> projectsByEntryId = new HashMap<>();
        if (user != null && !entryIds.isEmpty()) {
            for (var membership : projectMapper.findVisibleMembershipsByEntryAgentIds(JsonUtil.toJsonString(entryIds),
                    user.getUserId(), visibilityTeamId(user), AccessPolicy.isAdmin(user))) {
                if (canView(membership.project(), user)) {
                    projectsByEntryId.computeIfAbsent(membership.entryAgentId(), id -> new ArrayList<>())
                            .add(membership.project());
                }
            }
            projectsByEntryId.replaceAll((id, projects) -> List.copyOf(projects));
        }
        Map<String, List<Project>> result = new LinkedHashMap<>();
        entryByAgentId.forEach((agentId, entryId) -> result.put(agentId,
                projectsByEntryId.getOrDefault(entryId, List.of())));
        return result;
    }

    /**
     * 项目以入口主机为归属单位。用户从树中选中子节点时，自动提升到其入口主机，
     * 避免生成在项目根列表中不可见的孤立关系。
     */
    private String resolveEntryAgentId(String agentId, Map<String, Agent> ancestors) {
        String currentId = trimToNull(agentId);
        if (currentId == null) return null;
        Set<String> visited = new HashSet<>();
        for (int depth = 0; depth < MAX_PARENT_DEPTH && visited.add(currentId); depth++) {
            // Missing ancestors are also reused within this operation.
            if (!ancestors.containsKey(currentId)) {
                ancestors.put(currentId, agentService.findAgentById(currentId));
            }
            Agent current = ancestors.get(currentId);
            if (current == null) return null;
            String parentId = trimToNull(current.getParentAgentId());
            if (parentId == null || "root".equals(parentId)) return current.getAgentId();
            currentId = parentId;
        }
        return null;
    }

    private List<String> normalizedEntryIds(Collection<String> ids) {
        Map<String, Agent> ancestors = new HashMap<>();
        return normalizedIds(ids).stream()
                .map(id -> resolveEntryAgentId(id, ancestors))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> normalizedIds(Collection<String> ids) {
        if (ids == null) return List.of();
        return ids.stream().filter(id -> id != null && !id.isBlank())
                .map(String::trim).distinct().limit(500).toList();
    }

    private static String requireName(String value) {
        String name = trimToNull(value);
        if (name == null) throw new IllegalArgumentException("项目名称不能为空");
        if (name.length() > 100) throw new IllegalArgumentException("项目名称长度不能超过100个字符");
        return name;
    }

    private static String normalizePermission(String value, User actor) {
        String permission = trimToNull(value);
        if (permission == null) permission = "private";
        if (!PERMISSIONS.contains(permission)) throw new IllegalArgumentException("项目权限值无效");
        if ("public".equals(permission) && !AccessPolicy.isAdmin(actor)) {
            throw new SecurityException("只有管理员可以设置公开项目");
        }
        return permission;
    }

    private static String normalizeStatus(String value) {
        String status = trimToNull(value);
        if (status == null) status = Project.STATUS_ACTIVE;
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("项目状态值无效");
        return status;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String now() { return DATE_FORMAT.format(LocalDateTime.now()); }
}
