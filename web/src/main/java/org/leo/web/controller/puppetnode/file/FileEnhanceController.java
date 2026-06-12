package org.leo.web.controller.puppetnode.file;

import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 文件操作增强接口
 *
 * <ul>
 *   <li>POST /puppet-node/file/enhance/grep   — 递归内容搜索</li>
 *   <li>POST /puppet-node/file/enhance/touch  — 修改时间戳</li>
 *   <li>POST /puppet-node/file/enhance/pack   — 打包目录为 tar.gz</li>
 *   <li>POST /puppet-node/file/enhance/rename — 重命名文件或目录</li>
 *   <li>POST /puppet-node/file/enhance/chmod  — 修改文件权限</li>
 * </ul>
 */
@RestController
@RequestMapping("/puppet-node/file/enhance")
public class FileEnhanceController {

    private static final String COMPONENT = "FileEnhanceComponent";

    private static final int ACTION_GREP   = 1;
    private static final int ACTION_TOUCH  = 2;
    private static final int ACTION_PACK   = 3;
    private static final int ACTION_RENAME = 4;
    private static final int ACTION_CHMOD  = 5;

    /** 递归关键词搜索文件内容 */
    @RequestMapping(value = "/grep", method = RequestMethod.POST)
    public HashMap<String, Object> grep(@RequestBody HashMap<String, Object> params) {
        return invoke(params, ACTION_GREP);
    }

    /** 修改文件/目录时间戳 */
    @RequestMapping(value = "/touch", method = RequestMethod.POST)
    public HashMap<String, Object> touch(@RequestBody HashMap<String, Object> params) {
        return invoke(params, ACTION_TOUCH);
    }

    /** 将目录打包为 tar.gz */
    @RequestMapping(value = "/pack", method = RequestMethod.POST)
    public HashMap<String, Object> pack(@RequestBody HashMap<String, Object> params) {
        return invoke(params, ACTION_PACK);
    }

    /** 重命名文件或目录 */
    @RequestMapping(value = "/rename", method = RequestMethod.POST)
    public HashMap<String, Object> rename(@RequestBody HashMap<String, Object> params) {
        return invoke(params, ACTION_RENAME);
    }

    /** 修改文件/目录权限 */
    @RequestMapping(value = "/chmod", method = RequestMethod.POST)
    public HashMap<String, Object> chmod(@RequestBody HashMap<String, Object> params) {
        return invoke(params, ACTION_CHMOD);
    }

    private HashMap<String, Object> invoke(HashMap<String, Object> params, int action) {
        try {
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            HashMap<String, Object> componentParams = new HashMap<String, Object>(params);
            componentParams.put("classname", COMPONENT);
            componentParams.put("action", action);
            HashMap<String, Object> result =
                    (HashMap<String, Object>) node.invokeComponent(COMPONENT, componentParams);
            if (result == null) return ApiResponse.error("组件未返回结果");
            Object codeObj = result.get("code");
            int code = codeObj instanceof Number ? ((Number) codeObj).intValue() : -1;
            if (code != 200) {
                return ApiResponse.error((String) result.getOrDefault("msg", "执行失败"));
            }
            result.remove("classname");
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error("执行失败: " + e.getMessage());
        }
    }
}
