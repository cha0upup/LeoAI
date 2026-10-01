package org.leo.web.controller.platform.agent;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Agent;
import org.leo.core.entity.Project;
import org.leo.core.entity.User;
import org.leo.service.AgentService;
import org.leo.service.UrlProbeService;
import org.leo.service.project.ProjectService;
import org.leo.service.user.UserService;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.json.JsonUtil;
import org.leo.core.util.request.ComponentClassNameStrategy;
import org.leo.web.util.ControllerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agent管理控制器
 */
@RestController
@RequestMapping("/platform/agent-manage")
public class AgentManageController {

    private static final Logger logger = LoggerFactory.getLogger(AgentManageController.class);

    // 参数名常量
    private static final String PARAM_PARENT_AGENT_ID = "parentAgentId";
    private static final String PARAM_AGENT_ID = "agentId";
    
    // 会话属性常量
    private static final String SESSION_ATTR_USER = "user";
    
    // 权限常量
    private static final String PRIVILEGE_ADMIN = "admin";
    private static final String PRIVILEGE_LEADER = "leader";
    private static final String PERMISSION_PRIVATE = "private";
    private static final String PERMISSION_TEAM = "team";
    private static final String PERMISSION_PUBLIC = "public";
    
    // 根节点ID
    private static final String ROOT_PARENT_ID = "root";
    
    private final AgentService agentService;
    private final UserService userService;
    private final UrlProbeService urlProbeService;
    private final ProjectService projectService;

    public AgentManageController(AgentService agentService, UserService userService,
                                  UrlProbeService urlProbeService,
                                  ProjectService projectService){
        this.agentService = agentService;
        this.userService = userService;
        this.urlProbeService = urlProbeService;
        this.projectService = projectService;
    }
    
    @RequestMapping(value = "/children", method = RequestMethod.POST)
    public HashMap<String, Object> getChildrenByParentAgentId(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        try {
            User user = getUserFromSession(request);
            if (user == null || user.getUserId() == null) {
                return ApiResponse.unauthorized("用户未登录");
            }
            String parentAgentId = ControllerUtil.getRequiredStringParam(params, PARAM_PARENT_AGENT_ID);
            List<Agent> childAgents = agentService.findAgentsByParentAgentId(parentAgentId);
            return ApiResponse.success(filterVisibleAgents(childAgents, user));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @RequestMapping(value = "/agents", method = RequestMethod.GET)
    public HashMap<String, Object> getAgent(HttpServletRequest request) {
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        List<Agent> mergedList = filterVisibleAgents(agentService.getAllAgents(), user);
        Iterator<Agent> iterator = mergedList.iterator();
        while (iterator.hasNext()) {
            Agent agent = iterator.next();
            String parentAgentId = agent.getParentAgentId();
            if (parentAgentId == null || !ROOT_PARENT_ID.equals(parentAgentId)) {
                iterator.remove();
            }
        }
        return ApiResponse.success(mergedList);
    }
    
    @RequestMapping(value = "/agents", method = RequestMethod.POST)
    @Transactional
    public HashMap<String, Object> addAgent(
            HttpServletRequest request,
            @RequestBody Agent agent,
            @RequestParam(value = "projectId", required = false) String projectId) {
        if (agent == null) {
            return ApiResponse.badRequest("agent参数不能为空");
        }
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        Project project = null;
        if (projectId != null && !projectId.isBlank()) {
            project = projectService.findById(projectId.trim());
            if (project == null) return ApiResponse.notFound("项目不存在");
            if (!projectService.canView(project, user)) return ApiResponse.forbidden("无权限访问此项目");
            if (!projectService.canEditContent(project, user)) {
                return ApiResponse.forbidden("无权限维护此项目的主机归属");
            }
            if (Project.STATUS_ARCHIVED.equals(project.getStatus())) {
                return ApiResponse.badRequest("已归档项目不能添加主机");
            }
        }
        try {
            validateComponentClassNameStrategy(agent.getComponentClassNameStrategy());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        agent.setCreateByUserId(user.getUserId());
        String permission = normalizeAgentPermission(agent.getPermission());
        if (PERMISSION_PUBLIC.equals(permission) && !isAdmin(user)) {
            return ApiResponse.forbidden("只有管理员可以创建公开Agent");
        }
        agent.setPermission(permission);
        agent.setTeamId(resolveAgentTeamId(agent, user, null));
        String id = UUID.randomUUID().toString();
        agent.setAgentId(id);
        boolean result = agentService.insertAgent(agent);
        if (result) {
            if (project != null) {
                projectService.attachAgents(project.getProjectId(), List.of(id),
                        null, null, null, user.getUserId());
            }
            return ApiResponse.success(Collections.singletonMap("agentId", id));
        } else {
            return ApiResponse.error("添加Agent失败");
        }
    }

    /**
     * 导出指定 Agent 及其完整祖先链。返回顺序始终为祖先在前、子节点在后，
     * 便于客户端在导入时按依赖顺序重建父子关系。
     */
    @RequestMapping(value = "/agents/export", method = RequestMethod.POST)
    public HashMap<String, Object> exportAgents(HttpServletRequest request,
                                                  @RequestBody HashMap<String, Object> params) {
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        Object rawIds = params != null ? params.get("agentIds") : null;
        if (!(rawIds instanceof Collection<?> values) || values.isEmpty()) {
            return ApiResponse.badRequest("agentIds不能为空");
        }
        if (values.size() > 200) {
            return ApiResponse.badRequest("单次最多导出200台主机");
        }

        LinkedHashMap<String, Agent> ordered = new LinkedHashMap<>();
        List<String> requestedIds = new ArrayList<>();
        for (Object value : values) {
            String agentId = value != null ? value.toString().trim() : "";
            if (agentId.isEmpty()) {
                continue;
            }
            requestedIds.add(agentId);
            List<Agent> chain = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            Agent current = agentService.findAgentById(agentId);
            if (current == null) {
                return ApiResponse.notFound("Agent不存在: " + agentId);
            }
            while (current != null) {
                if (!visited.add(current.getAgentId())) {
                    return ApiResponse.conflict("Agent层级存在循环依赖");
                }
                if (!canViewAgent(current, user)) {
                    return ApiResponse.forbidden("无权限导出主机或其依赖");
                }
                chain.add(current);
                String parentId = current.getParentAgentId();
                if (parentId == null || parentId.isBlank() || ROOT_PARENT_ID.equals(parentId)) {
                    break;
                }
                current = agentService.findAgentById(parentId);
                if (current == null) {
                    return ApiResponse.conflict("Agent缺少父主机依赖: " + parentId);
                }
            }
            Collections.reverse(chain);
            for (Agent agent : chain) {
                ordered.putIfAbsent(agent.getAgentId(), agent);
            }
        }

        if (Boolean.TRUE.equals(params.get("includeDescendants"))) {
            Deque<String> queue = new ArrayDeque<>(requestedIds);
            Set<String> expanded = new HashSet<>();
            while (!queue.isEmpty()) {
                String parentId = queue.removeFirst();
                if (!expanded.add(parentId)) {
                    continue;
                }
                for (Agent child : agentService.findAgentsByParentAgentId(parentId)) {
                    if (!canViewAgent(child, user)) {
                        continue;
                    }
                    ordered.putIfAbsent(child.getAgentId(), child);
                    queue.addLast(child.getAgentId());
                    if (ordered.size() > 1000) {
                        return ApiResponse.badRequest("单次分享最多包含1000台主机");
                    }
                }
            }
        }
        return ApiResponse.success(new ArrayList<>(ordered.values()));
    }
    
    @RequestMapping(value = "/agents/update", method = RequestMethod.POST)
    public HashMap<String, Object> updateAgent(HttpServletRequest request, @RequestBody Agent agent) {
        if (agent == null || agent.getAgentId() == null || agent.getAgentId().isBlank()) {
            return ApiResponse.badRequest("agent参数或agentId不能为空");
        }
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        try {
            validateComponentClassNameStrategy(agent.getComponentClassNameStrategy());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        
        Agent existingAgent = agentService.findAgentById(agent.getAgentId());
        if (existingAgent == null) {
            return ApiResponse.notFound("Agent不存在");
        }
        
        // 检查权限：只有创建者或管理员可以更新
        if (!hasPermissionToModify(existingAgent, user)) {
            return ApiResponse.forbidden("无权限修改此Agent");
        }

        String permission = normalizeAgentPermission(agent.getPermission());
        if (PERMISSION_PUBLIC.equals(permission) && !isAdmin(user)) {
            return ApiResponse.forbidden("只有管理员可以设置公开Agent");
        }
        agent.setPermission(permission);
        agent.setCreateByUserId(existingAgent.getCreateByUserId());
        agent.setTeamId(resolveAgentTeamId(agent, user, existingAgent));
        
        boolean result = agentService.updateAgentById(agent);
        if (result) {
            return ApiResponse.success();
        } else {
            return ApiResponse.error("更新Agent失败");
        }
    }

    @RequestMapping(value = "/component-class-name/preview", method = RequestMethod.POST)
    public HashMap<String, Object> previewComponentClassNames(HttpServletRequest request,
                                                               @RequestBody HashMap<String, Object> params) {
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        try {
            Object rawStrategy = params != null ? params.get("strategy") : null;
            String json = rawStrategy instanceof String
                    ? rawStrategy.toString() : JsonUtil.toJsonString(rawStrategy);
            ComponentClassNameStrategy strategy = parseComponentClassNameStrategy(json);
            String sessionKey = params != null && params.get("sessionKey") != null
                    ? params.get("sessionKey").toString() : "configuration-preview";
            Object rawComponents = params != null ? params.get("components") : null;
            Collection<?> components = rawComponents instanceof Collection<?>
                    ? (Collection<?>) rawComponents
                    : List.of("BasicInfoComponent", "ExecCommandComponent", "FileComponent");
            if (components.size() > 100) {
                return ApiResponse.badRequest("单次最多预览100个组件类名");
            }
            Map<String, String> preview = new LinkedHashMap<>();
            for (Object component : components) {
                if (component == null || component.toString().isBlank()) continue;
                String name = component.toString().trim();
                preview.put(name, strategy.resolve(sessionKey, name));
            }
            return ApiResponse.success(preview);
        } catch (Exception e) {
            return ApiResponse.badRequest("类名画像配置错误: " + e.getMessage());
        }
    }
    
    @RequestMapping(value = "/agents/delete", method = RequestMethod.POST)
    public HashMap<String, Object> deleteAgent(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        if (params == null) {
            return ApiResponse.badRequest("params参数不能为空");
        }
        User user = getUserFromSession(request);
        if (user == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        String agentId = (String) params.get(PARAM_AGENT_ID);
        if (agentId == null || agentId.isBlank()) {
            return ApiResponse.badRequest("agentId不能为空");
        }
        Agent agent = agentService.findAgentById(agentId);
        if (agent == null) {
            return ApiResponse.notFound("Agent不存在");
        }
        if (!hasPermissionToModify(agent, user)) {
            return ApiResponse.forbidden("无权限删除此Agent");
        }
        boolean result = agentService.deleteAgentById(agentId);
        if (result) {
            return ApiResponse.success();
        } else {
            return ApiResponse.error("删除Agent失败");
        }
    }

    /**
     * URL 路径池自动探测接口。
     * 从平台侧请求目标站点，发现可用于 URL 随机化的真实路径。
     */
    @RequestMapping(value = "/url-probe", method = RequestMethod.POST)
    public HashMap<String, Object> probeUrlPaths(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        User user = getUserFromSession(request);
        if (user == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        String baseUrl = (String) params.get("baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            return ApiResponse.badRequest("baseUrl 不能为空");
        }
        Number timeout = (Number) params.get("timeout");
        try {
            Map<String, Object> probeResult = urlProbeService.probe(baseUrl.trim(), timeout != null ? timeout.intValue() : 0);
            return ApiResponse.success(probeResult);
        } catch (Exception e) {
            logger.error("URL 探测失败, baseUrl={}: {}", baseUrl, e.getMessage());
            return ApiResponse.error("URL 探测失败: " + e.getMessage());
        }
    }

    /**
     * 从会话中获取用户
     */
    private User getUserFromSession(HttpServletRequest request) {
        return (User) request.getSession().getAttribute(SESSION_ATTR_USER);
    }

    private List<Agent> filterVisibleAgents(List<Agent> agents, User user) {
        if (agents == null || agents.isEmpty()) {
            return new ArrayList<Agent>();
        }
        List<Agent> visible = new ArrayList<Agent>();
        for (Agent agent : agents) {
            if (canViewAgent(agent, user)) {
                visible.add(agent);
            }
        }
        return visible;
    }

    private boolean canViewAgent(Agent agent, User user) {
        if (agent == null || user == null || user.getUserId() == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        if (user.getUserId().equals(agent.getCreateByUserId())) {
            return true;
        }
        if (PERMISSION_PUBLIC.equals(agent.getPermission())) {
            return true;
        }
        return isSameTeamAgent(agent, user) && isTeamVisiblePermission(agent.getPermission());
    }
    
    /**
     * 检查是否有权限修改Agent
     */
    private boolean hasPermissionToModify(Agent agent, User user) {
        if (agent == null || user == null || user.getUserId() == null) {
            return false;
        }
        if (PERMISSION_PUBLIC.equals(agent.getPermission())) {
            return isAdmin(user);
        }
        if (agent.getCreateByUserId() != null &&
            agent.getCreateByUserId().equals(user.getUserId())) {
            return true;
        }
        if (isAdmin(user)) {
            return true;
        }
        return PRIVILEGE_LEADER.equals(user.getPrivilege())
                && isTeamVisiblePermission(agent.getPermission())
                && isSameTeamAgent(agent, user);
    }

    private boolean isAdmin(User user) {
        return user != null && PRIVILEGE_ADMIN.equals(user.getPrivilege());
    }

    private boolean isTeamVisiblePermission(String permission) {
        return PERMISSION_TEAM.equals(permission);
    }

    private boolean isSameTeamAgent(Agent agent, User user) {
        if (agent == null || user == null || user.getTeamId() == null || user.getTeamId().isBlank()) {
            return false;
        }
        if (user.getTeamId().equals(agent.getTeamId())) {
            return true;
        }
        if (agent.getTeamId() != null && !agent.getTeamId().isBlank()) {
            return false;
        }
        User owner = userService.getUserById(agent.getCreateByUserId());
        return owner != null && user.getTeamId().equals(owner.getTeamId());
    }

    private String normalizeAgentPermission(String permission) {
        if (PERMISSION_PUBLIC.equals(permission)) {
            return PERMISSION_PUBLIC;
        }
        if (PERMISSION_TEAM.equals(permission)) {
            return PERMISSION_TEAM;
        }
        return PERMISSION_PRIVATE;
    }

    private String resolveAgentTeamId(Agent agent, User user, Agent existingAgent) {
        if (isAdmin(user)) {
            String requestedTeamId = agent.getTeamId();
            if (requestedTeamId != null && !requestedTeamId.isBlank()) {
                return requestedTeamId.trim();
            }
            return existingAgent != null ? existingAgent.getTeamId() : user.getTeamId();
        }
        if (user.getTeamId() != null && !user.getTeamId().isBlank()) {
            return user.getTeamId();
        }
        return existingAgent != null ? existingAgent.getTeamId() : null;
    }

    private void validateComponentClassNameStrategy(String json) {
        if (json == null || json.isBlank()) return;
        ComponentClassNameStrategy strategy = parseComponentClassNameStrategy(json);
        strategy.validateConfiguration();
    }

    private ComponentClassNameStrategy parseComponentClassNameStrategy(String json) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) {
            return new ComponentClassNameStrategy();
        }
        try {
            ComponentClassNameStrategy strategy = (ComponentClassNameStrategy) JsonUtil.fromJsonString(
                    json, ComponentClassNameStrategy.class);
            if (strategy == null) {
                throw new IllegalArgumentException("类名画像配置为空");
            }
            return strategy;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("类名画像 JSON 格式错误: " + e.getMessage(), e);
        }
    }
}
