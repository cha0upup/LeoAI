package org.leo.web.controller.puppetnode.resource;

import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.decompiler.DecompilerUtil;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 通用资源读取控制器。
 *
 * <p>用户场景：浏览 puppet 进程能看到的任意 classpath 资源（jar 内的 .class /
 * application.yml / META-INF/MANIFEST.MF / mybatis-mapper.xml 等）。
 *
 * <p>与 {@link org.leo.web.controller.puppetnode.bytecode.ClassBytecodeController} 互补：
 * - ClassBytecode：面向「类名」，自动反编译；只回 class 字节码
 * - 本控制器：面向「任意资源路径」，按内容类型决定是否反编译；同时返回十六进制/文本预览
 *
 * <p>底层走 {@code ResourceComponent}，已包含 contextCL / systemCL / 所有
 * Tomcat WebappClassLoader 三层降级，在独立 Tomcat + puppet 在 commonLoader
 * 的部署下也能拿到 webapp 里的资源。
 */
@RestController
@RequestMapping("/puppet-node/resource")
public class ResourceBrowseController {

    /** 单次响应文本预览的字符上限，避免 30MB+ 的 jar 内资源把前端卡住。 */
    private static final int TEXT_PREVIEW_MAX_CHARS = 256 * 1024;

    @RequestMapping(value = "/get", method = RequestMethod.POST)
    public HashMap<String, Object> getResource(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode node = null;
        String resourcePath = null;
        try {
            node = ControllerUtil.getPuppetNode(params);
            String mode = ControllerUtil.getOptionalStringParam(params, "mode");
            String className = ControllerUtil.getOptionalStringParam(params, "className");
            resourcePath = ControllerUtil.getOptionalStringParam(params, "resourcePath");

            // 入参标准化：mode=class 时优先用 className 派生路径
            if (resourcePath == null || resourcePath.isBlank()) {
                if ("class".equalsIgnoreCase(mode) && className != null && !className.isBlank()) {
                    resourcePath = className.trim().replace('.', '/') + ".class";
                } else if (className != null && !className.isBlank()) {
                    // 兼容前端只传 className 的情况
                    resourcePath = className.trim().replace('.', '/') + ".class";
                }
            }
            if (resourcePath == null || resourcePath.isBlank()) {
                return ApiResponse.badRequest("resourcePath 或 className 不能同时为空");
            }
            resourcePath = resourcePath.trim();

            Map<String, Object> componentResult = node.getResource(resourcePath);
            if (componentResult == null || componentResult.get("code") == null) {
                return ApiResponse.error("资源组件返回结果异常");
            }
            int code = ((Number) componentResult.get("code")).intValue();
            if (code != ApiResponse.CODE_SUCCESS) {
                String msg = (String) componentResult.get("msg");
                AuditLogUtil.logFailure(node, "RESOURCE_GET", "读取资源", resourcePath, params,
                        msg, AuditLogUtil.getClientIp());
                if (code == 404) {
                    return ApiResponse.notFound(msg != null ? msg : "资源未找到");
                }
                return ApiResponse.error(msg != null ? msg : "读取资源失败");
            }

            byte[] bytes = (byte[]) componentResult.get("bytecode");
            if (bytes == null) bytes = (byte[]) componentResult.get("data");
            if (bytes == null || bytes.length == 0) {
                return ApiResponse.error("资源内容为空");
            }

            HashMap<String, Object> data = new HashMap<>();
            data.put("resourcePath", resourcePath);
            data.put("size", bytes.length);
            data.put("base64", Base64.getEncoder().encodeToString(bytes));

            // 内容类型判定 + 预览
            boolean isClass = isClassBytecode(bytes);
            data.put("isClass", isClass);
            if (isClass) {
                try {
                    data.put("javaCode", DecompilerUtil.decompile(bytes));
                    data.put("preview", "java");
                } catch (Throwable t) {
                    data.put("decompileError", t.getMessage());
                    data.put("preview", "hex");
                }
            } else if (looksTextual(bytes)) {
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (text.length() > TEXT_PREVIEW_MAX_CHARS) {
                    data.put("text", text.substring(0, TEXT_PREVIEW_MAX_CHARS));
                    data.put("textTruncated", true);
                } else {
                    data.put("text", text);
                }
                data.put("preview", "text");
            } else {
                data.put("preview", "hex");
            }

            AuditLogUtil.logSuccess(node, "RESOURCE_GET", "读取资源", resourcePath, params,
                    ApiResponse.CODE_SUCCESS, "读取资源成功", AuditLogUtil.getClientIp());
            return ApiResponse.success("读取资源成功", data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            AuditLogUtil.logFailure(node, "RESOURCE_GET", "读取资源", resourcePath, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("读取资源失败: " + e.getMessage());
        }
    }

    /** JVM .class 文件 magic number: 0xCAFEBABE。 */
    private static boolean isClassBytecode(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && (bytes[0] & 0xFF) == 0xCA
                && (bytes[1] & 0xFF) == 0xFE
                && (bytes[2] & 0xFF) == 0xBA
                && (bytes[3] & 0xFF) == 0xBE;
    }

    /**
     * 简单文本判定：前 8KB 不含 NUL 字节即视为文本（与项目里其他 skill 文件判定逻辑保持一致）。
     */
    private static boolean looksTextual(byte[] bytes) {
        int len = Math.min(bytes.length, 8192);
        for (int i = 0; i < len; i++) {
            if (bytes[i] == 0) return false;
        }
        return true;
    }
}
