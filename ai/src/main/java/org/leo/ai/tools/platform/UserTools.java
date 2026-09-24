package org.leo.ai.tools.platform;

import org.leo.core.entity.User;
import org.leo.core.util.PasswordUtil;
import org.leo.service.user.UserService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolAccess;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.leo.ai.tools.platform.PlatformToolArguments.*;

/**
 * 平台用户管理 AI 工具。
 *
 * <p>这些工具只对平台管理员开放，权限由 Agent 工具授权层强制校验。
 * 角色值：admin / leader / normal。
 * 密码以 PBKDF2-SHA256 形式存储，工具层自动处理哈希。
 */
@Component("platformUserTools")
@AiToolAccess(AiToolAccess.Level.ADMIN)
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class UserTools {

    private static final String USERNAME_ADMIN = "admin";

    private final UserService userService;

    public UserTools(UserService userService) {
        this.userService = userService;
    }

    @Tool("列出平台用户。withoutTeam=true 时只返回尚未加入团队的用户。结果不会返回密码。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public List<User> listUsers(
            @P(value = "是否只返回未加入团队的用户", required = false)
            Boolean withoutTeam) {
        if (!Boolean.TRUE.equals(withoutTeam)) {
            return sanitize(userService.getAllUser());
        }
        List<User> filtered = new ArrayList<>();
        for (User u : userService.getAllUser()) {
            if (u != null && (u.getTeamId() == null || u.getTeamId().isBlank())) {
                filtered.add(u);
            }
        }
        return sanitize(filtered);
    }

    @Tool("按 userId 或 userName 获取用户详情；两者必须且只能提供一个。结果不会返回密码。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public User getUser(
            @P(value = "用户 ID，与 userName 二选一", required = false) String userId,
            @P(value = "用户名，与 userId 二选一", required = false) String userName) {
        String id = trimToNull(userId);
        String name = trimToNull(userName);
        if ((id == null) == (name == null)) {
            throw new IllegalArgumentException("userId 与 userName 必须且只能提供一个");
        }
        User user = id != null ? userService.getUserById(id) : userService.getUserByName(name);
        if (user == null) throw new IllegalArgumentException("用户不存在");
        user.setPassword("");
        return user;
    }

    @Tool("创建平台用户。userName 和 password 必填；privilege 可选（admin/leader/normal，默认 normal）；"
            + "未传 userId 会自动生成。密码明文传入，系统自动 PBKDF2-SHA256 存储。")
    public Map<String, Object> addUser(
            @P("唯一用户名") String userName,
            @P("初始密码；服务端仅保存安全哈希") String password,
            @P(value = "角色：admin/leader/normal；默认 normal", required = false) String privilege,
            @P(value = "邮箱", required = false) String email,
            @P(value = "手机号", required = false) String phone,
            @P(value = "状态：1启用、0停用；默认1", required = false, defaultValue = "1") Integer status,
            @P(value = "所属团队 ID", required = false) String teamId,
            @P(value = "备注", required = false) String remark,
            @P(value = "用户 ID；省略时自动生成", required = false) String userId) {
        String name = requireNonBlank(userName, "userName不能为空");
        String pwd  = requireNonBlank(password, "password不能为空");

        if (userService.getUserByName(name) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }

        User user = new User();
        user.setUserId(defaultIfBlank(userId, UUID.randomUUID().toString()));
        user.setUserName(name);
        user.setPassword(PasswordUtil.hash(pwd));
        user.setPrivilege(normalizePrivilege(privilege));
        user.setEmail(trimToNull(email));
        user.setPhone(trimToNull(phone));
        user.setStatus(normalizeStatus(status, 1));
        if (isBuiltInAdmin(user) && user.getStatus() == 0) {
            throw new IllegalArgumentException("admin用户为系统内置账户，禁止禁用");
        }
        user.setLoginCount(0);
        user.setTeamId(trimToNull(teamId));
        user.setRemark(trimToNull(remark));

        boolean created = userService.addUser(user);
        return buildResult("created", created, user.getUserId(), user.getUserName());
    }

    @Tool("更新平台用户。userId 必填，其余字段按需更新；password 不为空时自动安全哈希后存储。")
    public Map<String, Object> updateUser(
            @P("待更新用户 ID") String userId,
            @P(value = "新用户名", required = false) String userName,
            @P(value = "新密码；服务端仅保存安全哈希", required = false) String password,
            @P(value = "新角色：admin/leader/normal", required = false) String privilege,
            @P(value = "新邮箱；空字符串表示清空", required = false) String email,
            @P(value = "新手机号；空字符串表示清空", required = false) String phone,
            @P(value = "新状态：1启用、0停用", required = false) Integer status,
            @P(value = "新团队 ID；空字符串表示清空", required = false) String teamId,
            @P(value = "新备注；空字符串表示清空", required = false) String remark) {
        User existing = userService.getUserById(requireNonBlank(userId, "userId不能为空"));
        if (existing == null) throw new IllegalArgumentException("用户不存在");
        boolean builtInAdmin = isBuiltInAdmin(existing);

        if (!isBlank(userName) && !userName.equals(existing.getUserName())) {
            if (userService.getUserByName(userName) != null) {
                throw new IllegalArgumentException("用户名已存在");
            }
            existing.setUserName(userName.trim());
        }
        if (!isBlank(password)) {
            existing.setPassword(PasswordUtil.hash(password));
        }
        if (!isBlank(privilege)) {
            String normalizedPrivilege = normalizePrivilege(privilege);
            if (builtInAdmin && !normalizedPrivilege.equals(existing.getPrivilege())) {
                throw new IllegalArgumentException("admin用户为系统内置账户，禁止修改角色");
            }
            existing.setPrivilege(normalizedPrivilege);
        }
        if (email != null)    existing.setEmail(trimToNull(email));
        if (phone != null)    existing.setPhone(trimToNull(phone));
        if (status != null) {
            Integer nextStatus = normalizeStatus(status, existing.getStatus());
            if (builtInAdmin && nextStatus == 0) {
                throw new IllegalArgumentException("admin用户为系统内置账户，禁止禁用");
            }
            existing.setStatus(nextStatus);
        } else if (builtInAdmin) {
            existing.setStatus(1);
        }
        if (teamId != null) {
            String normalizedTeamId = trimToNull(teamId);
            if (builtInAdmin && !sameNullable(normalizedTeamId, trimToNull(existing.getTeamId()))) {
                throw new IllegalArgumentException("admin用户为系统内置账户，禁止修改所属团队");
            }
            existing.setTeamId(normalizedTeamId);
        }
        if (remark != null)   existing.setRemark(trimToNull(remark));

        boolean updated = userService.updateUser(existing);
        return buildResult("updated", updated, existing.getUserId(), existing.getUserName());
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.DESTRUCTIVE, exclusive = true)
    @Tool("删除指定平台用户。禁止删除 admin 用户。")
    public Map<String, Object> deleteUser(@P("待删除用户 ID") String userId) {
        User user = userService.getUserById(requireNonBlank(userId, "userId不能为空"));
        if (user == null) throw new IllegalArgumentException("用户不存在");
        if (isBuiltInAdmin(user)) throw new IllegalArgumentException("admin用户为系统内置账户，禁止删除");
        boolean deleted = userService.delUser(user.getUserId());
        return buildResult("deleted", deleted, user.getUserId(), user.getUserName());
    }

    // ── 私有工具 ─────────────────────────────────────────────────────────────────

    private List<User> sanitize(List<User> users) {
        if (users == null) return new ArrayList<>();
        for (User u : users) {
            if (u != null) u.setPassword("");
        }
        return users;
    }

    private String normalizePrivilege(String privilege) {
        if (UserService.PRIVILEGE_ADMIN.equals(privilege))  return UserService.PRIVILEGE_ADMIN;
        if (UserService.PRIVILEGE_LEADER.equals(privilege)) return UserService.PRIVILEGE_LEADER;
        return UserService.PRIVILEGE_NORMAL;
    }

    private Map<String, Object> buildResult(String status, boolean success, String userId, String userName) {
        HashMap<String, Object> result = new HashMap<>();
        result.put("status",   status);
        result.put("success",  success);
        result.put("userId",   userId);
        result.put("userName", userName);
        return result;
    }

    private Integer normalizeStatus(Integer status, Integer fallback) {
        if (status == null) return fallback != null ? fallback : 1;
        return status == 0 ? 0 : 1;
    }

    private boolean isBuiltInAdmin(User user) {
        if (user == null) return false;
        return USERNAME_ADMIN.equals(user.getUserId()) || USERNAME_ADMIN.equals(user.getUserName());
    }

    private boolean sameNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
