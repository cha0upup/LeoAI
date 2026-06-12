package org.leo.web.controller.platform.fingerprint;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.service.fingerprint.FingerprintManageService;
import org.leo.web.util.ControllerUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

@RestController
@RequestMapping("/platform/fingerprint-manage")
public class FingerprintManageController {

    private static final String SESSION_ATTR_USER = "user";

    private final FingerprintManageService fingerprintManageService;

    @Autowired
    public FingerprintManageController(FingerprintManageService fingerprintManageService) {
        this.fingerprintManageService = fingerprintManageService;
    }

    @RequestMapping(value = "/fingerprints", method = RequestMethod.GET)
    public HashMap<String, Object> listFingerprints() {
        try {
            return ApiResponse.success(fingerprintManageService.listFingerprints());
        } catch (Exception e) {
            return ApiResponse.error("获取指纹列表失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/fingerprints/by-protocol", method = RequestMethod.POST)
    public HashMap<String, Object> getFingerprintsByProtocol(@RequestBody HashMap<String, Object> params) {
        try {
            String protocol = ControllerUtil.getRequiredStringParam(params, "protocol");
            return ApiResponse.success(fingerprintManageService.getFingerprintsByProtocol(protocol));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("按协议获取指纹失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/fingerprints/get", method = RequestMethod.POST)
    public HashMap<String, Object> getFingerprint(@RequestBody HashMap<String, Object> params) {
        try {
            String fingerprintId = ControllerUtil.getRequiredStringParam(params, "fingerprintId");
            return ApiResponse.success(fingerprintManageService.getFingerprintById(fingerprintId));
        } catch (FingerprintManageService.FingerprintNotFoundException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("获取指纹失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/fingerprints/save", method = RequestMethod.POST)
    public HashMap<String, Object> saveFingerprint(@RequestBody HashMap<String, Object> params, HttpServletRequest request) {
        try {
            HashMap<String, Object> data = fingerprintManageService.saveFingerprint(params, getCurrentUser(request));
            return ApiResponse.success("保存成功", data);
        } catch (IllegalArgumentException e) {
            return toValidationResponse(e);
        } catch (Exception e) {
            return ApiResponse.error("保存指纹失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/fingerprints/delete", method = RequestMethod.POST)
    public HashMap<String, Object> deleteFingerprint(@RequestBody HashMap<String, Object> params, HttpServletRequest request) {
        try {
            String fingerprintId = ControllerUtil.getRequiredStringParam(params, "fingerprintId");
            fingerprintManageService.deleteFingerprint(getCurrentUser(request), fingerprintId);
            return ApiResponse.success("删除成功");
        } catch (IllegalArgumentException e) {
            return toValidationResponse(e);
        } catch (Exception e) {
            return ApiResponse.error("删除指纹失败: " + e.getMessage());
        }
    }

    private User getCurrentUser(HttpServletRequest request) {
        return (User) request.getSession().getAttribute(SESSION_ATTR_USER);
    }

    private HashMap<String, Object> toValidationResponse(IllegalArgumentException e) {
        String message = e.getMessage();
        if ("用户未登录".equals(message)) {
            return ApiResponse.unauthorized(message);
        }
        if (e instanceof FingerprintManageService.FingerprintNotFoundException) {
            return ApiResponse.notFound(message);
        }
        return ApiResponse.badRequest(message);
    }
}
