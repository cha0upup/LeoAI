package org.leo.service.user;

import org.leo.core.entity.User;
import org.leo.dao.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 用户管理服务。
 *
 * <p>角色体系：admin（管理员）、leader（团队队长）、normal（普通用户）。
 * <ul>
 *   <li>admin  — 可管理所有用户与团队</li>
 *   <li>leader — 只能在自己的团队内创建普通用户</li>
 *   <li>normal — 无用户管理权限</li>
 * </ul>
 */
@Service
public class UserService {

    public static final String PRIVILEGE_ADMIN  = "admin";
    public static final String PRIVILEGE_LEADER = "leader";
    public static final String PRIVILEGE_NORMAL = "normal";

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────────

    public User getUserById(String userId) {
        if (userId == null || userId.isBlank()) return null;
        return normalize(userMapper.findUserById(userId.trim()));
    }

    public User getUserByName(String userName) {
        if (userName == null || userName.isBlank()) return null;
        return normalize(userMapper.findUserByUsername(userName.trim()));
    }

    public List<User> getAllUser() {
        List<User> list = userMapper.getAllUser();
        if (list == null) return new ArrayList<>();
        for (User u : list) normalize(u);
        return list;
    }

    public List<User> getUserByTeamId(String teamId) {
        if (teamId == null || teamId.isBlank()) return new ArrayList<>();
        List<User> list = userMapper.findUserByTeamId(teamId.trim());
        if (list == null) return new ArrayList<>();
        for (User u : list) normalize(u);
        return list;
    }

    // ── 写操作（不含权限校验，由调用方负责） ────────────────────────────────────

    public boolean addUser(User user) {
        if (user == null) throw new IllegalArgumentException("user不能为空");
        String now = DATE_FORMAT.format(new Date());
        return userMapper.insertUser(
                user.getUserId(),
                user.getUserName(),
                user.getPassword(),
                user.getPrivilege() != null ? user.getPrivilege() : PRIVILEGE_NORMAL,
                user.getEmail(),
                user.getPhone(),
                user.getStatus() != null ? user.getStatus() : 1,
                user.getLastLoginTime(),
                user.getLoginCount() != null ? user.getLoginCount() : 0,
                now, now,
                user.getTeamId(),
                user.getRemark()
        );
    }

    public boolean updateUser(User user) {
        if (user == null || user.getUserId() == null || user.getUserId().isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        String now = DATE_FORMAT.format(new Date());
        return userMapper.updateUserById(
                user.getUserName(),
                user.getPassword(),
                user.getPrivilege(),
                user.getEmail(),
                user.getPhone(),
                user.getStatus(),
                user.getLastLoginTime(),
                user.getLoginCount(),
                now,
                user.getTeamId(),
                user.getRemark(),
                user.getUserId()
        );
    }

    public boolean delUser(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId不能为空");
        return userMapper.delUser(userId.trim());
    }

    // ── 权限校验 ─────────────────────────────────────────────────────────────────

    /**
     * 校验调用方是否有权限创建指定角色、指定团队的用户。
     *
     * @throws SecurityException 权限不足时抛出
     */
    public void checkCreatePermission(User caller, String targetPrivilege, String targetTeamId) {
        if (caller == null) throw new SecurityException("未登录");
        if (PRIVILEGE_ADMIN.equals(caller.getPrivilege())) return;
        if (PRIVILEGE_LEADER.equals(caller.getPrivilege())) {
            if (!PRIVILEGE_NORMAL.equals(targetPrivilege)) {
                throw new SecurityException("队长只能创建普通用户");
            }
            String leaderTeam = caller.getTeamId();
            if (leaderTeam == null || leaderTeam.isBlank()
                    || !leaderTeam.equals(targetTeamId)) {
                throw new SecurityException("队长只能在自己的团队内创建用户");
            }
            return;
        }
        throw new SecurityException("无权创建用户");
    }

    /**
     * 校验调用方是否有权限删除目标用户。
     *
     * @throws SecurityException 权限不足时抛出
     */
    public void checkDeletePermission(User caller, User target) {
        if (caller == null) throw new SecurityException("未登录");
        if (PRIVILEGE_ADMIN.equals(caller.getPrivilege())) return;
        if (PRIVILEGE_LEADER.equals(caller.getPrivilege())) {
            if (!PRIVILEGE_NORMAL.equals(target.getPrivilege())) {
                throw new SecurityException("队长只能删除普通用户");
            }
            String leaderTeam = caller.getTeamId();
            if (leaderTeam == null || !leaderTeam.equals(target.getTeamId())) {
                throw new SecurityException("队长只能删除自己团队内的用户");
            }
            return;
        }
        throw new SecurityException("无权删除用户");
    }

    // ── 私有工具 ─────────────────────────────────────────────────────────────────

    private User normalize(User user) {
        if (user == null) return null;
        if (!isKnownPrivilege(user.getPrivilege())) {
            user.setPrivilege(PRIVILEGE_NORMAL);
        }
        return user;
    }

    private boolean isKnownPrivilege(String privilege) {
        return PRIVILEGE_ADMIN.equals(privilege)
                || PRIVILEGE_LEADER.equals(privilege)
                || PRIVILEGE_NORMAL.equals(privilege);
    }
}
