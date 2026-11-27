package org.leo.web.application.project;

import org.leo.core.entity.Agent;
import org.leo.core.entity.Project;
import org.leo.core.entity.User;
import org.leo.service.project.ProjectService;
import org.leo.web.application.UseCaseException;
import org.leo.web.application.agent.AgentDirectoryService;
import org.leo.web.application.agent.AgentDirectoryService.Entry;
import org.leo.web.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 项目主机目录和归属查询使用各自一致的只读快照。 */
@Service
public class ProjectReadService {
    private final ProjectService projects;
    private final PermissionService permissions;
    private final AgentDirectoryService directory;

    public ProjectReadService(ProjectService projects, PermissionService permissions, AgentDirectoryService directory) {
        this.projects = projects;
        this.permissions = permissions;
        this.directory = directory;
    }

    public Project requireVisibleProject(String projectId, User user) {
        String id = PermissionService.requireText(projectId, "projectId不能为空");
        Project project = projects.findById(id);
        if (project == null) {
            throw new UseCaseException(UseCaseException.Kind.NOT_FOUND, "项目不存在");
        }
        if (!projects.canView(project, user)) {
            throw new UseCaseException(UseCaseException.Kind.FORBIDDEN, "无权限访问此项目");
        }
        return project;
    }

    @Transactional(readOnly = true)
    public List<Entry> listRootAgents(String projectId, User user) {
        Project project = requireVisibleProject(projectId, user);
        return describe(projects.listRootAgents(project.getProjectId()), user);
    }

    @Transactional(readOnly = true)
    public List<Entry> listUnassignedAgents(User user) {
        return describe(projects.listUnassignedRootAgents(), user);
    }

    @Transactional(readOnly = true)
    public List<Entry> listChildren(String projectId, String parentAgentId, User user) {
        Project project = requireVisibleProject(projectId, user);
        String parentId = PermissionService.requireText(parentAgentId, "parentAgentId不能为空");
        return describe(projects.listAgentsByParent(project.getProjectId(), parentId), user);
    }

    /** 共用数据库快照和 MyBatis 会话，避免重复加载两条路径经过的节点。 */
    @Transactional(readOnly = true)
    public Map<String, List<Project>> listMemberships(List<String> agentIds, User user) {
        for (String agentId : agentIds) {
            permissions.requireAccessibleAgentChain(agentId, user);
        }
        return projects.listVisibleMemberships(agentIds, user);
    }

    private List<Entry> describe(List<Agent> agents, User user) {
        return directory.describe(agents, agent -> permissions.canAccessAgentChain(agent, user));
    }
}
