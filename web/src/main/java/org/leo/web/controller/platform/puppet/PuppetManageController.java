package org.leo.web.controller.platform.puppet;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.PuppetJdbc;
import org.leo.core.entity.User;
import org.leo.service.PuppetJdbcService;
import org.leo.service.PuppetService;
import org.leo.service.UrlProbeService;
import org.leo.service.user.UserService;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.web.util.ControllerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;


import java.util.*;

/**
 * Puppet管理控制器
 */
@RestController
@RequestMapping("/platform/puppet-manage")
public class PuppetManageController {

    private static final Logger logger = LoggerFactory.getLogger(PuppetManageController.class);

    // 参数名常量
    private static final String PARAM_PARENT_PUPPET_ID = "parentPuppetId";
    private static final String PARAM_PUPPET_ID = "PuppetId";
    private static final String PARAM_PUPPET_ID_LOWER = "puppetId";
    private static final String PARAM_SESSION_ID = "sessionId";
    private static final String PARAM_CONN_ID = "connId";
    
    // 会话属性常量
    private static final String SESSION_ATTR_USER = "user";
    
    // 权限常量
    private static final String PRIVILEGE_ADMIN = "admin";
    private static final String PERMISSION_PROTECTED = "protected";
    private static final String PERMISSION_PUBLIC = "public";
    
    // 根节点ID
    private static final String ROOT_PARENT_ID = "root";
    
    private PuppetService puppetService;
    private UserService userService;
    private PuppetJdbcService puppetJdbcService;
    private UrlProbeService urlProbeService;

    @Autowired
    public PuppetManageController(PuppetService puppetService, UserService userService,
                                  PuppetJdbcService puppetJdbcService, UrlProbeService urlProbeService){
        this.puppetService = puppetService;
        this.userService = userService;
        this.puppetJdbcService = puppetJdbcService;
        this.urlProbeService = urlProbeService;
    }
    
    @RequestMapping(value = "/children", method = RequestMethod.POST)
    public HashMap<String, Object> getChildrenByParentPuppetId(@RequestBody HashMap<String, Object> params) {
        try {
            
            String puppetId = ControllerUtil.getRequiredStringParam(params, PARAM_PARENT_PUPPET_ID);
            List<Puppet> puppetList = puppetService.findPuppetByParentPuppetId(puppetId);
            return ApiResponse.success(puppetList != null ? puppetList : new ArrayList<Puppet>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @RequestMapping(value = "/puppet", method = RequestMethod.POST)
    public HashMap<String, Object> getPuppetById(@RequestBody HashMap<String, Object> params) {
        try {
            
            String puppetId = ControllerUtil.getRequiredStringParam(params, PARAM_PUPPET_ID);
            Puppet puppet = puppetService.findPuppetById(puppetId);
            if (puppet == null) {
                return ApiResponse.notFound("Puppet不存在");
            }
            return ApiResponse.success(puppet);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @RequestMapping(value = "/puppets", method = RequestMethod.GET)
    public HashMap<String, Object> getPuppet(HttpServletRequest request) {
        // 获取用户、团队和公开的Puppet列表
        List<Puppet> puppetList0 = getPuppetListByUser(request);
        List<Puppet> puppetList1 = getPuppetListByTeam(request);
        List<Puppet> puppetList2 = getPuppetListByPublic();
        Set<Puppet> uniquePuppets = new HashSet<Puppet>();
        if (puppetList0 != null) {
            uniquePuppets.addAll(puppetList0);
        }
        if (puppetList1 != null) {
            uniquePuppets.addAll(puppetList1);
        }
        if (puppetList2 != null) {
            uniquePuppets.addAll(puppetList2);
        }
        // 将结果转换回 List，只保留根节点
        List<Puppet> mergedList = new ArrayList<Puppet>(uniquePuppets);
        Iterator<Puppet> iterator = mergedList.iterator();
        while (iterator.hasNext()) {
            Puppet puppet = iterator.next();
            String parentPuppetId = puppet.getParentPuppetId();
            if (parentPuppetId == null || !ROOT_PARENT_ID.equals(parentPuppetId)) {
                iterator.remove();
            }
        }
        return ApiResponse.success(mergedList);
    }
    
    /**
     * 获取用户Puppet列表（内部方法，返回List）
     */
    private List<Puppet> getPuppetListByUser(HttpServletRequest request) {
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
        return new ArrayList<Puppet>();
        }
        List<Puppet> puppetList = puppetService.findPuppetByCreateUserId(user.getUserId());
        return puppetList != null ? puppetList : new ArrayList<Puppet>();
    }
    
    /**
     * 获取团队Puppet列表（内部方法，返回List）
     */
    private List<Puppet> getPuppetListByTeam(HttpServletRequest request) {
        User user = getUserFromSession(request);
        if (user == null || user.getTeamId() == null || user.getTeamId().isBlank()) {
        return new ArrayList<Puppet>();
        }
        String teamId = user.getTeamId();
        List<User> userList = userService.getUserByTeamId(teamId);
        if (userList == null || userList.isEmpty()) {
        return new ArrayList<Puppet>();
        }
        List<Puppet> allPuppetList = new ArrayList<Puppet>();
        for (User teamUser : userList) {
            if (teamUser == null || teamUser.getUserId() == null) {
                continue;
            }
            List<Puppet> puppetList = puppetService.findPuppetByCreateUserId(teamUser.getUserId());
            if (puppetList != null) {
                for (Puppet puppet : puppetList) {
                    if (puppet != null && PERMISSION_PROTECTED.equals(puppet.getPermission())) {
                        allPuppetList.add(puppet);
                    }
                }
            }
        }
        return allPuppetList;
    }

    /**
     * 获取公开Puppet列表（内部方法，返回List）
     */
    private List<Puppet> getPuppetListByPublic() {
        List<Puppet> puppets = puppetService.findPuppetByPermission(PERMISSION_PUBLIC);
        if (puppets == null) {
        return new ArrayList<Puppet>();
        }
        for (Puppet puppet : puppets) {
            if (puppet != null) {
                puppet.setHeaders("");
            }
        }
        return puppets;
    }

    @RequestMapping(value = "/puppets/user", method = RequestMethod.GET)
    public HashMap<String, Object> getPuppetByUser(HttpServletRequest request) {
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.success(new ArrayList<Puppet>());
        }
        List<Puppet> puppetList = puppetService.findPuppetByCreateUserId(user.getUserId());
        return ApiResponse.success(puppetList != null ? puppetList : new ArrayList<Puppet>());
    }

    @RequestMapping(value = "/puppets/team", method = RequestMethod.GET)
    public HashMap<String, Object> getPuppetByTeam(HttpServletRequest request) {
        User user = getUserFromSession(request);
        if (user == null || user.getTeamId() == null || user.getTeamId().isBlank()) {
            return ApiResponse.success(new ArrayList<Puppet>());
        }
        String teamId = user.getTeamId();
        List<User> userList = userService.getUserByTeamId(teamId);
        if (userList == null || userList.isEmpty()) {
            return ApiResponse.success(new ArrayList<Puppet>());
        }
        List<Puppet> allPuppetList = new ArrayList<Puppet>();
        for (User teamUser : userList) {
            if (teamUser == null || teamUser.getUserId() == null) {
                continue;
            }
            List<Puppet> puppetList = puppetService.findPuppetByCreateUserId(teamUser.getUserId());
            if (puppetList != null) {
                for (Puppet puppet : puppetList) {
                    if (puppet != null && PERMISSION_PROTECTED.equals(puppet.getPermission())) {
                        allPuppetList.add(puppet);
                    }
                }
            }
        }
        return ApiResponse.success(allPuppetList);
    }
    
    @RequestMapping(value = "/puppets/public", method = RequestMethod.GET)
    public HashMap<String, Object> getPuppetByPublic() {
        List<Puppet> puppets = puppetService.findPuppetByPermission(PERMISSION_PUBLIC);
        if (puppets == null) {
            return ApiResponse.success(new ArrayList<Puppet>());
        }
        for (Puppet puppet : puppets) {
            if (puppet != null) {
                puppet.setHeaders("");
            }
        }
        return ApiResponse.success(puppets);
    }
    
    @RequestMapping(value = "/puppets", method = RequestMethod.POST)
    public HashMap<String, Object> addPuppet(HttpServletRequest request, @RequestBody Puppet puppet) {
        if (puppet == null) {
            return ApiResponse.badRequest("puppet参数不能为空");
        }
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        puppet.setCreateByUserId(user.getUserId());
        String id = UUID.randomUUID().toString();
        puppet.setPuppetId(id);
        boolean result = puppetService.insertPuppet(puppet);
        if (result) {
            return ApiResponse.success();
        } else {
            return ApiResponse.error("添加Puppet失败");
        }
    }
    
    @RequestMapping(value = "/puppets/update", method = RequestMethod.POST)
    public HashMap<String, Object> updatePuppet(HttpServletRequest request, @RequestBody Puppet puppet) {
        if (puppet == null || puppet.getPuppetId() == null || puppet.getPuppetId().isBlank()) {
            return ApiResponse.badRequest("puppet参数或puppetId不能为空");
        }
        User user = getUserFromSession(request);
        if (user == null || user.getUserId() == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        
        Puppet existingPuppet = puppetService.findPuppetById(puppet.getPuppetId());
        if (existingPuppet == null) {
            return ApiResponse.notFound("Puppet不存在");
        }
        
        // 检查权限：只有创建者或管理员可以更新
        if (!hasPermissionToModify(existingPuppet, user)) {
            return ApiResponse.forbidden("无权限修改此Puppet");
        }
        
        boolean result = puppetService.updatePuppetById(puppet);
        if (result) {
            return ApiResponse.success();
        } else {
            return ApiResponse.error("更新Puppet失败");
        }
    }
    
    @RequestMapping(value = "/puppets/delete", method = RequestMethod.POST)
    public HashMap<String, Object> deletePuppet(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        if (params == null) {
            return ApiResponse.badRequest("params参数不能为空");
        }
        User user = getUserFromSession(request);
        if (user == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
        String puppetId = (String) params.get(PARAM_PUPPET_ID_LOWER);
        if (puppetId == null || puppetId.isBlank()) {
            return ApiResponse.badRequest("puppetId不能为空");
        }
        // 删除前先查出 puppet，用于获取 createByUserId 清理工作目录
        Puppet puppet = puppetService.findPuppetById(puppetId);
        boolean result = puppetService.deletePuppetById(puppetId);
        if (result) {
            // 联动清理 puppet 级工作目录（basic-info、catalina-info 等）
            if (puppet != null) {
                try {
                    PuppetNodeSessionWorkDirUtil.deletePuppetWorkDir(puppet.getCreateByUserId(), puppetId);
                } catch (Exception ex) {
                    logger.warn("清理 puppet 工作目录失败, puppetId={}: {}", puppetId, ex.getMessage());
                }
            }
            return ApiResponse.success();
        } else {
            return ApiResponse.error("删除Puppet失败");
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
    
    /**
     * 检查是否有权限修改Puppet
     */
    private boolean hasPermissionToModify(Puppet puppet, User user) {
        if (puppet.getCreateByUserId() != null && 
            puppet.getCreateByUserId().equals(user.getUserId())) {
            return true;
        }
        return PRIVILEGE_ADMIN.equals(user.getPrivilege());
    }

    @RequestMapping(value = "/puppet-jdbc", method = RequestMethod.POST)
    public HashMap<String, Object> SavePuppetJdbc(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }

            // 创建或获取PuppetJdbc对象
            PuppetJdbc connection = new PuppetJdbc();
            
            // 获取连接ID，如果存在则是更新，否则是新增
            String connId = (String) params.get(PARAM_CONN_ID);
            if (connId != null && !connId.isBlank()) {
                // 更新操作：先查询现有连接
                PuppetJdbc existing = puppetJdbcService.findById(connId);
                if (existing == null) {
                    return ApiResponse.notFound("数据库连接不存在");
                }
                // 检查权限：只有创建者或管理员可以更新
                if (!existing.getCreateUserId().equals(user.getUserId()) && !PRIVILEGE_ADMIN.equals(user.getPrivilege())) {
                    return ApiResponse.forbidden("无权限修改此数据库连接");
                }
                connection = existing;
            } else {
                // 新增操作：设置创建用户
                connection.setCreateUserId(user.getUserId());
                // 设置团队ID（如果用户有团队）
                if (user.getTeamId() != null) {
                    connection.setTeamId(user.getTeamId());
                }
            }

            // 设置puppet ID（必填）- 只能通过sessionId获取
            String sessionId = (String) params.get(PARAM_SESSION_ID);
            if (sessionId == null) {
                return ApiResponse.badRequest("sessionId不能为空");
            }
            
            // 通过sessionId获取puppetId
            PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
            if (session.getJavaPuppetNode() == null) {
                return ApiResponse.badRequest("会话中不存在Puppet实体，sessionId: " + sessionId);
            }
            if (session.getJavaPuppetNode().getPuppet() == null) {
                return ApiResponse.badRequest("Puppet实体中不存在Puppet信息，sessionId: " + sessionId);
            }
            String puppetId = session.getJavaPuppetNode().getPuppet().getPuppetId();
            if (puppetId == null) {
                return ApiResponse.badRequest("无法从会话中获取Puppet ID，sessionId: " + sessionId);
            }
            connection.setPuppetId(puppetId);

            // 设置jdbcUrl（必填）
            String jdbcUrl = (String) params.get("jdbcUrl");
            if (jdbcUrl == null) {
                return ApiResponse.badRequest("jdbcUrl不能为空");
            }
            connection.setJdbcUrl(jdbcUrl);
            
            // 设置driverClass（必填）
            String driverClass = (String) params.get("driverClass");
            if (driverClass == null) {
                return ApiResponse.badRequest("driverClass不能为空");
            }
            connection.setDriverClass(driverClass);

            // 设置用户名和密码
            String username = (String) params.get("username");
            connection.setUsername(username);

            String password = (String) params.get("password");
            // 如果是更新操作且密码为空，保持原密码不变
            if (password != null && !password.isBlank()) {
                connection.setPassword(password);
            } else if (connId == null || connId.isBlank()) {
                // 新增操作时密码可以为空
                connection.setPassword("");
            }

            // 从jdbcUrl解析数据库类型等信息
            puppetJdbcService.parseJdbcUrlInfo(connection);

            // 设置连接名称（如果未提供，则自动生成）
            String connName = (String) params.get("connName");
            if (connName == null || connName.isBlank()) {
                // 自动生成连接名称：从jdbcUrl中提取数据库信息
                connName = generateConnNameFromJdbcUrl(jdbcUrl);
            }
            
            // 检查连接名称是否已存在（排除当前连接）
            if (puppetJdbcService.existsByName(connName, connId)) {
                // 如果名称已存在，添加时间戳
                connName = connName + "_" + System.currentTimeMillis();
            }
            connection.setConnName(connName);

            // 如果提供了其他可选参数，则设置
            String dbType = (String) params.get("dbType");
            if (dbType != null && !dbType.isBlank()) {
                connection.setDbType(dbType);
            }

            // 处理其他可选参数（如果提供了host、port等，则设置）
            String host = (String) params.get("host");
            if (host != null && !host.isBlank()) {
                connection.setHost(host);
            }

            Object portObj = params.get("port");
            if (portObj != null) {
                Integer port = null;
                if (portObj instanceof Integer) {
                    port = (Integer) portObj;
                } else if (portObj instanceof String) {
                    try {
                        port = Integer.parseInt((String) portObj);
                    } catch (NumberFormatException e) {
                        // 忽略
                    }
                }
                if (port != null) {
                    connection.setPort(port);
                }
            }

            String databaseName = (String) params.get("databaseName");
            if (databaseName != null && !databaseName.isBlank()) {
                connection.setDatabaseName(databaseName);
            }

            // 设置额外连接参数（可选）
            String connectionParams = (String) params.get("connectionParams");
            connection.setConnectionParams(connectionParams);

            // 设置状态
            Object statusObj = params.get("status");
            if (statusObj != null) {
                if (statusObj instanceof Integer) {
                    connection.setStatus((Integer) statusObj);
                } else if (statusObj instanceof String) {
                    connection.setStatus(Integer.parseInt((String) statusObj));
                }
            }

            // 设置最大连接数和超时时间
            Object maxConnectionsObj = params.get("maxConnections");
            if (maxConnectionsObj != null) {
                if (maxConnectionsObj instanceof Integer) {
                    connection.setMaxConnections((Integer) maxConnectionsObj);
                } else if (maxConnectionsObj instanceof String) {
                    connection.setMaxConnections(Integer.parseInt((String) maxConnectionsObj));
                }
            }

            Object timeoutSecondsObj = params.get("timeoutSeconds");
            if (timeoutSecondsObj != null) {
                if (timeoutSecondsObj instanceof Integer) {
                    connection.setTimeoutSeconds((Integer) timeoutSecondsObj);
                } else if (timeoutSecondsObj instanceof String) {
                    connection.setTimeoutSeconds(Integer.parseInt((String) timeoutSecondsObj));
                }
            }

            // 设置是否公开
            Object isPublicObj = params.get("isPublic");
            if (isPublicObj != null) {
                if (isPublicObj instanceof Integer) {
                    connection.setIsPublic((Integer) isPublicObj);
                } else if (isPublicObj instanceof String) {
                    connection.setIsPublic(Integer.parseInt((String) isPublicObj));
                } else if (isPublicObj instanceof Boolean) {
                    connection.setIsPublic(((Boolean) isPublicObj) ? 1 : 0);
                }
            }

            // 设置描述和备注
            String description = (String) params.get("description");
            connection.setDescription(description);

            String remark = (String) params.get("remark");
            connection.setRemark(remark);

            // 保存或更新
            boolean result = puppetJdbcService.saveOrUpdate(connection);
            if (result) {
                return ApiResponse.success();
            } else {
                return ApiResponse.error("保存数据库连接失败");
            }
        } catch (Exception e) {
            return ApiResponse.error("保存数据库连接失败: " + e.getMessage());
        }
    }

    /**
     * 从jdbcUrl自动生成连接名称
     */
    private String generateConnNameFromJdbcUrl(String jdbcUrl) {
        try {
            String urlLower = jdbcUrl.toLowerCase();
            String dbType = "";
            String host = "";
            String database = "";

            // 解析数据库类型
            if (urlLower.startsWith("jdbc:mysql:")) {
                dbType = "MySQL";
            } else if (urlLower.startsWith("jdbc:postgresql:")) {
                dbType = "PostgreSQL";
            } else if (urlLower.startsWith("jdbc:sqlserver:") || urlLower.startsWith("jdbc:microsoft:sqlserver:")) {
                dbType = "SQLServer";
            } else if (urlLower.startsWith("jdbc:oracle:")) {
                dbType = "Oracle";
            } else if (urlLower.startsWith("jdbc:sqlite:")) {
                dbType = "SQLite";
                String path = jdbcUrl.substring("jdbc:sqlite:".length());
                int lastSlash = path.lastIndexOf("/");
                int lastBackslash = path.lastIndexOf("\\");
                int lastSeparator = Math.max(lastSlash, lastBackslash);
                if (lastSeparator >= 0) {
                    database = path.substring(lastSeparator + 1);
                } else {
                    database = path;
                }
                // 移除文件扩展名
                int dotIndex = database.lastIndexOf(".");
                if (dotIndex > 0) {
                    database = database.substring(0, dotIndex);
                }
                return dbType + "_" + database;
            }

            // 解析host和database
            if (!urlLower.startsWith("jdbc:sqlite:")) {
                String urlPart = jdbcUrl.substring(jdbcUrl.indexOf("://") + 3);
                int portIndex = urlPart.indexOf(":");
                int dbIndex = urlPart.indexOf("/");
                int paramIndex = urlPart.indexOf("?");
                int semicolonIndex = urlPart.indexOf(";");

                if (urlLower.startsWith("jdbc:sqlserver:")) {
                    // SQL Server: jdbc:sqlserver://host:port;databaseName=database
                    if (portIndex > 0) {
                        host = urlPart.substring(0, portIndex);
                    }
                    int dbNameIndex = urlPart.indexOf("databaseName=");
                    if (dbNameIndex > 0) {
                        String dbNamePart = urlPart.substring(dbNameIndex + "databaseName=".length());
                        int dbNameEnd = dbNamePart.indexOf(";");
                        if (dbNameEnd < 0) dbNameEnd = dbNamePart.length();
                        database = dbNamePart.substring(0, dbNameEnd);
                    }
                } else if (urlLower.startsWith("jdbc:oracle:")) {
                    // Oracle: jdbc:oracle:thin:@//host:port/database
                    String urlPart2 = jdbcUrl.substring(jdbcUrl.indexOf("@//") + 3);
                    int portIndex2 = urlPart2.indexOf(":");
                    int dbIndex2 = urlPart2.indexOf("/");
                    if (portIndex2 > 0) {
                        host = urlPart2.substring(0, portIndex2);
                    }
                    if (dbIndex2 > portIndex2) {
                        database = urlPart2.substring(dbIndex2 + 1);
                        int paramIndex2 = database.indexOf("?");
                        if (paramIndex2 > 0) {
                            database = database.substring(0, paramIndex2);
                        }
                    }
                } else {
                    // MySQL/PostgreSQL: jdbc:mysql://host:port/database
                    if (portIndex > 0) {
                        host = urlPart.substring(0, portIndex);
                    }
                    if (dbIndex > portIndex) {
                        database = urlPart.substring(dbIndex + 1);
                        int endIndex = Math.min(
                            paramIndex > 0 ? paramIndex : database.length(),
                            semicolonIndex > 0 ? semicolonIndex : database.length()
                        );
                        if (endIndex < database.length()) {
                            database = database.substring(0, endIndex);
                        }
                    }
                }
            }

            // 生成连接名称
            StringBuilder name = new StringBuilder();
            if (!dbType.equals("")) {
                name.append(dbType);
            }
            if (!host.equals("")) {
                if (name.length() > 0) name.append("_");
                name.append(host);
            }
            if (!database.equals("")) {
                if (name.length() > 0) name.append("_");
                name.append(database);
            }
            if (name.length() == 0) {
                name.append("数据库连接");
            }
            return name.toString();
        } catch (Exception e) {
            // 解析失败，返回默认名称
            return "数据库连接_" + System.currentTimeMillis();
        }
    }

    /**
     * 通过sessionId获取PuppetJdbc列表
     * 
     * @param request HTTP请求
     * @param params 请求参数，包含sessionId
     * @return 数据库连接列表
     */
    @RequestMapping(value = "/puppet-jdbc/list", method = RequestMethod.POST)
    public HashMap<String, Object> getPuppetJdbcBySessionId(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }

            // 通过sessionId获取PuppetEntity
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            
            // 获取puppetId
            if (javaPuppetNode.getPuppet() == null) {
                return ApiResponse.badRequest("Puppet实体中不存在Puppet信息");
            }
            String puppetId = javaPuppetNode.getPuppet().getPuppetId();
            if (puppetId == null || puppetId.isBlank()) {
                return ApiResponse.badRequest("无法从会话中获取Puppet ID");
            }

            // 根据puppetId查询数据库连接列表
            List<PuppetJdbc> connections = puppetJdbcService.findByPuppetId(puppetId);
            return ApiResponse.success(connections);
        } catch (Exception e) {
            return ApiResponse.error("获取数据库连接列表失败: " + e.getMessage());
        }
    }

    /**
     * 通过connId删除PuppetJdbc
     * 
     * @param request HTTP请求
     * @param params 请求参数，包含connId
     * @return 删除结果
     */
    @RequestMapping(value = "/puppet-jdbc/delete", method = RequestMethod.POST)
    public HashMap<String, Object> deletePuppetJdbc(HttpServletRequest request, @RequestBody HashMap<String, Object> params) {
        try {
            User user = getUserFromSession(request);
            if (user == null) {
                return ApiResponse.unauthorized("用户未登录");
            }

            // 获取connId
            String connId = (String) params.get(PARAM_CONN_ID);
            if (connId == null || connId.isBlank()) {
                return ApiResponse.badRequest("connId不能为空");
            }

            // 查询数据库连接
            PuppetJdbc connection = puppetJdbcService.findById(connId);
            if (connection == null) {
                return ApiResponse.notFound("数据库连接不存在，connId: " + connId);
            }

            // 检查权限：只有创建者或管理员可以删除
            if (!connection.getCreateUserId().equals(user.getUserId()) && !PRIVILEGE_ADMIN.equals(user.getPrivilege())) {
                return ApiResponse.forbidden("无权限删除此数据库连接");
            }

            // 删除数据库连接
            boolean result = puppetJdbcService.deleteById(connId);
            if (result) {
                return ApiResponse.success();
            } else {
                return ApiResponse.error("删除数据库连接失败");
            }
        } catch (Exception e) {
            return ApiResponse.error("删除数据库连接失败: " + e.getMessage());
        }
    }

}
