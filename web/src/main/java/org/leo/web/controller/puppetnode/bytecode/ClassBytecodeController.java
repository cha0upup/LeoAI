package org.leo.web.controller.puppetnode.bytecode;


import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.decompiler.DecompilerUtil;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/class-bytecode")
public class ClassBytecodeController {
    /**
     * 获取类字节码
     *
     * @param params 请求参数，必须包含：
     *               - sessionId: 会话ID
     *               - className: 完整类名（包含包名），例如：java.lang.String
     * @return 包含类字节码的响应
     */
    @RequestMapping(value = "/get", method = RequestMethod.POST)
    public HashMap<String, Object> getClassBytecode(@RequestBody HashMap<String, Object> params) {

        try {
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            String className = (String) params.get("className");

            Map<String, Object> componentResult = javaPuppetNode.getClassBytecode(className);
            if (componentResult == null || componentResult.get("code") == null) {
                throw new RuntimeException("类字节码组件返回结果异常");
            }

            int code = ((Number) componentResult.get("code")).intValue();
            if (code != ApiResponse.CODE_SUCCESS) {
                String errorMsg = (String) componentResult.get("msg");
                throw new RuntimeException("获取类字节码失败: " + (errorMsg != null ? errorMsg : "未知错误"));
            }

            // 获取字节码数据
            byte[] bytecode = (byte[]) componentResult.get("bytecode");
            if (bytecode == null || bytecode.length == 0) {
                throw new RuntimeException("类字节码数据为空");
            }
            // 反编译字节码为Java源代码
            String javaCode = null;
            try {
                javaCode = DecompilerUtil.decompile(bytecode);
            } catch (IOException e) {
                // 反编译失败不影响返回字节码，只记录警告
                javaCode = null;
            }
            // 返回结果
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("bytecode", bytecode);
            data.put("bytecodeSize", bytecode.length);
            data.put("className", className);
            if (javaCode != null) {
                data.put("javaCode", javaCode);
            }

            return ApiResponse.success("获取类字节码成功", data);

        } catch (Exception e) {
            return ApiResponse.error("获取类字节码失败: " + e.getMessage());
        }
    }
}
