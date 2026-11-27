package org.leo.web.application.project;

import org.leo.core.entity.Project;
import org.leo.core.entity.User;
import org.leo.service.project.ProjectService;
import org.leo.web.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 在一次读取中完成祖先链校验与项目归属查询。 */
@Service
public class ProjectMembershipQuery {
    private final ProjectService projects;
    private final PermissionService permissions;

    public ProjectMembershipQuery(ProjectService projects, PermissionService permissions) {
        this.projects = projects;
        this.permissions = permissions;
    }

    /** 共用数据库快照和 MyBatis 会话，避免重复加载两条路径经过的节点。 */
    @Transactional(readOnly = true)
    public Map<String, List<Project>> list(List<String> agentIds, User user) {
        for (String agentId : agentIds) {
            permissions.requireAccessibleAgentChain(agentId, user);
        }
        return projects.listVisibleMemberships(agentIds, user);
    }
}
