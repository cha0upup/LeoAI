package org.leo.web.controller.platform.session;

import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.platform.session.SessionDtos.AllHostIdsResponse;
import org.leo.web.dto.platform.session.SessionDtos.CurrentHostIdResponse;
import org.leo.web.dto.platform.session.SessionDtos.HostIdContainsResponse;
import org.leo.web.dto.platform.session.SessionDtos.HostIdMutateResponse;
import org.leo.web.dto.platform.session.SessionDtos.HostIdRequest;
import org.leo.web.dto.platform.session.SessionDtos.SessionRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * 会话 HostId 管理控制器。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code POST /platform/session/current-host-id}        — 获取当前 hostId</li>
 *   <li>{@code POST /platform/session/current-host-id/set}    — 设置当前 hostId</li>
 *   <li>{@code POST /platform/session/all-host-ids}           — 获取所有 hostId</li>
 *   <li>{@code POST /platform/session/host-id/add}            — 添加 hostId</li>
 *   <li>{@code POST /platform/session/host-id/remove}         — 移除 hostId</li>
 *   <li>{@code POST /platform/session/host-id/contains}       — 检查 hostId 是否存在</li>
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

    @PostMapping("/current-host-id/set")
    public HashMap<String, Object> setCurrentHostId(@RequestBody HostIdRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        String hostId    = requireText(request.hostId(),    "hostId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        session.setCurrentHostId(hostId);
        return ApiResponse.success(new CurrentHostIdResponse(sessionId, session.getCurrentHostId()));
    }

    @PostMapping("/all-host-ids")
    public HashMap<String, Object> getAllHostIds(@RequestBody SessionRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        var ids = new ArrayList<>(session.getAllHostIds());
        return ApiResponse.success(new AllHostIdsResponse(sessionId, ids, ids.size()));
    }

    @PostMapping("/host-id/add")
    public HashMap<String, Object> addHostId(@RequestBody HostIdRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        String hostId    = requireText(request.hostId(),    "hostId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        boolean added = session.addHostId(hostId);
        var ids = new ArrayList<>(session.getAllHostIds());
        return ApiResponse.success(new HostIdMutateResponse(
                sessionId, ids, added, added ? "hostId 添加成功" : "hostId 已存在"));
    }

    @PostMapping("/host-id/remove")
    public HashMap<String, Object> removeHostId(@RequestBody HostIdRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        String hostId    = requireText(request.hostId(),    "hostId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        boolean removed = session.removeHostId(hostId);
        var ids = new ArrayList<>(session.getAllHostIds());
        return ApiResponse.success(new HostIdMutateResponse(
                sessionId, ids, removed, removed ? "hostId 移除成功" : "hostId 不存在"));
    }

    @PostMapping("/host-id/contains")
    public HashMap<String, Object> containsHostId(@RequestBody HostIdRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        String hostId    = requireText(request.hostId(),    "hostId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        return ApiResponse.success(
                new HostIdContainsResponse(sessionId, hostId, session.containsHostId(hostId)));
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(name + " 不能为空");
        }
        return value.trim();
    }
}
