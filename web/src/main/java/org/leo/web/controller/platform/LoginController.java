package org.leo.web.controller.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.PasswordUtil;
import org.leo.service.user.UserService;
import org.leo.web.dto.platform.user.ChangePasswordRequest;
import org.leo.web.dto.platform.user.LoginRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户登录控制器。
 * 密码以 MD5 形式存储与比对。
 */
@RestController
@RequestMapping("/platform/user")
public class LoginController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

    private static final String SESSION_ATTR_USER  = "user";

    private final UserService userService;
    private final PermissionService permissionService;

    public LoginController(UserService userService, PermissionService permissionService) {
        this.userService = userService;
        this.permissionService = permissionService;
    }

    /**
     * 用户登录。提交的明文密码会转为 MD5 后与数据库比对。
     */
    @PostMapping("/login")
    public Map<String, Object> login(HttpServletRequest request,
                                     @RequestBody LoginRequest body) {
        String username = requireText(body != null ? body.username() : null, "username不能为空");
        String password = requireText(body != null ? body.password() : null, "password不能为空");

        User user = userService.getUserByName(username);
        if (user == null || !PasswordUtil.verify(password, user.getPassword())) {
            logger.warn("登录失败，用户名或密码错误: {}", username);
            throw ApiException.badRequest("用户名或密码错误");
        }

        request.getSession().setAttribute(SESSION_ATTR_USER, user);
        logger.info("用户登录成功: {} ({})", username, user.getPrivilege());
        return ApiResponse.success();
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
        request.getSession().removeAttribute(SESSION_ATTR_USER);
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
        if (user != null) {
            data.put("userId",    user.getUserId());
            data.put("userName",  user.getUserName());
            data.put("privilege", user.getPrivilege());
            data.put("teamId",    user.getTeamId());
        }
        return ApiResponse.success(data);
    }

    /**
     * 修改密码。oldPassword 为明文，系统自动 MD5 比对；newPassword 为明文，保存时自动 MD5。
     */
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(HttpServletRequest request,
                                              @RequestBody ChangePasswordRequest body) {
        User sessionUser = permissionService.requireLogin(request);
        String oldPassword = requireText(body != null ? body.oldPassword() : null, "oldPassword不能为空");
        String newPassword = requireText(body != null ? body.newPassword() : null, "newPassword不能为空");

        User user = userService.getUserById(sessionUser.getUserId());
        if (user == null || !PasswordUtil.verify(oldPassword, user.getPassword())) {
            logger.warn("修改密码失败，旧密码不正确，userId: {}", sessionUser.getUserId());
            throw ApiException.badRequest("旧密码不正确");
        }

        user.setPassword(PasswordUtil.md5(newPassword));
        userService.updateUser(user);

        // 更新 Session 中的用户信息
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
}
