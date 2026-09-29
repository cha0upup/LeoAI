package org.leo.web.controller.platform.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.PasswordUtil;
import org.leo.service.user.PasswordPolicy;
import org.leo.service.user.UserService;
import org.leo.service.user.UserViews;
import org.leo.web.security.RoleAwareAdminEndpoint;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.leo.service.user.UserAccountPolicy.isBuiltInAdmin;
import static org.leo.service.user.UserAccountPolicy.normalizePrivilege;
import static org.leo.service.user.UserAccountPolicy.normalizeStatus;

/**
 * 用户管理控制器。
 *
 * <p>权限规则：
 * <ul>
 *   <li>admin  — 可查询所有用户与 Puppet，可创建任意角色用户，可删除非 admin 用户</li>
 *   <li>leader — 可查询自己团队内的用户；可创建 normal 用户到自己团队；可删除自己团队内的 normal 用户</li>
 *   <li>normal — 无管理权限，只能查询自己的信息</li>
 * </ul>
 */
@RestController
@RequestMapping("/platform/admin")
@RoleAwareAdminEndpoint
public class UserController {

    private static final String SESSION_USER = "user";
    private static final int MAX_USERNAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 100;
    private static final int MAX_PHONE_LENGTH = 20;

    private final UserService userService;
    private final PasswordPolicy passwordPolicy;

    public UserController(UserService userService, PasswordPolicy passwordPolicy) {
        this.userService = userService;
        this.passwordPolicy = passwordPolicy;
    }

    // ── 用户查询 ─────────────────────────────────────────────────────────────────

    /**
     * 获取用户列表。
     * admin 返回所有用户；leader 返回自己团队的用户；normal 返回自己。
     */
    @RequestMapping(value = "/users", method = RequestMethod.GET)
    public HashMap<String, Object> getUsers(HttpServletRequest request) {
        User caller = getSessionUser(request);
        if (caller == null) return ApiResponse.unauthorized("未登录");

        List<User> users;
        if (UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            users = userService.getAllUser();
        } else if (UserService.PRIVILEGE_LEADER.equals(caller.getPrivilege())) {
            users = userService.getUserByTeamId(caller.getTeamId());
        } else {
            users = new ArrayList<>();
            User self = userService.getUserById(caller.getUserId());
            if (self != null) users.add(self);
        }
        return ApiResponse.success(sanitize(users));
    }

    /**
     * 获取未加入任何团队的用户列表（admin only，用于分配 leader）。
     */
    @RequestMapping(value = "/users/no-team", method = RequestMethod.GET)
    public HashMap<String, Object> getNoTeamUsers(HttpServletRequest request) {
        User caller = getSessionUser(request);
        if (caller == null) return ApiResponse.unauthorized("未登录");
        if (!UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            return ApiResponse.forbidden("无权访问");
        }
        return ApiResponse.success(sanitize(userService.getUsersWithoutTeam()));
    }

    // ── 用户创建 ─────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/users", method = RequestMethod.POST)
    public HashMap<String, Object> addUser(HttpServletRequest request, @RequestBody User user) {
        User caller = getSessionUser(request);
        if (caller == null) return ApiResponse.unauthorized("未登录");
        if (user == null) return ApiResponse.badRequest("user参数不能为空");

        String targetName = user.getUserName();
        if (targetName == null || targetName.isBlank()) {
            return ApiResponse.badRequest("用户名不能为空");
        }
        targetName = targetName.trim();
        if (targetName.length() > MAX_USERNAME_LENGTH) {
            return ApiResponse.badRequest("用户名不能超过 " + MAX_USERNAME_LENGTH + " 个字符");
        }
        user.setUserName(targetName);
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            return ApiResponse.badRequest("密码不能为空");
        }
        try {
            passwordPolicy.validate(user.getPassword());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        if (exceeds(user.getEmail(), MAX_EMAIL_LENGTH)) {
            return ApiResponse.badRequest("邮箱不能超过 " + MAX_EMAIL_LENGTH + " 个字符");
        }
        if (exceeds(user.getPhone(), MAX_PHONE_LENGTH)) {
            return ApiResponse.badRequest("手机号不能超过 " + MAX_PHONE_LENGTH + " 个字符");
        }

        // 确定目标角色
        String targetPrivilege = normalizePrivilege(user.getPrivilege());
        String targetTeamId = normalizeNullableId(user.getTeamId());
        user.setTeamId(targetTeamId);

        // 权限校验
        try {
            userService.checkCreatePermission(caller, targetPrivilege, targetTeamId);
        } catch (SecurityException e) {
            return ApiResponse.forbidden(e.getMessage());
        }

        if (userService.getUserByName(targetName) != null) {
            return ApiResponse.badRequest("用户名已存在");
        }

        user.setUserId(UUID.randomUUID().toString());
        user.setPrivilege(targetPrivilege);
        user.setPassword(PasswordUtil.hash(user.getPassword()));
        user.setStatus(normalizeStatus(user.getStatus(), 1));
        user.setLoginCount(0);
        user.setPasswordChangeRequired(1);

        userService.addUser(user);
        return ApiResponse.success();
    }

    // ── 用户更新 ─────────────────────────────────────────────────────────────────

    /**
     * 更新用户信息。
     * admin 可更新任意非 admin 用户；leader 只能更新自己团队内的 normal 用户。
     * userId 必填；password 不为空时使用带盐 PBKDF2 存储。
     */
    @RequestMapping(value = "/users/update", method = RequestMethod.POST)
    public HashMap<String, Object> updateUser(HttpServletRequest request,
                                               @RequestBody HashMap<String, Object> params) {
        User caller = getSessionUser(request);
        if (caller == null) return ApiResponse.unauthorized("未登录");
        if (params == null) return ApiResponse.badRequest("params不能为空");

        String userId = getString(params, "userId");
        if (userId == null) return ApiResponse.badRequest("userId不能为空");

        User target = userService.getUserById(userId);
        if (target == null) return ApiResponse.notFound("用户不存在");
        boolean targetIsBuiltInAdmin = isBuiltInAdmin(target);
        if (targetIsBuiltInAdmin && !UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            return ApiResponse.forbidden("无权修改admin用户");
        }

        // 权限检查：leader 只能改自己团队的 normal 用户
        if (UserService.PRIVILEGE_LEADER.equals(caller.getPrivilege())) {
            if (!UserService.PRIVILEGE_NORMAL.equals(target.getPrivilege())) {
                return ApiResponse.forbidden("队长只能修改普通用户");
            }
            if (!sameTeam(caller, target)) {
                return ApiResponse.forbidden("只能修改本团队成员");
            }
        } else if (!UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            return ApiResponse.forbidden("无权修改用户");
        }

        // 字段更新
        String newName = getString(params, "userName");
        if (newName != null && !newName.equals(target.getUserName())) {
            if (newName.length() > MAX_USERNAME_LENGTH) {
                return ApiResponse.badRequest("用户名不能超过 " + MAX_USERNAME_LENGTH + " 个字符");
            }
            if (userService.getUserByName(newName) != null) return ApiResponse.badRequest("用户名已存在");
            target.setUserName(newName);
        }

        String newPwd = getString(params, "password");
        if (newPwd != null && !newPwd.isEmpty()) {
            try {
                passwordPolicy.validate(newPwd);
            } catch (IllegalArgumentException e) {
                return ApiResponse.badRequest(e.getMessage());
            }
            target.setPassword(PasswordUtil.hash(newPwd));
            target.setPasswordChangeRequired(1);
        }

        String newPrivilege = getString(params, "privilege");
        if (newPrivilege != null && UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            String normalizedPrivilege = normalizePrivilege(newPrivilege);
            if (targetIsBuiltInAdmin && !normalizedPrivilege.equals(target.getPrivilege())) {
                return ApiResponse.forbidden("admin用户为系统内置账户，禁止修改角色");
            }
            target.setPrivilege(normalizedPrivilege);
        }

        Object statusObj = params.get("status");
        if (statusObj != null) {
            Integer nextStatus = normalizeStatus(statusObj, target.getStatus());
            if (targetIsBuiltInAdmin && nextStatus == 0) {
                return ApiResponse.forbidden("admin用户为系统内置账户，禁止禁用");
            }
            target.setStatus(nextStatus);
        } else if (targetIsBuiltInAdmin) {
            target.setStatus(1);
        }

        String newTeamId = getString(params, "teamId");
        if (params.containsKey("teamId")) {
            String normalizedTeamId = normalizeNullableId(newTeamId);
            if (targetIsBuiltInAdmin && !Objects.equals(normalizedTeamId, normalizeNullableId(target.getTeamId()))) {
                return ApiResponse.forbidden("admin用户为系统内置账户，禁止修改所属团队");
            }
            target.setTeamId(normalizedTeamId);
        }

        String remark = getString(params, "remark");
        if (params.containsKey("remark")) target.setRemark(remark);

        boolean ok = userService.updateUser(target);
        return ok ? ApiResponse.success() : ApiResponse.error("更新失败");
    }

    /**
     * 重置用户密码（管理员）。
     * admin 可重置任意非 admin 用户密码；leader 只能重置本团队 normal 用户密码。
     */
    @RequestMapping(value = "/users/reset-password", method = RequestMethod.POST)
    public HashMap<String, Object> resetPassword(HttpServletRequest request,
                                                   @RequestBody HashMap<String, Object> params) {
        User caller = getSessionUser(request);
        if (caller == null) return ApiResponse.unauthorized("未登录");
        if (params == null) return ApiResponse.badRequest("params不能为空");

        String userId = getString(params, "userId");
        if (userId == null) userId = getString(params, "id");
        if (userId == null) return ApiResponse.badRequest("userId不能为空");

        String newPassword = getString(params, "newPassword");
        if (newPassword == null || newPassword.isEmpty()) return ApiResponse.badRequest("新密码不能为空");
        try {
            passwordPolicy.validate(newPassword);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }

        User target = userService.getUserById(userId);
        if (target == null) return ApiResponse.notFound("用户不存在");
        if (isBuiltInAdmin(target) && !UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            return ApiResponse.forbidden("无权重置admin密码");
        }

        if (UserService.PRIVILEGE_LEADER.equals(caller.getPrivilege())) {
            if (!UserService.PRIVILEGE_NORMAL.equals(target.getPrivilege())) {
                return ApiResponse.forbidden("队长只能重置普通用户密码");
            }
            if (!sameTeam(caller, target)) {
                return ApiResponse.forbidden("只能重置本团队成员密码");
            }
        } else if (!UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            return ApiResponse.forbidden("无权重置密码");
        }

        target.setPassword(PasswordUtil.hash(newPassword));
        target.setPasswordChangeRequired(1);
        boolean ok = userService.updateUser(target);
        return ok ? ApiResponse.success() : ApiResponse.error("重置失败");
    }

    // ── 用户删除 ─────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/users/delete", method = RequestMethod.POST)
    public HashMap<String, Object> deleteUser(HttpServletRequest request,
                                               @RequestBody HashMap<String, Object> params) {
        User caller = getSessionUser(request);
        if (caller == null) return ApiResponse.unauthorized("未登录");
        if (params == null) return ApiResponse.badRequest("params不能为空");

        String userId = getString(params, "id");
        if (userId == null) return ApiResponse.badRequest("id不能为空");

        User target = userService.getUserById(userId);
        if (target == null) return ApiResponse.notFound("用户不存在");
        if (isBuiltInAdmin(target)) return ApiResponse.forbidden("admin用户为系统内置账户，禁止删除");

        try {
            userService.checkDeletePermission(caller, target);
        } catch (SecurityException e) {
            return ApiResponse.forbidden(e.getMessage());
        }

        boolean ok = userService.delUser(target.getUserId());
        return ok ? ApiResponse.success() : ApiResponse.error("删除失败");
    }

    // ── 私有工具 ─────────────────────────────────────────────────────────────────

    private User getSessionUser(HttpServletRequest request) {
        return (User) request.getSession().getAttribute(SESSION_USER);
    }

    private List<Map<String, Object>> sanitize(List<User> users) {
        if (users == null) return List.of();
        return users.stream().filter(Objects::nonNull).map(UserViews::profile).toList();
    }

    private String getString(HashMap<String, Object> params, String key) {
        Object val = params.get(key);
        return val == null ? null : val.toString().isBlank() ? null : val.toString().trim();
    }

    private boolean exceeds(String value, int maxLength) {
        return value != null && value.length() > maxLength;
    }

    private String normalizeNullableId(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private boolean sameTeam(User caller, User target) {
        if (caller == null || target == null) return false;
        String callerTeamId = caller.getTeamId();
        return callerTeamId != null && !callerTeamId.isBlank() && callerTeamId.equals(target.getTeamId());
    }
}
