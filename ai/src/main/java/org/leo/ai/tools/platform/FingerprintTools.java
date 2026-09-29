package org.leo.ai.tools.platform;

import org.leo.service.fingerprint.FingerprintManageService;
import org.leo.core.entity.User;
import org.leo.core.util.json.JsonUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolAccess;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@AiToolAccess(AiToolAccess.Level.ADMIN)
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class FingerprintTools {

    private final FingerprintManageService fingerprintManageService;

    public FingerprintTools(FingerprintManageService fingerprintManageService) {
        this.fingerprintManageService = fingerprintManageService;
    }

    @Tool("列出平台全部 HTTP 指纹摘要。每项返回 fingerprintId、protocol、name、tags、info。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public List<Map<String, Object>> listFingerprints() {
        return fingerprintManageService.listFingerprints();
    }

    @Tool("根据 fingerprintId 获取指纹完整配置，返回完整对象，包括 rule。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> getFingerprintById(@P("指纹 ID") String fingerprintId) throws Exception {
        return fingerprintManageService.getFingerprintById(fingerprintId);
    }

    @Tool("创建或覆盖保存 HTTP 指纹。ruleJson 必须是声明式规则对象，infoJson 必须包含 version。")
    public Map<String, Object> saveFingerprint(
            @P("创建人用户 ID") String userId,
            @P("指纹名称") String name,
            @P("匹配规则 JSON") String ruleJson,
            @P("规则元信息 JSON；必须包含 version，可含 author、description、remark") String infoJson,
            @P(value = "标签数组 JSON", required = false) String tagsJson) throws Exception {
        HashMap<String, Object> params = new HashMap<>();
        params.put("name", name);
        params.put("protocol", "http");
        params.put("rule", parseJson(ruleJson, "ruleJson"));
        params.put("info", parseJson(infoJson, "infoJson"));
        if (tagsJson != null && !tagsJson.isBlank()) {
            params.put("tags", parseJson(tagsJson, "tagsJson"));
        }
        return fingerprintManageService.saveFingerprint(params, user(userId));
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.DESTRUCTIVE, exclusive = true)
    @Tool("删除指定 fingerprintId 对应的指纹文件。")
    public Map<String, Object> deleteFingerprint(
            @P("操作人用户 ID") String userId,
            @P("待删除指纹 ID") String fingerprintId) {
        fingerprintManageService.deleteFingerprint(user(userId), fingerprintId);
        HashMap<String, Object> result = new HashMap<>();
        result.put("status", "deleted");
        result.put("fingerprintId", fingerprintId);
        return result;
    }

    private Object parseJson(String json, String field) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        try {
            return JsonUtil.fromJsonString(json, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + "不是合法JSON", e);
        }
    }

    private User user(String userId) {
        User user = new User();
        user.setUserId(userId);
        return user;
    }
}
