package org.leo.ai.tools.platform;

import org.leo.service.fingerprint.FingerprintManageService;
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

    @Tool("创建或覆盖保存指纹。userId、name、ruleJson 必填；version 可直接传，或从 infoJson.version 读取；最终 fingerprintId 按 name+version 自动生成。")
    public Map<String, Object> saveFingerprint(
            @P("创建人用户 ID") String userId,
            @P("指纹名称") String name,
            @P("匹配规则 JSON") String ruleJson,
            @P(value = "规则元信息 JSON；仅保留 version、author、description、remark", required = false) String infoJson,
            @P(value = "标签数组 JSON", required = false) String tagsJson,
            @P(value = "版本；省略时尝试从 infoJson.version 读取", required = false) String version) throws Exception {
        return fingerprintManageService.saveFingerprint(userId, name, ruleJson, infoJson, tagsJson, version);
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.DESTRUCTIVE, exclusive = true)
    @Tool("删除指定 fingerprintId 对应的指纹文件。")
    public Map<String, Object> deleteFingerprint(
            @P("操作人用户 ID") String userId,
            @P("待删除指纹 ID") String fingerprintId) {
        fingerprintManageService.deleteFingerprint(userId, fingerprintId);
        HashMap<String, Object> result = new HashMap<>();
        result.put("status", "deleted");
        result.put("fingerprintId", fingerprintId);
        return result;
    }
}
