package org.leo.ai.tools.platform;

import org.leo.core.entity.User;
import org.leo.core.util.PasswordUtil;
import org.leo.service.user.UserService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 平台用户管理 AI 工具。
 *
 * <p>这些工具以平台管理员权限运行，不受前端角色限制。
 * 角色值：admin / leader / normal。
 * 密码以 MD5 形式存储，工具层自动处理哈希。
 */
@Component("platformUserTools")
public class UserTools {

    private static final String USERNAME_ADMIN = "admin";

    private final UserService userService;

    public UserTools(UserService userService) {
        this.userService = userService;
    }

    @Tool("获取当前平台所有用户。返回结果会清空 password 字段，避免泄露敏感信息。")
    public List<User> getAllUser() {
        return sanitize(userService.getAllUser());
    }

    @Tool("获取当前平台所有未加入团队的用户。适用于创建团队前挑选 leader。")
    public List<User> getAllNoTeamUser() {
        List<User> filtered = new ArrayList<>();
        for (User u : userService.getAllUser()) {
            if (u != null && (u.getTeamId() == null || u.getTeamId().isBlank())) {
                filtered.add(u);
            }
        }
        return sanitize(filtered);
    }

    @Tool("根据 userId 获取用户详情。返回结果会清空 password 字段。")
    public User getUserById(String userId) {
        User user = userService.getUserById(requireNonBlank(userId, "userId不能为空"));
        if (user == null) throw new IllegalArgumentException("用户不存在");
        user.setPassword("");
        return user;
    }

    @Tool("根据用户名获取用户详情。返回结果会清空 password 字段。")
    public User getUserByName(String userName) {
        User user = userService.getUserByName(requireNonBlank(userName, "userName不能为空"));
        if (user == null) throw new IllegalArgumentException("用户不存在");
        user.setPassword("");
        return user;
    }

    @Tool("获取当前平台所有用户名。")
    public List<String> getAllUserName() {
        List<String> names = new ArrayList<>();
        for (User u : userService.getAllUser()) {
            if (u != null && u.getUserName() != null) names.add(u.getUserName());
        }
        return names;
    }

    @Tool("创建平台用户。userName 和 password 必填；privilege 可选（admin/leader/normal，默认 normal）；"
            + "未传 userId 会自动生成。密码明文传入，系统自动 MD5 存储。")
    public Map<String, Object> addUser(String userName, String password, String privilege,
                                        String email, String phone, Integer status,
                                        String teamId, String remark, String userId) {
        String name = requireNonBlank(userName, "userName不能为空");
        String pwd  = requireNonBlank(password, "password不能为空");

        if (userService.getUserByName(name) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }

        User user = new User();
        user.setUserId(defaultIfBlank(userId, UUID.randomUUID().toString()));
        user.setUserName(name);
        user.setPassword(PasswordUtil.md5(pwd));
        user.setPrivilege(normalizePrivilege(privilege));
        user.setEmail(trimToNull(email));
        user.setPhone(trimToNull(phone));
        user.setStatus(status == null ? 1 : status);
        user.setLoginCount(0);
        user.setTeamId(trimToNull(teamId));
        user.setRemark(trimToNull(remark));

        boolean created = userService.addUser(user);
        return buildResult("created", created, user.getUserId(), user.getUserName());
    }

    @Tool("更新平台用户。userId 必填，其余字段按需更新；password 不为空时自动 MD5 再存储。")
    public Map<String, Object> updateUser(String userId, String userName, String password,
                                           String privilege, String email, String phone,
                                           Integer status, String teamId, String remark) {
        User existing = userService.getUserById(requireNonBlank(userId, "userId不能为空"));
        if (existing == null) throw new IllegalArgumentException("用户不存在");

        if (!isBlank(userName) && !userName.equals(existing.getUserName())) {
            if (userService.getUserByName(userName) != null) {
                throw new IllegalArgumentException("用户名已存在");
            }
            existing.setUserName(userName.trim());
        }
        if (!isBlank(password)) {
            existing.setPassword(PasswordUtil.md5(password));
        }
        if (!isBlank(privilege)) {
            existing.setPrivilege(normalizePrivilege(privilege));
        }
        if (email != null)    existing.setEmail(trimToNull(email));
        if (phone != null)    existing.setPhone(trimToNull(phone));
        if (status != null)   existing.setStatus(status);
        if (teamId != null)   existing.setTeamId(trimToNull(teamId));
        if (remark != null)   existing.setRemark(trimToNull(remark));

        boolean updated = userService.updateUser(existing);
        return buildResult("updated", updated, existing.getUserId(), existing.getUserName());
    }

    @Tool("删除指定平台用户。禁止删除 admin 用户。")
    public Map<String, Object> deleteUser(String userId) {
        User user = userService.getUserById(requireNonBlank(userId, "userId不能为空"));
        if (user == null) throw new IllegalArgumentException("用户不存在");
        if (USERNAME_ADMIN.equals(user.getUserName())) throw new IllegalArgumentException("不能删除admin用户");
        boolean deleted = userService.delUser(user.getUserId());
        return buildResult("deleted", deleted, user.getUserId(), user.getUserName());
    }

    @Tool("获取平台可用角色列表：admin、leader、normal。")
    public List<String> getPrivileges() {
        return Arrays.asList(
                UserService.PRIVILEGE_ADMIN,
                UserService.PRIVILEGE_LEADER,
                UserService.PRIVILEGE_NORMAL
        );
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

    private String requireNonBlank(String value, String message) {
        String t = trimToNull(value);
        if (t == null) throw new IllegalArgumentException(message);
        return t;
    }

    private String defaultIfBlank(String value, String def) {
        String t = trimToNull(value);
        return t == null ? def : t;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private boolean isBlank(String value) {
        return trimToNull(value) == null;
    }
}
