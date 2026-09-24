package org.leo.web.controller.platform.session;

import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.platform.session.SessionDtos.CurrentHostIdResponse;
import org.leo.web.dto.platform.session.SessionDtos.SessionRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 会话 HostId 管理控制器。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code POST /platform/session/current-host-id}        — 获取当前 hostId</li>
 * </ul>
 */
@RestController
@RequestMapping("/platform/session")
public class HostIdController {

    @PostMapping("/current-host-id")
    public HashMap<String, Object> getCurrentHostId(@RequestBody SessionRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        return ApiResponse.success(new CurrentHostIdResponse(sessionId, session.getCurrentHostId()));
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(name + " 不能为空");
        }
        return value.trim();
    }
}
