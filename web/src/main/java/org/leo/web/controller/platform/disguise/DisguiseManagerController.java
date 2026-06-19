package org.leo.web.controller.platform.disguise;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Disguise;
import org.leo.core.entity.User;
import org.leo.service.disguise.DisguiseService;
import org.leo.service.disguise.DisguiseService.ConflictPolicy;
import org.leo.service.disguise.DisguiseService.ImportResult;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.javassist.JavassistDisguiseFactory;
import org.leo.web.util.ControllerUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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
    public HashMap<String, Object> delDisguise(@RequestBody HashMap<String, Object> params, HttpServletRequest request) {
        try {
            disguiseService.deleteDisguise(ControllerUtil.getRequiredStringParam(params, "disguiseId"), getCurrentUser(request));
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
    public HashMap<String, Object> updateDisguise(@RequestBody HashMap<String, Object> params, HttpServletRequest request) {
        try {
            disguiseService.updateDisguise(params, getCurrentUser(request));
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
    public HashMap<String, Object> testDisguise(@RequestBody HashMap<String, Object> params, HttpServletRequest request) {
        if (getCurrentUser(request) == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
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
     *
     * 注意：该接口会动态编译并执行任意 Java 代码，必须验证用户已登录，
     * 防止未授权用户利用编译/执行能力。
     */
    @RequestMapping(value = "/preview", method = RequestMethod.POST)
    public HashMap<String, Object> previewDisguise(@RequestBody HashMap<String, Object> params, HttpServletRequest request) {
        if (getCurrentUser(request) == null) {
            return ApiResponse.unauthorized("用户未登录");
        }
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

    // ── 导出 ──────────────────────────────────────────────────────────────────

    /**
     * 单条导出：GET /disguises/export?disguiseId=xxx
     * 返回加密的 .disguise 文件，内置伪装通过内存序列化导出。
     */
    @RequestMapping(value = "/disguises/export", method = RequestMethod.GET)
    public ResponseEntity<byte[]> exportDisguise(@RequestParam("disguiseId") String disguiseId) {
        if (disguiseId == null || disguiseId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body("disguiseId 不能为空".getBytes(StandardCharsets.UTF_8));
        }
        try {
            byte[] data = disguiseService.exportDisguise(disguiseId.trim());
            String filename = disguiseId.trim() + ".disguise";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .header(HttpHeaders.CONTENT_DISPOSITION, buildAttachment(filename))
                    .body(data);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(("导出失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 批量导出：POST /disguises/export/batch
     * 请求体：{ "disguiseIds": ["id1", "id2"] }
     * 返回 disguises_<date>.zip。
     */
    @RequestMapping(value = "/disguises/export/batch", method = RequestMethod.POST)
    public ResponseEntity<byte[]> exportDisguisesBatch(@RequestBody HashMap<String, Object> params) {
        Object idsObj = params == null ? null : params.get("disguiseIds");
        if (!(idsObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("disguiseIds 不能为空".getBytes(StandardCharsets.UTF_8));
        }
        List<String> ids = rawList.stream()
                .filter(o -> o instanceof String s && !s.isBlank())
                .map(o -> ((String) o).trim())
                .toList();
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("disguiseIds 不能为空".getBytes(StandardCharsets.UTF_8));
        }
        try {
            byte[] zip = disguiseService.exportDisguisesZip(ids);
            String filename = "disguises_" + LocalDate.now() + ".zip";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                    .header(HttpHeaders.CONTENT_DISPOSITION, buildAttachment(filename))
                    .body(zip);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(("批量导出失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 导入伪装：POST /disguises/import（multipart/form-data）
     * 参数：file（.disguise 或 .zip）、conflictPolicy（skip/overwrite/rename，默认 skip）
     * 响应：{ results: [{disguiseId, disguiseName, status, message}] }
     */
    @RequestMapping(value = "/disguises/import", method = RequestMethod.POST)
    public HashMap<String, Object> importDisguises(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpServletRequest request) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.badRequest("file 不能为空");
        }
        try {
            ConflictPolicy policy = ConflictPolicy.parse(conflictPolicy);
            List<ImportResult> results = disguiseService.importDisguises(file, policy, getCurrentUser(request));
            HashMap<String, Object> data = new HashMap<>();
            data.put("results", results.stream().map(ImportResult::toMap).toList());
            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return toValidationResponse(e);
        } catch (Exception e) {
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    /** 构造 RFC 5987 兼容的 Content-Disposition attachment 头。 */
    private static String buildAttachment(String filename) {
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        } catch (Exception e) {
            encoded = filename;
        }
        return "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded;
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
