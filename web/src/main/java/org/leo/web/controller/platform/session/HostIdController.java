package org.leo.web.controller.platform.session;

import org.leo.core.session.AgentNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.platform.session.SessionDtos.CurrentHostIdResponse;
import org.leo.web.dto.platform.session.SessionDtos.SessionRequest;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/** 会话 HostId 查询。 */
@RestController
@RequestMapping("/platform/session")
public class HostIdController {

    @PostMapping("/current-host-id")
    public HashMap<String, Object> getCurrentHostId(@RequestBody SessionRequest request) {
        String sessionId = ControllerUtil.requireText(request.sessionId(), "sessionId 不能为空");
        AgentNodeSession session = ControllerUtil.getAgentNodeSession(sessionId);
        return ApiResponse.success(new CurrentHostIdResponse(sessionId, session.getCurrentHostId()));
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────
}
