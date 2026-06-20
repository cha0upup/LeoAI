package org.leo.web.controller.puppetnode.script;

import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 脚本临时执行控制器：直接发起 puppet 侧脚本执行，不持久化为插件。
 *
 * <p>底层通过 {@link JavaPuppetNode#execScript} 走 {@code ExecScriptService}，
 * 与 AI 工具 {@code ScriptTools.execScript()} 走完全相同的路径，
 * 保证组件加载缓存和包装行为一致。
 */
@RestController
@RequestMapping("/puppet-node")
public class ExecScriptController {

    private static final String PARAM_LANGUAGE = "language";
    private static final String PARAM_SCRIPT = "script";

    @RequestMapping(value = "/exec-script", method = RequestMethod.POST)
    public HashMap<String, Object> execScript(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode node = null;
        String language = null;
        try {
            node = ControllerUtil.getPuppetNode(params);
            language = ControllerUtil.getRequiredStringParam(params, PARAM_LANGUAGE);
            String script = ControllerUtil.getRequiredStringParam(params, PARAM_SCRIPT);

            // 复用已有 execScriptService 路径，与 AI 端 ScriptTools 行为一致
            Map<String, Object> result = node.execScript(language, script);
            AuditLogUtil.logSuccess(node, "EXEC_SCRIPT", "执行脚本", language, params,
                    ApiResponse.CODE_SUCCESS, "脚本执行成功", AuditLogUtil.getClientIp());
            return ApiResponse.success(result != null ? result : new HashMap<>());
        } catch (IllegalArgumentException e) {
            AuditLogUtil.logFailure(node, "EXEC_SCRIPT", "执行脚本", language, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            AuditLogUtil.logFailure(node, "EXEC_SCRIPT", "执行脚本", language, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("脚本执行失败: " + e.getMessage());
        }
    }
}

