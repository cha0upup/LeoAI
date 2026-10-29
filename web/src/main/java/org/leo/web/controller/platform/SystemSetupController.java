package org.leo.web.controller.platform;

import org.leo.core.util.ApiResponse;
import org.leo.service.user.SystemInitializationService;
import org.leo.web.exception.ApiException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class SystemSetupController {
    private final SystemInitializationService initialization;

    public SystemSetupController(SystemInitializationService initialization) {
        this.initialization = initialization;
    }

    public record SetupRequest(String password, String confirmPassword) {}

    @PostMapping(value = "/platform/setup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> initialize(@RequestBody(required = false) SetupRequest body) {
        if (body == null) throw ApiException.badRequest("请填写管理员密码");
        try {
            initialization.initializeAdmin(body.password(), body.confirmPassword());
        } catch (SystemInitializationService.AlreadyInitializedException completed) {
            throw ApiException.conflict(completed.getMessage());
        }
        return ApiResponse.success("初始化完成，请使用 admin 登录");
    }
}
