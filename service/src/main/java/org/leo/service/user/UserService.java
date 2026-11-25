package org.leo.service.user;

import org.leo.core.entity.User;
import org.leo.core.entity.UserOption;
import org.leo.dao.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

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

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Match String.isBlank() rather than SQLite's default, space-only trim.
    private static final String TEAM_ID_WHITESPACE = IntStream.rangeClosed(Character.MIN_CODE_POINT, Character.MAX_CODE_POINT)
            .filter(Character::isWhitespace)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();

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

    public boolean isUserNameTaken(String userName) {
        return userName != null && !userName.isBlank() && userMapper.existsUserByUsername(userName.trim());
    }

    public List<User> getAllUsers() {
        return normalizeUserList(userMapper.getAllUsers());
    }

    public List<User> getUsersByTeamId(String teamId) {
        if (teamId == null || teamId.isBlank()) return new ArrayList<>();
        return normalizeUserList(userMapper.findUsersByTeamId(teamId.trim()));
    }

    public List<User> getUsersWithoutTeam() {
        List<User> users = userMapper.findUsersWithoutTeam(TEAM_ID_WHITESPACE);
        return users == null ? List.of() : users.stream().filter(Objects::nonNull).map(this::normalize).toList();
    }

    public List<UserOption> getUserOptionsWithoutTeam() {
        return userMapper.findUserOptionsWithoutTeam(TEAM_ID_WHITESPACE);
    }

    public List<UserOption> getEnabledUserOptions() {
        return userMapper.findEnabledUserOptions();
    }

    // ── 写操作（不含权限校验，由调用方负责） ────────────────────────────────────

    public boolean addUser(User user) {
        if (user == null) throw new IllegalArgumentException("user不能为空");
        String now = now();
        return userMapper.insertUser(user, now);
    }

    /** Updates a previously read account snapshot; false means it changed or disappeared. */
    public boolean updateUser(User user) {
        if (user == null || user.getUserId() == null || user.getUserId().isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        String now = now();
        if (!userMapper.updateUserById(user, now)) return false;
        user.setAccountRevision(user.getAccountRevision() + 1);
        user.setUpdateTime(now);
        return true;
    }

    /** Writes only editable profile fields and leaves the verified account snapshot untouched. */
    public User updateProfile(User verifiedUser, String email, String phone, String remark) {
        return userMapper.updateProfile(verifiedUser.getUserId(), verifiedUser.getAccountRevision(),
                email, phone, remark, now());
    }

    public boolean delUser(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId不能为空");
        return userMapper.delUser(userId.trim());
    }

    /** Updates membership without rewriting unrelated account fields. */
    public void detachTeamMembers(String teamId) {
        if (teamId == null || teamId.isBlank()) throw new IllegalArgumentException("teamId不能为空");
        userMapper.detachTeamMembers(teamId.trim(), now());
    }

    /** Records the login only if the account still accepts the verified credential. */
    public User recordSuccessfulLogin(String userId, String verifiedPasswordHash) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (!userMapper.recordSuccessfulLogin(userId.trim(), verifiedPasswordHash, now())) return null;
        return getUserById(userId);
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

    private List<User> normalizeUserList(List<User> users) {
        if (users == null) return new ArrayList<>();
        for (User user : users) normalize(user);
        return users;
    }

    private User normalize(User user) {
        if (user == null) return null;
        user.setPrivilege(UserAccountPolicy.normalizePrivilege(user.getPrivilege()));
        if (user.getPasswordChangeRequired() == null) {
            user.setPasswordChangeRequired(0);
        }
        return user;
    }

    private static String now() {
        return LocalDateTime.now().format(DATE_FORMAT);
    }
}
