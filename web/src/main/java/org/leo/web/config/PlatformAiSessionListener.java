package org.leo.web.config;

import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.leo.ai.platform.PlatformAiStateStore;

/**
 * HTTP Session 销毁监听器，用于及时清理平台侧 AI 状态。
 *
 * <p>当用户退出、Session 过期或服务器主动 invalidate 时，
 * 自动从 {@link PlatformAiStateStore} 中移除对应条目，避免内存泄漏。
 *
 * <p>通过 {@link WebConfig#platformAiSessionListener()} 注册到 Servlet 容器。
 */
@WebListener
public class PlatformAiSessionListener implements HttpSessionListener {

    private static final String SESSION_ATTR_PLATFORM_AI_STATE_ID = "platformAiStateId";

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        HttpSession session = se.getSession();
        Object stateId = session.getAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID);
        if (stateId instanceof String id && !id.isBlank()) {
            PlatformAiStateStore.remove(id);
        }
    }
}
