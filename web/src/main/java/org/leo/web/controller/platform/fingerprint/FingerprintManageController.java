package org.leo.web.controller.platform.fingerprint;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.service.fingerprint.FingerprintManageService;
import org.leo.service.fingerprint.FingerprintManageService.ConflictPolicy;
import org.leo.service.fingerprint.FingerprintManageService.ImportResult;
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
import java.util.HashMap;
import java.util.List;

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

    // ── 导出 ──────────────────────────────────────────────────────────────────

    /**
     * 单条导出：GET /fingerprints/export?fingerprintId=xxx
     * 返回 <fingerprintId>.json 文件下载。
     */
    @RequestMapping(value = "/fingerprints/export", method = RequestMethod.GET)
    public ResponseEntity<byte[]> exportFingerprint(
            @RequestParam("fingerprintId") String fingerprintId) {
        try {
            byte[] data = fingerprintManageService.exportFingerprint(fingerprintId);
            String filename = fingerprintId.trim() + ".json";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.CONTENT_DISPOSITION, buildAttachment(filename))
                    .body(data);
        } catch (FingerprintManageService.FingerprintNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(("导出失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 批量导出：POST /fingerprints/export/batch
     * 请求体：{ "fingerprintIds": ["id1", "id2", ...] }
     * 返回 fingerprints_<date>.zip 文件下载。
     */
    @RequestMapping(value = "/fingerprints/export/batch", method = RequestMethod.POST)
    public ResponseEntity<byte[]> exportFingerprintsBatch(
            @RequestBody HashMap<String, Object> params) {
        Object idsObj = params.get("fingerprintIds");
        if (!(idsObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("fingerprintIds 不能为空".getBytes(StandardCharsets.UTF_8));
        }
        List<String> ids = rawList.stream()
                .filter(o -> o instanceof String s && !s.isBlank())
                .map(o -> ((String) o).trim())
                .toList();
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("fingerprintIds 不能为空".getBytes(StandardCharsets.UTF_8));
        }
        try {
            byte[] zip = fingerprintManageService.exportFingerprintsZip(ids);
            String filename = "fingerprints_" + LocalDate.now() + ".zip";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                    .header(HttpHeaders.CONTENT_DISPOSITION, buildAttachment(filename))
                    .body(zip);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(("批量导出失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 导入指纹：POST /fingerprints/import（multipart/form-data）
     * 参数：file（.json 或 .zip）、conflictPolicy（skip/overwrite/rename，默认 skip）
     * 响应：{ results: [{name, fingerprintId, status, message}] }
     */
    @RequestMapping(value = "/fingerprints/import", method = RequestMethod.POST)
    public HashMap<String, Object> importFingerprints(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy,
            HttpServletRequest request) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.badRequest("file 不能为空");
        }
        try {
            ConflictPolicy policy = ConflictPolicy.parse(conflictPolicy);
            List<ImportResult> results = fingerprintManageService.importFingerprints(
                    file, policy, getCurrentUser(request));
            HashMap<String, Object> data = new HashMap<>();
            data.put("results", results.stream().map(ImportResult::toMap).toList());
            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return toValidationResponse(e);
        } catch (Exception e) {
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

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

    /** 构造 RFC 5987 兼容的 Content-Disposition attachment 头，支持中文文件名。 */
    private static String buildAttachment(String filename) {
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8)
                    .replace("+", "%20");
        } catch (Exception e) {
            encoded = filename;
        }
        return "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded;
    }
}

