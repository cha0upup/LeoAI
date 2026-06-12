package org.leo.web.controller.platform.session;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.service.PuppetService;
import org.leo.web.dto.platform.session.SessionDtos.ConnLinkChainResponse;
import org.leo.web.dto.platform.session.SessionDtos.ConnLinkItem;
import org.leo.web.dto.platform.session.SessionDtos.SessionInfo;
import org.leo.web.dto.platform.session.SessionDtos.SessionRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.util.ControllerUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 会话生命周期管理控制器。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET  /platform/session/sessions}         — 获取当前用户所有会话</li>
 *   <li>{@code POST /platform/session/sessions/delete}  — 删除会话</li>
 *   <li>{@code POST /platform/session/conn-link-chain}  — 获取连接链路链</li>
 * </ul>
 */
@RestController
@RequestMapping("/platform/session")
public class SessionManageController {

    private final PuppetService puppetService;

    @Autowired
    public SessionManageController(PuppetService puppetService) {
        this.puppetService = puppetService;
    }

    /**
     * 获取当前用户的所有会话连接。
     */
    @GetMapping("/sessions")
    public HashMap<String, Object> getAllSessions(HttpServletRequest request) {
        User user = ControllerUtil.getCurrentUser(request);
        Map<String, PuppetNodeSession> sessionMap = PuppetNodeSessionContainer.getAllSession();

        Set<SessionInfo> sessions = sessionMap.entrySet().stream()
                .filter(e -> isValidSession(e.getValue()))
                .filter(e -> ControllerUtil.canAccessSession(e.getValue(), user))
                .map(e -> toSessionInfo(e.getKey(), e.getValue()))
                .collect(Collectors.toSet());

        return ApiResponse.success(sessions);
    }

    /**
     * 删除会话及其工作目录。
     */
    @PostMapping("/sessions/delete")
    public HashMap<String, Object> deleteSession(@RequestBody SessionRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        ControllerUtil.getPuppetNodeSession(sessionId);
        if (!PuppetNodeSessionWorkDirUtil.deleteSessionWorkDir(sessionId)) {
            throw ApiException.serverError("删除会话目录失败: " + sessionId);
        }
        PuppetNodeSessionContainer.removeSession(sessionId);
        return ApiResponse.success();
    }

    /**
     * 获取当前会话的连接链路链（从当前节点到根节点）。
     */
    @PostMapping("/conn-link-chain")
    public HashMap<String, Object> getConnLinkChain(@RequestBody SessionRequest request) {
        String sessionId = requireText(request.sessionId(), "sessionId");
        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);

        List<ConnLinkItem> chain;
        if (session.isCacheMode()) {
            chain = buildCacheModeChain(session);
        } else {
            if (session.getJavaPuppetNode() == null || session.getJavaPuppetNode().getPuppet() == null) {
                throw ApiException.badRequest("会话中不存在 Puppet 实体: " + sessionId);
            }
            chain = buildLiveChain(session);
        }

        return ApiResponse.success(new ConnLinkChainResponse(sessionId, chain));
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────

    private boolean isValidSession(PuppetNodeSession session) {
        return session != null
                && session.getJavaPuppetNode() != null
                && session.getJavaPuppetNode().getPuppet() != null;
    }

    private SessionInfo toSessionInfo(String sessionId, PuppetNodeSession session) {
        Puppet puppet = session.getJavaPuppetNode().getPuppet();
        return new SessionInfo(
                sessionId,
                puppet.getPuppetName(),
                puppet.getConnLink(),
                puppet.getParentPuppetId(),
                session.getUpdateTime());
    }

    private List<ConnLinkItem> buildCacheModeChain(PuppetNodeSession session) {
        List<ConnLinkItem> chain = new ArrayList<>();
        String pId = session.getPuppetId();
        if (pId != null && !pId.isBlank()) {
            Puppet puppet = puppetService.findPuppetById(pId);
            if (puppet != null) {
                chain.add(toConnLinkItem(puppet));
            }
        }
        return chain;
    }

    private List<ConnLinkItem> buildLiveChain(PuppetNodeSession session) {
        return session.buildConnLinkChain(puppetService::findPuppetById)
                .stream()
                .map(m -> new ConnLinkItem(
                        str(m, "puppetId"),
                        str(m, "puppetName"),
                        str(m, "connLink"),
                        str(m, "parentPuppetId"),
                        str(m, "protocol")))
                .toList();
    }

    private ConnLinkItem toConnLinkItem(Puppet p) {
        return new ConnLinkItem(
                p.getPuppetId(),
                p.getPuppetName(),
                p.getConnLink(),
                p.getParentPuppetId(),
                p.getProtocol());
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(name + " 不能为空");
        }
        return value.trim();
    }
}
