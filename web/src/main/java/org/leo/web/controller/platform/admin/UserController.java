package org.leo.web.controller.platform.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.PasswordUtil;
import org.leo.service.PuppetService;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

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
public class UserController {

    private static final String SESSION_USER = "user";
    private static final String USERNAME_ADMIN = "admin";

    private final PuppetService puppetService;
    private final UserService userService;
    private final TeamService teamService;

    public UserController(PuppetService puppetService, UserService userService, TeamService teamService) {
        this.puppetService = puppetService;
        this.userService = userService;
        this.teamService = teamService;
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
     * 获取所有用户名列表（admin only）。
     */
    @RequestMapping(value = "/users/names", method = RequestMethod.GET)
    public HashMap<String, Object> getAllUserName(HttpServletRequest request) {
        User caller = getSessionUser(request);
        if (caller == null) return ApiResponse.unauthorized("未登录");
        if (!UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            return ApiResponse.forbidden("无权访问");
        }
        List<String> names = new ArrayList<>();
        for (User u : userService.getAllUser()) {
            if (u != null && u.getUserName() != null) names.add(u.getUserName());
        }
        return ApiResponse.success(names);
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
        List<User> filtered = new ArrayList<>();
        for (User u : userService.getAllUser()) {
            if (u != null && (u.getTeamId() == null || u.getTeamId().isBlank())) {
                filtered.add(u);
            }
        }
        return ApiResponse.success(sanitize(filtered));
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
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            return ApiResponse.badRequest("密码不能为空");
        }

        // 确定目标角色
        String targetPrivilege = normalizePrivilege(user.getPrivilege());
        String targetTeamId = user.getTeamId();

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
        user.setPassword(PasswordUtil.md5(user.getPassword()));
        user.setStatus(1);
        user.setLoginCount(0);

        userService.addUser(user);
        return ApiResponse.success();
    }

    // ── 用户更新 ─────────────────────────────────────────────────────────────────

    /**
     * 更新用户信息。
     * admin 可更新任意非 admin 用户；leader 只能更新自己团队内的 normal 用户。
     * id 必填；password 不为空时自动 MD5 后存储。
     */
    @RequestMapping(value = "/users/update", method = RequestMethod.POST)
    public HashMap<String, Object> updateUser(HttpServletRequest request,
                                               @RequestBody HashMap<String, Object> params) {
        User caller = getSessionUser(request);
        if (caller == null) return ApiResponse.unauthorized("未登录");
        if (params == null) return ApiResponse.badRequest("params不能为空");

        String userId = getString(params, "id");
        if (userId == null) userId = getString(params, "userId");
        if (userId == null) return ApiResponse.badRequest("id不能为空");

        User target = userService.getUserById(userId);
        if (target == null) return ApiResponse.notFound("用户不存在");
        if (USERNAME_ADMIN.equals(target.getUserName()) &&
                !UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            return ApiResponse.forbidden("无权修改admin用户");
        }

        // 权限检查：leader 只能改自己团队的 normal 用户
        if (UserService.PRIVILEGE_LEADER.equals(caller.getPrivilege())) {
            if (!UserService.PRIVILEGE_NORMAL.equals(target.getPrivilege())) {
                return ApiResponse.forbidden("队长只能修改普通用户");
            }
            if (!caller.getTeamId().equals(target.getTeamId())) {
                return ApiResponse.forbidden("只能修改本团队成员");
            }
        } else if (!UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            return ApiResponse.forbidden("无权修改用户");
        }

        // 字段更新
        String newName = getString(params, "username");
        if (newName == null) newName = getString(params, "userName");
        if (newName != null && !newName.equals(target.getUserName())) {
            if (userService.getUserByName(newName) != null) return ApiResponse.badRequest("用户名已存在");
            target.setUserName(newName);
        }

        String newPwd = getString(params, "password");
        if (newPwd != null && !newPwd.isEmpty()) {
            target.setPassword(PasswordUtil.md5(newPwd));
        }

        String newPrivilege = getString(params, "privilege");
        if (newPrivilege != null && UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            target.setPrivilege(normalizePrivilege(newPrivilege));
        }

        Object statusObj = params.get("status");
        if (statusObj != null) {
            try { target.setStatus(Integer.parseInt(statusObj.toString())); } catch (NumberFormatException ignored) {}
        }

        String newTeamId = (String) params.get("teamname");
        if (newTeamId == null) newTeamId = getString(params, "teamId");
        if (params.containsKey("teamname") || params.containsKey("teamId")) {
            target.setTeamId(newTeamId == null || newTeamId.isEmpty() ? null : newTeamId);
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

        User target = userService.getUserById(userId);
        if (target == null) return ApiResponse.notFound("用户不存在");
        if (USERNAME_ADMIN.equals(target.getUserName()) &&
                !UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            return ApiResponse.forbidden("无权重置admin密码");
        }

        if (UserService.PRIVILEGE_LEADER.equals(caller.getPrivilege())) {
            if (!UserService.PRIVILEGE_NORMAL.equals(target.getPrivilege())) {
                return ApiResponse.forbidden("队长只能重置普通用户密码");
            }
            if (!caller.getTeamId().equals(target.getTeamId())) {
                return ApiResponse.forbidden("只能重置本团队成员密码");
            }
        } else if (!UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            return ApiResponse.forbidden("无权重置密码");
        }

        target.setPassword(PasswordUtil.md5(newPassword));
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
        if (USERNAME_ADMIN.equals(target.getUserName())) return ApiResponse.forbidden("不能删除admin用户");

        try {
            userService.checkDeletePermission(caller, target);
        } catch (SecurityException e) {
            return ApiResponse.forbidden(e.getMessage());
        }

        boolean ok = userService.delUser(target.getUserId());
        return ok ? ApiResponse.success() : ApiResponse.error("删除失败");
    }

    // ── Puppet 查询 ──────────────────────────────────────────────────────────────

    /**
     * 获取当前用户可见的 Puppet 列表（基于角色与 permission 规则）。
     */
    @RequestMapping(value = "/puppets", method = RequestMethod.GET)
    public HashMap<String, Object> getVisiblePuppets(HttpServletRequest request) {
        User caller = getSessionUser(request);
        if (caller == null) return ApiResponse.unauthorized("未登录");

        List<String> teamMemberIds = null;
        if (UserService.PRIVILEGE_LEADER.equals(caller.getPrivilege())
                && caller.getTeamId() != null && !caller.getTeamId().isBlank()) {
            teamMemberIds = new ArrayList<>();
            for (User member : userService.getUserByTeamId(caller.getTeamId())) {
                if (member != null) teamMemberIds.add(member.getUserId());
            }
        }

        List<Puppet> puppets = puppetService.getVisiblePuppets(caller, teamMemberIds);
        return ApiResponse.success(puppets);
    }

    // ── 元数据 ───────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/privileges", method = RequestMethod.GET)
    public HashMap<String, Object> getPrivileges() {
        return ApiResponse.success(Arrays.asList(
                UserService.PRIVILEGE_ADMIN,
                UserService.PRIVILEGE_LEADER,
                UserService.PRIVILEGE_NORMAL
        ));
    }

    // ── 私有工具 ─────────────────────────────────────────────────────────────────

    private User getSessionUser(HttpServletRequest request) {
        return (User) request.getSession().getAttribute(SESSION_USER);
    }

    private List<User> sanitize(List<User> users) {
        if (users == null) return new ArrayList<>();
        for (User u : users) {
            if (u != null) u.setPassword("");
        }
        return users;
    }

    private String getString(HashMap<String, Object> params, String key) {
        Object val = params.get(key);
        return val == null ? null : val.toString().isBlank() ? null : val.toString().trim();
    }

    private String normalizePrivilege(String privilege) {
        if (UserService.PRIVILEGE_ADMIN.equals(privilege)) return UserService.PRIVILEGE_ADMIN;
        if (UserService.PRIVILEGE_LEADER.equals(privilege)) return UserService.PRIVILEGE_LEADER;
        return UserService.PRIVILEGE_NORMAL;
    }
}
