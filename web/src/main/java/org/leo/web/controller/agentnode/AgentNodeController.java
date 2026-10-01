package org.leo.web.controller.agentnode;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Agent;
import org.leo.core.entity.Project;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.BasicInfoCapable;
import org.leo.core.agent.capability.AgentNodeCapabilityRegistry;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.agentnode.AgentIdRequest;
import org.leo.web.dto.agentnode.SessionIdRequest;
import org.leo.service.AgentConnectionService;
import org.leo.service.project.ProjectService;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.leo.web.service.AgentNodeLifecycleService;
import org.leo.web.service.AgentCacheService;
import org.leo.web.service.AgentHostDiscoveryService;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/agent-node")
public class AgentNodeController {

    private final AgentConnectionService agentConnectionService;
    private final PermissionService permissionService;
    private final AgentNodeLifecycleService agentNodeLifecycleService;
    private final ProjectService projectService;
    private final AgentCacheService cacheService;
    private final AgentHostDiscoveryService hostDiscoveryService;

    public AgentNodeController(AgentConnectionService agentConnectionService,
                                PermissionService permissionService,
                                AgentNodeLifecycleService agentNodeLifecycleService,
                                ProjectService projectService,
                                AgentCacheService cacheService,
                                AgentHostDiscoveryService hostDiscoveryService) {
        this.agentConnectionService = agentConnectionService;
        this.permissionService = permissionService;
        this.agentNodeLifecycleService = agentNodeLifecycleService;
        this.projectService = projectService;
        this.cacheService = cacheService;
        this.hostDiscoveryService = hostDiscoveryService;
    }

    /**
     * 初始化 Agent，建立会话。
     */
    @RequestMapping(value = "/init", method = RequestMethod.GET)
    public HashMap<String, Object> initAgent(HttpServletRequest request,
                                              @RequestParam("agentId") String agentId,
                                              @RequestParam(value = "projectId", required = false) String projectId,
                                              @RequestParam(value = "hostId", required = false) String hostId) throws Exception {
        User user = permissionService.requireLogin(request);
        Agent agent = permissionService.requireAccessibleAgentChain(agentId, user);
        String projectContext = requireProjectContext(projectId, agent.getAgentId(), user);
        return ApiResponse.success(agentNodeLifecycleService.initLiveSession(agent, user, projectContext, hostId));
    }

    /** 探测后端 HostId，不创建 session。 */
    @RequestMapping(value = "/discover-hosts", method = RequestMethod.GET)
    public Map<String, Object> discoverHosts(HttpServletRequest request,
                                             @RequestParam("agentId") String agentId,
                                             @RequestParam(value = "projectId", required = false) String projectId,
                                             @RequestParam(value = "force", defaultValue = "false") boolean force) throws Exception {
        User user = permissionService.requireLogin(request);
        Agent agent = permissionService.requireAccessibleAgentChain(agentId, user);
        requireProjectContext(projectId, agent.getAgentId(), user);
        AgentHostDiscoveryService.DiscoveryResult discovery = hostDiscoveryService.discover(agent, user, force);
        Map<String, Object> result = new HashMap<>();
        result.put("hosts", discovery.hostIds());
        result.put("count", discovery.hostIds().size());
        result.put("discoveredAt", discovery.discoveredAt());
        result.put("cached", discovery.reused());
        return ApiResponse.success(result);
    }

    /**
     * 检查指定 agent 是否存在本地缓存。
     * 返回 hasCache(boolean)、saveTime(String, 可能为 null)。
     */
    @RequestMapping(value = "/check-cache", method = RequestMethod.GET)
    public Map<String, Object> checkCache(HttpServletRequest request,
                                          @RequestParam("agentId") String agentId) {
        User user = permissionService.requireLogin(request);
        Agent agent = permissionService.requireAccessibleAgentChain(agentId, user);

        return ApiResponse.success(cacheService.status(user.getUserId(), agent.getAgentId()));
    }

    /**
     * 以缓存模式进入 agent，不尝试实时连接。
     * 要求 agent 存在且有本地缓存，否则返回错误。
     * 返回 sessionId、cacheMode=true。
     */
    @RequestMapping(value = "/init-cache", method = RequestMethod.GET)
    public Map<String, Object> initCache(HttpServletRequest request,
                                         @RequestParam("agentId") String agentId,
                                         @RequestParam(value = "projectId", required = false) String projectId,
                                         @RequestParam(value = "hostId", required = false) String hostId) {
        User user = permissionService.requireLogin(request);
        Agent agent = permissionService.requireAccessibleAgentChain(agentId, user);
        String projectContext = requireProjectContext(projectId, agent.getAgentId(), user);
        return ApiResponse.success(agentNodeLifecycleService.initCacheSession(agent, user, projectContext, hostId));
    }

    /** 查询本地缓存中已保存的 HostId，不创建 session。 */
    @RequestMapping(value = "/cache-hosts", method = RequestMethod.GET)
    public Map<String, Object> cacheHosts(HttpServletRequest request,
                                          @RequestParam("agentId") String agentId,
                                          @RequestParam(value = "projectId", required = false) String projectId) {
        User user = permissionService.requireLogin(request);
        Agent agent = permissionService.requireAccessibleAgentChain(agentId, user);
        requireProjectContext(projectId, agent.getAgentId(), user);
        List<String> hosts = cacheService.hostIds(user.getUserId(), agent.getAgentId());
        Map<String, Object> result = new HashMap<>();
        result.put("hosts", hosts);
        result.put("count", hosts.size());
        return ApiResponse.success(result);
    }

    /**
     * 测试 Agent 连通性，不创建会话。
     */
    @RequestMapping(value = "/test-conn", method = RequestMethod.POST)
    public Map<String, Object> testConnCheck(HttpServletRequest request, @RequestBody AgentIdRequest body) {
        User user = permissionService.requireLogin(request);
        String agentId = body != null ? body.agentId() : null;
        Agent agent = permissionService.requireAccessibleAgentChain(agentId, user);
        Map<String, Object> result = agentConnectionService.testConnection(agent.getAgentId());
        return ApiResponse.success(result);
    }

    /**
     * 使用新增或编辑表单中的即时配置执行连接预检，不保存配置、不创建会话。
     */
    @RequestMapping(value = "/test-config", method = RequestMethod.POST)
    public Map<String, Object> testConfig(HttpServletRequest request, @RequestBody Agent agent) {
        permissionService.requireLogin(request);
        if (agent == null) throw ApiException.badRequest("主机配置不能为空");
        if (agent.getConnLink() == null || agent.getConnLink().isBlank()) {
            throw ApiException.badRequest("连接地址不能为空");
        }
        return ApiResponse.success(agentConnectionService.testConnection(agent));
    }

    /**
     * 获取基础信息。
     */
    @RequestMapping(value = "/basic-info", method = RequestMethod.POST)
    public Map<String, Object> basicInfo(@RequestBody SessionIdRequest body) throws Exception {
        String sessionId = body != null ? body.sessionId() : null;
        AgentNodeSession session = ControllerUtil.getAgentNodeSession(sessionId);

        // 缓存模式：直接从 agent 级持久化目录读取，不尝试实时连接
        if (session != null && session.isCacheMode()) {
            String pId = session.getAgentId();
            Map<String, Object> cached = cacheService.loadBasicInfo(
                    session.getCreateByUser(), pId, session.getCurrentHostId());
            if (cached != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("BasicInfo", cached);
                return ApiResponse.success(result);
            }
            return ApiResponse.success(new HashMap<>());
        }

        BasicInfoCapable node = ControllerUtil.requireCapability(sessionId, BasicInfoCapable.class);
        Map<String, Object> results = node.getBasicInfo();

        if (session != null && results != null && results.containsKey("BasicInfo")) {
            Object basicInfoObj = results.get("BasicInfo");
            if (basicInfoObj instanceof Map<?, ?> basicInfoMap) {
                Map<String, Object> basicInfo = copyStringKeyMap(basicInfoMap);
                String hostId = session.getCurrentHostId();
                if (hostId != null) {
                    session.setBasicInfo(hostId, basicInfo);
                    cacheService.saveBasicInfo(sessionId, hostId, basicInfo);
                }
            }
        }
        return ApiResponse.success(results != null ? results : new HashMap<>());
    }

    private static Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = new HashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) copy.put(stringKey, value);
        });
        return copy;
    }

    private String requireProjectContext(String projectId, String agentId, User user) {
        if (projectId == null || projectId.isBlank()) return null;
        Project project = projectService.findById(projectId.trim());
        if (project == null) throw ApiException.notFound("项目不存在");
        if (!projectService.canView(project, user)) throw ApiException.forbidden("无权限访问此项目");
        if (Project.STATUS_ARCHIVED.equals(project.getStatus())) {
            throw ApiException.badRequest("已归档项目不能创建新会话");
        }
        if (!projectService.containsAgent(project.getProjectId(), agentId)) {
            throw ApiException.badRequest("主机尚未加入当前项目");
        }
        return project.getProjectId();
    }

    /**
     * 获取当前主机信息。
     */
    @RequestMapping(value = "/current-host", method = RequestMethod.POST)
    public Map<String, Object> getCurrentHost(@RequestBody SessionIdRequest body) {
        String sessionId = body != null ? body.sessionId() : null;
        AbstractAgentNode node = ControllerUtil.getAbstractAgentNode(sessionId);
        Agent currentAgent = node.getAgent();
        return ApiResponse.success(currentAgent);
    }

    /**
     * 获取当前会话支持的能力清单。
     */
    @RequestMapping(value = "/capabilities", method = RequestMethod.POST)
    public Map<String, Object> capabilities(@RequestBody SessionIdRequest body) {
        String sessionId = body != null ? body.sessionId() : null;
        AgentNodeSession session = ControllerUtil.getAgentNodeSession(sessionId);
        AbstractAgentNode node = session.getAgentNode();
        List<String> capabilities = AgentNodeCapabilityRegistry.listSupported(node);

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", session.getSessionId());
        result.put("cacheMode", session.isCacheMode());
        result.put("capabilities", capabilities);
        result.put("capabilityDetails", session.getCapabilityStatuses());
        result.put("capabilityCount", capabilities.size());
        if (session.getRuntimeProfile() != null) {
            result.put("runtimeProfile", session.getRuntimeProfile());
        }
        if (node != null && node.getAgent() != null) {
            result.put("agentId", node.getAgent().getAgentId());
        } else if (session.getAgentId() != null) {
            result.put("agentId", session.getAgentId());
        }
        return ApiResponse.success(result);
    }
}
