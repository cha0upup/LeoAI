package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.ai.util.ToolResultUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ScriptTools {


    @Tool("执行轻量脚本与 Java 互操作代码。适用于 js、groovy、python，以及目标环境支持的 Java 相关脚本/代码执行场景，可用于临时计算、文本解析、数据清洗、格式转换和小范围 Java 代码验证；不适合作为首选文件侦察或长任务执行方式。")
    public Map<String, Object> execScript(String language, String script) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> result = node.execScript(language, script);
        // 脚本输出可能很长（如遍历打印），对 output/data/result 字段做智能压缩
        if (result != null) {
            for (String field : new String[]{"output", "data", "result"}) {
                if (result.containsKey(field)) {
                    ToolResultUtils.compressMapField(result, field, ToolResultUtils.DEFAULT_COMMAND_OUTPUT_THRESHOLD);
                    break;
                }
            }
        }
        return result;
    }
}
