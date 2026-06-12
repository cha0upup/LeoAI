package org.leo.web.controller.platform.disguise;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Disguise;
import org.leo.core.entity.User;
import org.leo.service.disguise.DisguiseService;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.javassist.JavassistDisguiseFactory;
import org.leo.web.util.ControllerUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;

@RestController
@RequestMapping("/platform/disguise-manager")
public class DisguiseManagerController {

    private static final String SESSION_ATTR_USER = "user";

    private final DisguiseService disguiseService;

    @Autowired
    public DisguiseManagerController(DisguiseService disguiseService) {
        this.disguiseService = disguiseService;
    }

    @RequestMapping(value = "/add-disguise", method = RequestMethod.POST)
    public HashMap<String, Object> addDisguise(@RequestBody HashMap<String, Object> params, HttpServletRequest request) {
        try {
            disguiseService.addDisguise(params, getCurrentUser(request));
            return ApiResponse.success();
        } catch (IllegalArgumentException e) {
            return toValidationResponse(e);
        } catch (Exception e) {
            return ApiResponse.error("保存disguise失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/del-disguise", method = RequestMethod.POST)
    public HashMap<String, Object> delDisguise(@RequestBody HashMap<String, Object> params) {
        try {
            disguiseService.deleteDisguise(ControllerUtil.getRequiredStringParam(params, "disguiseId"));
            return ApiResponse.success();
        } catch (IllegalArgumentException e) {
            return toValidationResponse(e);
        } catch (IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @RequestMapping(value = "/disguises", method = RequestMethod.GET)
    public HashMap<String, Object> getDisguises() {
        ArrayList<Disguise> disguises = disguiseService.getDisguises();
        return ApiResponse.success(disguises);
    }

    @RequestMapping(value = "/update-disguises", method = RequestMethod.POST)
    public HashMap<String, Object> updateDisguise(@RequestBody HashMap<String, Object> params) {
        try {
            disguiseService.updateDisguise(params);
            return ApiResponse.success("disguise更新成功");
        } catch (IllegalArgumentException e) {
            return toValidationResponse(e);
        } catch (Exception e) {
            return ApiResponse.error("disguise保存失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/upload-disguises", method = RequestMethod.POST)
    public HashMap<String, Object> uploadDisguise(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        try {
            HashMap<String, Object> data = disguiseService.uploadDisguise(file, getCurrentUser(request));
            return ApiResponse.success("Disguise 上传成功", data);
        } catch (IllegalArgumentException e) {
            return toValidationResponse(e);
        } catch (Exception e) {
            return ApiResponse.error("Disguise 保存失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/disguises/get", method = RequestMethod.POST)
    public HashMap<String, Object> getDisguiseById(@RequestBody HashMap<String, Object> params) {
        try {
            Disguise disguise = disguiseService.getDisguiseById(ControllerUtil.getRequiredStringParam(params, "disguiseId"));
            return ApiResponse.success(disguise);
        } catch (IllegalArgumentException e) {
            return toValidationResponse(e);
        }
    }

    @RequestMapping(value = "/test-disguises", method = RequestMethod.POST)
    public HashMap<String, Object> testDisguise(@RequestBody HashMap<String, Object> params) {
        try {
            String encodeBody = ControllerUtil.getRequiredStringParam(params, "encodeBody");
            String decodeBody = ControllerUtil.getRequiredStringParam(params, "decodeBody");
            disguiseService.testDisguise(encodeBody, decodeBody);
            return ApiResponse.success("测试通过：encode和decode方法可以正确互逆");
        } catch (IllegalArgumentException e) {
            return toValidationResponse(e);
        } catch (Exception e) {
            return ApiResponse.error("测试过程中发生异常: " + e.getMessage());
        }
    }

    /**
     * 实时预览：编译 encodeBody/decodeBody，用给定的 params 执行 encode，
     * 返回编码后的字节（Base64 与可打印 ASCII），以及 decode 逆向结果。
     * 用于编辑器实时预览，无副作用。
     */
    @RequestMapping(value = "/preview", method = RequestMethod.POST)
    public HashMap<String, Object> previewDisguise(@RequestBody HashMap<String, Object> params) {
        try {
            String encodeBody = ControllerUtil.getRequiredStringParam(params, "encodeBody");
            String decodeBody = ControllerUtil.getRequiredStringParam(params, "decodeBody");

            // 用户自定义测试参数，默认使用标准测试数据
            Object customParams = params.get("testParams");
            java.util.HashMap<String, Object> testInput = new java.util.HashMap<>();
            if (customParams instanceof java.util.Map) {
                testInput.putAll((java.util.Map<String, Object>) customParams);
            } else {
                testInput.put("testKey", "hello_world");
                testInput.put("sessionId", "preview-session");
            }

            // 动态编译并执行
            Class<?> cls = JavassistDisguiseFactory.createDisguiseClass(encodeBody, decodeBody);
            Object instance = cls.getDeclaredConstructor().newInstance();

            java.lang.reflect.Method encMethod = cls.getMethod("encode", java.util.HashMap.class);
            java.lang.reflect.Method decMethod = cls.getMethod("decode", byte[].class);
            encMethod.setAccessible(true);
            decMethod.setAccessible(true);

            byte[] encoded = (byte[]) encMethod.invoke(instance, testInput);
            Object decoded = decMethod.invoke(instance, encoded);

            // Base64 编码结果
            String encodedBase64 = java.util.Base64.getEncoder().encodeToString(encoded);

            // 可打印字符摘要（截取前 200 字节中的可打印 ASCII）
            StringBuilder printable = new StringBuilder();
            int limit = Math.min(encoded.length, 200);
            for (int i = 0; i < limit; i++) {
                byte b = encoded[i];
                if (b >= 0x20 && b < 0x7F) printable.append((char) b);
                else printable.append('·');
            }
            if (encoded.length > 200) printable.append("…");

            // 十六进制摘要（前 32 字节）
            StringBuilder hex = new StringBuilder();
            int hexLimit = Math.min(encoded.length, 32);
            for (int i = 0; i < hexLimit; i++) {
                if (i > 0) hex.append(' ');
                hex.append(String.format("%02X", encoded[i] & 0xFF));
            }
            if (encoded.length > 32) hex.append(" …");

            HashMap<String, Object> data = new HashMap<>();
            data.put("encodedBase64", encodedBase64);
            data.put("encodedPrintable", printable.toString());
            data.put("encodedHex", hex.toString());
            data.put("encodedLength", encoded.length);
            data.put("decoded", decoded != null ? decoded.toString() : null);
            data.put("inverseOk", testInput.equals(decoded));
            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return toValidationResponse(e);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
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
        if ("disguise不存在".equals(message) || (message != null && message.startsWith("disguise文件不存在"))) {
            return ApiResponse.notFound(message);
        }
        return ApiResponse.badRequest(message);
    }
}
