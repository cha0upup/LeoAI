package org.leo.service.project;

import org.leo.core.entity.Project;
import org.leo.core.entity.ProjectPuppet;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.security.AccessPolicy;
import org.leo.dao.entity.ProjectPuppetCount;
import org.leo.dao.mapper.ProjectMapper;
import org.leo.service.PuppetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> PERMISSIONS = Set.of("private", "team", "public");
    private static final Set<String> STATUSES = Set.of(Project.STATUS_ACTIVE, Project.STATUS_ARCHIVED);

    private final ProjectMapper projectMapper;
    private final PuppetService puppetService;

    public ProjectService(ProjectMapper projectMapper, PuppetService puppetService) {
        this.projectMapper = projectMapper;
        this.puppetService = puppetService;
    }

    public Project findById(String projectId) {
        if (projectId == null || projectId.isBlank()) return null;
        return projectMapper.findProjectById(projectId.trim());
    }

    public List<Project> listVisible(User user) {
        List<Project> projects = projectMapper.findAllProjects();
        if (projects == null) return List.of();
        return projects.stream().filter(project -> canView(project, user)).toList();
    }

    public boolean canView(Project project, User user) {
        if (project == null || user == null) return false;
        if (AccessPolicy.isAdmin(user)) return true;
        if (user.getUserId() != null && user.getUserId().equals(project.getOwnerUserId())) return true;
        if ("public".equals(project.getPermission())) return true;
        return "team".equals(project.getPermission())
                && user.getTeamId() != null
                && !user.getTeamId().isBlank()
                && user.getTeamId().equals(project.getTeamId());
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
        String now = now();
        input.setProjectId(UUID.randomUUID().toString());
        input.setProjectName(requireName(input.getProjectName()));
        input.setProjectCode(trimToNull(input.getProjectCode()));
        input.setDescription(trimToNull(input.getDescription()));
        input.setStatus(normalizeStatus(input.getStatus()));
        input.setPermission(normalizePermission(input.getPermission()));
        input.setOwnerUserId(owner.getUserId());
        input.setTeamId("team".equals(input.getPermission()) ? owner.getTeamId() : trimToNull(input.getTeamId()));
        input.setCreateTime(now);
        input.setUpdateTime(now);
        if (!projectMapper.insertProject(input)) throw new IllegalStateException("创建项目失败");
        return input;
    }

    public Project update(Project existing, Project changes) {
        if (existing == null || changes == null) throw new IllegalArgumentException("项目参数不能为空");
        existing.setProjectName(requireName(changes.getProjectName()));
        existing.setProjectCode(trimToNull(changes.getProjectCode()));
        existing.setDescription(trimToNull(changes.getDescription()));
        existing.setPermission(normalizePermission(changes.getPermission()));
        existing.setStatus(normalizeStatus(changes.getStatus()));
        if ("team".equals(existing.getPermission())) {
            existing.setTeamId(trimToNull(changes.getTeamId()) != null
                    ? changes.getTeamId().trim() : existing.getTeamId());
        } else {
            existing.setTeamId(trimToNull(changes.getTeamId()));
        }
        existing.setUpdateTime(now());
        if (!projectMapper.updateProject(existing)) throw new IllegalStateException("更新项目失败");
        return existing;
    }

    public Project archive(Project project) {
        project.setStatus(Project.STATUS_ARCHIVED);
        project.setUpdateTime(now());
        if (!projectMapper.updateProject(project)) throw new IllegalStateException("归档项目失败");
        return project;
    }

    /**
     * 永久删除项目及其主机归属关系。主机资产本身不会被删除，历史会话也会保留，
     * 仅解除它们与项目的关联。
     *
     * @return 被解除的入口主机归属数量
     */
    @Transactional
    public int delete(Project project) {
        if (project == null || project.getProjectId() == null || project.getProjectId().isBlank()) {
            throw new IllegalArgumentException("项目参数不能为空");
        }
        String projectId = project.getProjectId().trim();
        int detachedPuppets = projectMapper.deletePuppetRelations(projectId);
        projectMapper.clearSessionProject(projectId);
        if (projectMapper.deleteProject(projectId) != 1) {
            throw new IllegalStateException("删除项目失败");
        }
        return detachedPuppets;
    }

    @Transactional
    public int attachPuppets(String projectId, Collection<String> puppetIds,
                             String alias, String environment, String tags, String userId) {
        int changed = 0;
        for (String puppetId : normalizedEntryIds(puppetIds)) {
            ProjectPuppet relation = new ProjectPuppet();
            relation.setProjectId(projectId);
            relation.setPuppetId(puppetId);
            relation.setAlias(trimToNull(alias));
            relation.setEnvironment(trimToNull(environment));
            relation.setTags(trimToNull(tags));
            relation.setSortOrder(0);
            relation.setAddedByUserId(userId);
            relation.setCreateTime(now());
            if (projectMapper.attachPuppet(relation)) changed++;
        }
        return changed;
    }

    @Transactional
    public int detachPuppets(String projectId, Collection<String> puppetIds) {
        int changed = 0;
        for (String puppetId : normalizedEntryIds(puppetIds)) {
            if (projectMapper.detachPuppet(projectId, puppetId)) changed++;
        }
        return changed;
    }

    public boolean containsPuppet(String projectId, String puppetId) {
        if (projectId == null || puppetId == null) return false;
        String currentId = puppetId;
        for (int depth = 0; depth < 100 && currentId != null && !currentId.isBlank(); depth++) {
            if (projectMapper.containsPuppet(projectId, currentId) > 0) return true;
            Puppet current = puppetService.findPuppetById(currentId);
            if (current == null || current.getParentPuppetId() == null
                    || "root".equals(current.getParentPuppetId())) return false;
            currentId = current.getParentPuppetId();
        }
        return false;
    }

    public Map<String, Integer> countPuppetsByProject() {
        Map<String, Integer> counts = new HashMap<>();
        for (ProjectPuppetCount count : projectMapper.countPuppetsByProject()) {
            counts.put(count.projectId(), count.puppetCount());
        }
        return counts;
    }

    public List<Puppet> listRootPuppets(String projectId) {
        return listPuppetsByParent(projectId, "root");
    }

    public List<Puppet> listPuppetsByParent(String projectId, String parentPuppetId) {
        if ("root".equals(parentPuppetId)) {
            List<Puppet> roots = projectMapper.findPuppetsByProjectAndParent(projectId, parentPuppetId);
            return roots != null ? roots : List.of();
        }
        if (!containsPuppet(projectId, parentPuppetId)) return List.of();
        return puppetService.findPuppetByParentPuppetId(parentPuppetId);
    }

    public List<Puppet> listUnassignedRootPuppets() {
        List<Puppet> list = projectMapper.findUnassignedRootPuppets();
        return list != null ? list : List.of();
    }

    public Map<String, List<Project>> listVisibleMemberships(Collection<String> puppetIds, User user) {
        Map<String, List<Project>> result = new LinkedHashMap<>();
        Map<String, List<Project>> projectsByEntryId = new HashMap<>();
        for (String puppetId : normalizedIds(puppetIds)) {
            String entryId = resolveEntryPuppetId(puppetId);
            if (entryId == null) {
                result.put(puppetId, List.of());
                continue;
            }
            List<Project> memberships = projectsByEntryId.computeIfAbsent(entryId, id -> {
                List<Project> projects = projectMapper.findProjectsByPuppetId(id);
                if (projects == null) return List.of();
                return projects.stream().filter(project -> canView(project, user)).toList();
            });
            result.put(puppetId, memberships);
        }
        return result;
    }

    /**
     * 项目以入口主机为归属单位。用户从树中选中子节点时，自动提升到其入口主机，
     * 避免生成在项目根列表中不可见的孤立关系。
     */
    public String resolveEntryPuppetId(String puppetId) {
        String currentId = trimToNull(puppetId);
        if (currentId == null) return null;
        Set<String> visited = new HashSet<>();
        for (int depth = 0; depth < 100 && visited.add(currentId); depth++) {
            Puppet current = puppetService.findPuppetById(currentId);
            if (current == null) return null;
            String parentId = trimToNull(current.getParentPuppetId());
            if (parentId == null || "root".equals(parentId)) return current.getPuppetId();
            currentId = parentId;
        }
        return null;
    }

    private List<String> normalizedEntryIds(Collection<String> ids) {
        return normalizedIds(ids).stream()
                .map(this::resolveEntryPuppetId)
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

    private static String normalizePermission(String value) {
        String permission = trimToNull(value);
        if (permission == null) permission = "private";
        if (!PERMISSIONS.contains(permission)) throw new IllegalArgumentException("项目权限值无效");
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
