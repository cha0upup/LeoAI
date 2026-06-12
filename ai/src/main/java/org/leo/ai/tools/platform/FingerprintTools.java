package org.leo.ai.tools.platform;

import org.leo.service.fingerprint.FingerprintManageService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class FingerprintTools {

    private final FingerprintManageService fingerprintManageService;

    public FingerprintTools(FingerprintManageService fingerprintManageService) {
        this.fingerprintManageService = fingerprintManageService;
    }

    @Tool("获取当前平台所有指纹摘要。每项返回 fingerprintId、protocol、name、tags、info。")
    public List<Map<String, Object>> getFingerprints() {
        return fingerprintManageService.listFingerprints();
    }

    @Tool("根据协议类型获取指纹摘要列表。支持如 http、tcp 等协议。")
    public List<Map<String, Object>> getFingerprintsByProtocol(String protocol) {
        return fingerprintManageService.getFingerprintsByProtocol(protocol);
    }

    @Tool("根据 fingerprintId 获取指纹完整配置，返回完整对象，包括 rule。")
    public Map<String, Object> getFingerprintById(String fingerprintId) throws Exception {
        return fingerprintManageService.getFingerprintById(fingerprintId);
    }

    @Tool("创建或覆盖保存指纹。userId、name、ruleJson 必填；version 可直接传，或从 infoJson.version 读取；最终 fingerprintId 按 name+version 自动生成。")
    public Map<String, Object> saveFingerprint(String userId, String name, String ruleJson,
                                               String infoJson, String protocol, String tagsJson,
                                               String version) throws Exception {
        return fingerprintManageService.saveFingerprint(userId, name, ruleJson, infoJson, protocol, tagsJson, version);
    }

    @Tool("删除指定 fingerprintId 对应的指纹文件。")
    public Map<String, Object> deleteFingerprint(String userId, String fingerprintId) {
        fingerprintManageService.deleteFingerprint(userId, fingerprintId);
        HashMap<String, Object> result = new HashMap<>();
        result.put("status", "deleted");
        result.put("fingerprintId", fingerprintId);
        return result;
    }
}
