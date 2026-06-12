package org.leo.web.controller.platform.ai;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.ai.audit.AiAuditLogStore;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.platform.ai.PlatformAiDtos.AuditLogsRequest;
import org.leo.web.dto.platform.ai.PlatformAiDtos.AuditLogsResponse;
import org.leo.web.security.PermissionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 审计日志查询接口（仅 admin 可访问）。
 *
 * <p>日志数据存储在内存中（最近 2000 条），服务重启后清空。
 * 如需持久化，可扩展 {@link AiAuditLogStore} 将记录写入数据库或日志文件。
 */
@RestController
@RequestMapping("/platform/ai/audit")
public class AiAuditLogController {

    private final AiAuditLogStore store;
    private final PermissionService permissionService;

    public AiAuditLogController(AiAuditLogStore store, PermissionService permissionService) {
        this.store = store;
        this.permissionService = permissionService;
    }

    /**
     * 查询最近的 AI 对话审计记录。
     *
     * @param params 可选参数：{@code limit}（默认 100）
     */
    @PostMapping("/logs")
    public Map<String, Object> logs(@RequestBody(required = false) AuditLogsRequest body,
                                    HttpServletRequest request) {
        requireAdmin(request);
        int limit = body != null && body.limit() != null
                ? Math.min(body.limit(), 2000)
                : 100;
        List<AiChatAuditEntry> entries = store.recent(limit);
        return ApiResponse.success(new AuditLogsResponse(store.size(), entries.size(), entries));
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
}
