package org.leo.web.controller.platform.ai;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.ai.audit.AiAuditLogStore;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.util.ApiResponse;
import org.leo.web.security.PermissionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** AI 对话审计日志管理，仅管理员可访问；服务重启后清空。 */
@RestController
@RequestMapping("/platform/ai/audit")
public class AiAuditLogController {

    private static final int DEFAULT_LIMIT = 100;

    private final AiAuditLogStore store;
    private final PermissionService permissionService;

    public AiAuditLogController(AiAuditLogStore store, PermissionService permissionService) {
        this.store = store;
        this.permissionService = permissionService;
    }

    /**
     * 查询最近的 AI 对话审计记录。
     *
     * @param body 可选参数：{@code limit}（默认 100）
     */
    @PostMapping("/logs")
    public Map<String, Object> logs(@RequestBody(required = false) AuditLogsRequest body,
                                    HttpServletRequest request) {
        requireAdmin(request);
        int limit = body != null && body.limit() != null ? body.limit() : DEFAULT_LIMIT;
        AiAuditLogStore.Snapshot snapshot = store.snapshot(limit);
        return ApiResponse.success(new AuditLogsResponse(snapshot.total(), snapshot.logs().size(), snapshot.logs()));
    }

    /** 清空所有审计记录（仅 admin）。 */
    @PostMapping("/clear")
    public Map<String, Object> clear(HttpServletRequest request) {
        requireAdmin(request);
        store.clear();
        return ApiResponse.success(true);
    }

    private void requireAdmin(HttpServletRequest request) {
        permissionService.requireAdmin(permissionService.requireLogin(request));
    }

    private record AuditLogsRequest(Integer limit) {
    }

    private record AuditLogsResponse(int total, int returned, List<AiChatAuditEntry.Snapshot> logs) {
    }
}
