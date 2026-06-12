package org.leo.web.controller.puppetnode.screen;


import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

@RestController
@RequestMapping("/puppet-node/screen")
public class ScreenController {
    private static final String PARAM_SESSION_ID = "sessionId";
    private static final String PARAM_FORMAT = "format";
    private static final String PARAM_QUALITY = "quality";
    private static final String PARAM_DELAY = "delay";

    // 结果字段常量
    private static final String RESULT_CAPTURE_TIME = "captureTime";
    private static final String RESULT_SCREEN_BYTES = "screenBytes";
    private static final String RESULT_IMAGE_SIZE = "imageSize";
    private static final String RESULT_FORMAT = "format";
    private static final String RESULT_WIDTH = "width";
    private static final String RESULT_HEIGHT = "height";
    private static final String RESULT_SUPPORTED = "supported";
    private static final String RESULT_ERROR_TYPE = "errorType";


    // 默认值常量
    private static final String DEFAULT_FORMAT = "jpg";


    /**
     * 开始截图
     */
    @RequestMapping(value = "/start", method = RequestMethod.POST)
    public HashMap<String, Object> startScreenshot(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode javaPuppetNode = null;
        String sessionId = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);
            sessionId = ControllerUtil.getRequiredStringParam(params, PARAM_SESSION_ID);

            // 准备截图参数
            HashMap<String, Object> screenshotParams = new HashMap<String, Object>();

            // 从请求参数中提取截图配置（可选）
            String format = (String) params.get(PARAM_FORMAT);
            if (format != null) {
                screenshotParams.put(PARAM_FORMAT, format);
            }

            Number quality = (Number) params.get(PARAM_QUALITY);
            if (quality != null) {
                screenshotParams.put(PARAM_QUALITY, quality);
            }

            Number delay = (Number) params.get(PARAM_DELAY);
            if (delay != null) {
                screenshotParams.put(PARAM_DELAY, delay);
            }

            // 执行截图
            HashMap<String, Object> screenshotResult = (HashMap<String, Object>) javaPuppetNode.invokeComponent(
                    "ScreenComponent",
                    screenshotParams
            );

            if (screenshotResult == null || screenshotResult.get("code") == null) {
                throw new RuntimeException("截图组件返回结果异常");
            }

            int code = ((Number) screenshotResult.get("code")).intValue();

            // 处理特殊错误码
            if (code == 501) {
                // Headless环境错误
                HashMap<String, Object> errorData = new HashMap<String, Object>();
                errorData.put(RESULT_SUPPORTED, false);
                errorData.put(RESULT_ERROR_TYPE, screenshotResult.get(RESULT_ERROR_TYPE));
                AuditLogUtil.logFailure(javaPuppetNode, "SCREENSHOT", "屏幕截图", sessionId, params,
                        "当前环境不支持屏幕截图", AuditLogUtil.getClientIp());
                return ApiResponse.error("当前环境不支持屏幕截图：" + screenshotResult.get("msg"));
            }

            if (code != ApiResponse.CODE_SUCCESS) {
                String errorMsg = (String) screenshotResult.get("msg");
                throw new RuntimeException("截图失败: " + errorMsg);
            }

            // 获取截图数据
            byte[] screenBytes = (byte[]) screenshotResult.get(RESULT_SCREEN_BYTES);
            if (screenBytes == null || screenBytes.length == 0) {
                throw new RuntimeException("截图数据为空");
            }

            String formatUsed = (String) screenshotResult.getOrDefault(RESULT_FORMAT, DEFAULT_FORMAT);
            Number captureTime = (Number) screenshotResult.get(RESULT_CAPTURE_TIME);

            // 返回结果
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put(RESULT_CAPTURE_TIME, captureTime);
            data.put(RESULT_SCREEN_BYTES, screenBytes);
            data.put(RESULT_IMAGE_SIZE, screenBytes.length);
            data.put(RESULT_FORMAT, formatUsed);
            data.put(RESULT_WIDTH, screenshotResult.get(RESULT_WIDTH));
            data.put(RESULT_HEIGHT, screenshotResult.get(RESULT_HEIGHT));
            AuditLogUtil.logSuccess(javaPuppetNode, "SCREENSHOT", "屏幕截图", sessionId, params,
                    ApiResponse.CODE_SUCCESS, "截图成功", AuditLogUtil.getClientIp());
            return ApiResponse.success("截图成功", data);

        } catch (Exception e) {
            AuditLogUtil.logFailure(javaPuppetNode, "SCREENSHOT", "屏幕截图", sessionId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("截图失败: " + e.getMessage());
        }
    }

    /**
     * 检查环境是否支持截图
     */
    @RequestMapping(value = "/check-environment", method = RequestMethod.POST)
    public HashMap<String, Object> checkEnvironment(@RequestBody HashMap<String, Object> params) {
        try {
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);

            // 调用组件检查环境
            HashMap<String, Object> checkParams = new HashMap<String, Object>();
            HashMap<String, Object> checkResult = (HashMap<String, Object>) javaPuppetNode.invokeComponent(
                    "ScreenComponent",
                    checkParams
            );

            HashMap<String, Object> data = new HashMap<String, Object>();
            if (checkResult != null && checkResult.get("code") != null) {
                int code2 = ((Number) checkResult.get("code")).intValue();
                if (code2 == 501) {
                    data.put(RESULT_SUPPORTED, false);
                    data.put(RESULT_ERROR_TYPE, checkResult.get(RESULT_ERROR_TYPE));
                    return ApiResponse.success("当前环境不支持屏幕截图", data);
                } else {
                    data.put(RESULT_SUPPORTED, true);
                    return ApiResponse.success("当前环境支持屏幕截图", data);
                }
            } else {
                data.put(RESULT_SUPPORTED, true);
                return ApiResponse.success("环境检查完成", data);
            }

        } catch (Exception e) {
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put(RESULT_SUPPORTED, false);
            return ApiResponse.error("环境检查失败: " + e.getMessage());
        }
    }
}
