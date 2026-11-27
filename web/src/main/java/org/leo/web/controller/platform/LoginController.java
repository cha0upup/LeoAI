package org.leo.web.controller.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.leo.core.entity.Team;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.PasswordUtil;
import org.leo.service.team.TeamService;
import org.leo.service.user.PasswordPolicy;
import org.leo.service.user.SystemInitializationService;
import org.leo.service.user.UserService;
import org.leo.service.user.UserViews;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.leo.web.security.LoginAttemptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 用户登录控制器。
 * Passwords use salted PBKDF2 hashes.
 */
@RestController
@RequestMapping("/platform/user")
public class LoginController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

    private static final String SESSION_ATTR_USER  = "user";
    private static final int MAX_USERNAME_LENGTH = 100;
    private static final int MAX_PASSWORD_LENGTH = 256;
    private static final int MAX_EMAIL_LENGTH = 100;
    private static final int MAX_PHONE_LENGTH = 20;
    private static final int MAX_REMARK_LENGTH = 500;
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserService userService;
    private final TeamService teamService;
    private final PermissionService permissionService;
    private final LoginAttemptService loginAttemptService;
    private final PasswordPolicy passwordPolicy;
    private final SystemInitializationService initialization;

    public LoginController(UserService userService, TeamService teamService, PermissionService permissionService,
                           LoginAttemptService loginAttemptService, PasswordPolicy passwordPolicy,
                           SystemInitializationService initialization) {
        this.userService = userService;
        this.teamService = teamService;
        this.permissionService = permissionService;
        this.loginAttemptService = loginAttemptService;
        this.passwordPolicy = passwordPolicy;
        this.initialization = initialization;
    }

    /** 用户登录。 */
    @PostMapping("/login")
    public Map<String, Object> login(HttpServletRequest request,
                                     @RequestBody LoginRequest body) {
        String username = requireText(body != null ? body.username() : null, "username不能为空");
        String password = requirePassword(body != null ? body.password() : null, "password不能为空");
        if (username.length() > MAX_USERNAME_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw ApiException.badRequest("用户名或密码长度超出限制");
        }
        String remoteAddress = request.getRemoteAddr();
        long retryAfter = loginAttemptService.retryAfterSeconds(username, remoteAddress);
        if (retryAfter > 0L) {
            throw ApiException.tooManyRequests("登录失败次数过多，请在 " + retryAfter + " 秒后重试");
        }

        User user = userService.getUserByName(username);
        if (user == null || !PasswordUtil.verify(password, user.getPassword())) {
            loginAttemptService.recordFailure(username, remoteAddress);
            logger.warn("登录失败，用户名或密码错误: {}", username);
            throw ApiException.unauthorized("用户名或密码错误");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            loginAttemptService.recordFailure(username, remoteAddress);
            throw ApiException.forbidden("账号已禁用，请联系管理员");
        }

        User refreshed = userService.recordSuccessfulLogin(user.getUserId(), user.getPassword());
        if (refreshed == null || !Integer.valueOf(1).equals(refreshed.getStatus())
                || !Objects.equals(user.getPassword(), refreshed.getPassword())) {
            throw ApiException.unauthorized("账号凭据已更新，请重新登录");
        }
        user = refreshed;
        loginAttemptService.recordSuccess(username, remoteAddress);

        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(SESSION_ATTR_USER, user);
        logger.info("用户登录成功: {} ({})", username, user.getPrivilege());
        return ApiResponse.success(UserViews.authentication(user));
    }

    /**
     * 用户登出。
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        User user = permissionService.getCurrentUser(request);
        if (user != null) {
            logger.info("用户登出: {}", user.getUserName());
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ApiResponse.success();
    }

    /**
     * 获取当前登录状态及用户信息（不含密码）。
     */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        User user = permissionService.getCurrentUser(request);
        HashMap<String, Object> data = new HashMap<>();
        data.put("isLoggedIn", user != null);
        // A verified session already confirms that this installation contains an active account.
        data.put("initializationRequired", user == null && initialization.isInitializationRequired());
        data.put("passwordMinLength", passwordPolicy.getMinimumLength());
        data.put("passwordMaxLength", PasswordPolicy.MAX_LENGTH);
        if (user != null) {
            data.putAll(UserViews.authentication(user));
        }
        return ApiResponse.success(data);
    }

    /** 获取当前用户的完整个人资料（不含密码）。 */
    @GetMapping("/profile")
    public Map<String, Object> profile(HttpServletRequest request) {
        User user = permissionService.requireLogin(request);
        return ApiResponse.success(profileView(user));
    }

    /**
     * 更新当前用户可自行维护的资料。
     * 用户名、角色、团队和账号状态只能通过管理后台变更。
     */
    @PostMapping("/profile")
    public Map<String, Object> updateProfile(HttpServletRequest request,
                                             @RequestBody UpdateProfileRequest body) {
        User verifiedUser = permissionService.requireLogin(request);
        if (body == null) {
            throw ApiException.badRequest("个人资料不能为空");
        }
        Long revision = body.accountRevision();
        if (revision == null || revision < 0) {
            throw ApiException.badRequest("请先读取个人资料，并在保存时提交有效的 accountRevision");
        }
        if (revision != verifiedUser.getAccountRevision()) {
            throw ApiException.conflict("账号信息已变化，请刷新后重试");
        }

        String email = normalizeOptional(body.email());
        String phone = normalizeOptional(body.phone());
        String remark = normalizeOptional(body.remark());
        validateLength(email, MAX_EMAIL_LENGTH, "邮箱");
        validateLength(phone, MAX_PHONE_LENGTH, "手机号");
        validateLength(remark, MAX_REMARK_LENGTH, "备注");
        if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
            throw ApiException.badRequest("邮箱格式不正确");
        }

        User user = userService.updateProfile(verifiedUser, email, phone, remark);
        if (user == null) {
            throw ApiException.conflict("账号信息已变化，请刷新后重试");
        }

        // Profile changes do not replace the authenticated credential in the session.
        logger.info("个人资料更新成功，userId: {}", user.getUserId());
        return ApiResponse.success(profileView(user));
    }

    /**
     * 修改密码。oldPassword 为明文，newPassword 使用带盐 PBKDF2 保存。
     */
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(HttpServletRequest request,
                                              @RequestBody ChangePasswordRequest body) {
        User sessionUser = permissionService.requireLogin(request);
        String oldPassword = requirePassword(body != null ? body.oldPassword() : null, "oldPassword不能为空");
        String newPassword = requirePassword(body != null ? body.newPassword() : null, "newPassword不能为空");
        passwordPolicy.validate(newPassword);

        // 密码校验和修改使用独立对象，成功后才替换 Session 用户。
        User user = userService.getUserById(sessionUser.getUserId());
        if (user == null || !PasswordUtil.verify(oldPassword, user.getPassword())) {
            logger.warn("修改密码失败，旧密码不正确，userId: {}", sessionUser.getUserId());
            throw ApiException.badRequest("旧密码不正确");
        }

        user.setPassword(PasswordUtil.hash(newPassword));
        user.setPasswordChangeRequired(0);
        if (!userService.updateUser(user)) {
            throw ApiException.conflict("账号信息已变化，请刷新后重试");
        }

        // 更新 Session 中的用户信息
        request.changeSessionId();
        request.getSession().setAttribute(SESSION_ATTR_USER, user);
        logger.info("密码修改成功，userId: {}", user.getUserId());
        return ApiResponse.success("密码修改成功");
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(message);
        }
        return value.trim();
    }

    /** Password characters are significant; normalization is reserved for identifiers. */
    private String requirePassword(String value, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(message);
        }
        return value;
    }

    private Map<String, Object> profileView(User user) {
        Map<String, Object> data = UserViews.profile(user);
        data.put("accountRevision", user.getAccountRevision());
        Team team = teamService.getTeamById(user.getTeamId());
        data.put("teamName", team != null ? team.getTeamName() : null);
        return data;
    }

    private String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void validateLength(String value, int maxLength, String label) {
        if (value != null && value.length() > maxLength) {
            throw ApiException.badRequest(label + "不能超过 " + maxLength + " 个字符");
        }
    }

    record LoginRequest(String username, String password) {}

    record ChangePasswordRequest(String oldPassword, String newPassword) {}

    /** 可编辑字段及客户端读取资料时的账号版本。 */
    record UpdateProfileRequest(String email, String phone, String remark, Long accountRevision) {}
}
