package org.leo.ai.tools.puppetnode;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Windows 注册表操作工具。逻辑在 RegistryService（core 模块）。
 */
@Component
public class RegistryTools {

    @Tool("查询 puppet 侧 Windows 注册表键，返回该键下所有值及可选子键。仅 Windows 有效。")
    public Map<String, Object> queryRegistry(
            @P("注册表键路径，如 HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run") String keyPath,
            @P("是否递归查询所有子键（默认 false）") boolean recursive) throws Exception {
        return node().queryRegistry(keyPath, recursive);
    }

    @Tool("在 puppet 侧 Windows 注册表指定路径下搜索关键字。searchIn: k=键名 v=值名 d=值数据（默认 d）。仅 Windows 有效。")
    public Map<String, Object> searchRegistry(
            @P("搜索起始路径（如 HKLM、HKCU），默认 HKLM") String rootPath,
            @P("搜索关键字") String pattern,
            @P("搜索范围：k=键名, v=值名, d=值数据（默认 d）") String searchIn,
            @P("最多返回条数（默认 50）") int maxResults) throws Exception {
        return node().searchRegistry(rootPath, pattern, searchIn, maxResults);
    }

    @Tool("在 puppet 侧 Windows 注册表创建或修改指定值。⚠️ 写操作。仅 Windows 有效。")
    public Map<String, Object> setRegistryValue(
            @P("注册表键路径") String keyPath,
            @P("值名称，为空表示设置默认值") String valueName,
            @P("值类型：REG_SZ / REG_DWORD / REG_QWORD / REG_EXPAND_SZ / REG_BINARY（默认 REG_SZ）") String valueType,
            @P("值数据") String valueData) throws Exception {
        return node().addRegistry(keyPath, valueName, valueType, valueData, true);
    }

    @Tool("删除 puppet 侧 Windows 注册表指定值（valueName 为空则删除键下所有值）。⚠️ 不可逆。仅 Windows 有效。")
    public Map<String, Object> deleteRegistryValue(
            @P("注册表键路径") String keyPath,
            @P("值名称，为空则删除该键下所有值（不删子键）") String valueName) throws Exception {
        return node().deleteRegistry(keyPath, valueName, true);
    }

    private JavaPuppetNode node() throws Exception {
        return PuppetNodeSessionUtils.getJavaPuppetNode(AiToolContext.requireSessionId());
    }
}
