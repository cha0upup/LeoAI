package org.leo.core.entity;

import org.leo.core.util.javassist.JavassistDisguiseFactory;
import org.leo.core.util.json.JsonUtil;
import org.leo.core.disguise.DisguiseProtocol;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Traffic wrapper definition; payload serialization and encryption are external codecs. */
public class Disguise {

    private String disguiseId;
    private String disguiseName;
    /** Java traffic-only methods. They wrap opaque payload bytes. */
    private String trafficEncodeBody;
    private String trafficDecodeBody;

    private Integer schemaVersion = DisguiseProtocol.SCHEMA_VERSION;
    private Integer protocolVersion = DisguiseProtocol.PROTOCOL_VERSION;

    /** PHP traffic function bodies. Encode receives opaque bytes; decode receives the HTTP body. */
    private String phpTrafficEncodeBody;
    private String phpTrafficDecodeBody;
    private Set<String> supportedRuntimes;
    private Map<String, Object> requirements;

    private Map<String, String> headers;

    private String version;
    private String createUserId;
    private String createTime;
    private String updateTime;
    private String description;
    private String remark;

    /** 运行时字段（不建议持久化） */
    private transient Class<?> handlerClass;
    private transient Method trafficEncodeMethod;
    private transient Method trafficDecodeMethod;

    // ================== 初始化 ==================

    public synchronized void init() throws Exception {
        if (handlerClass != null) {
            return;
        }

        if (!isTrafficOnly()) {
            throw new IllegalStateException("Disguise 必须配置 traffic 编解码");
        }
        handlerClass = JavassistDisguiseFactory.createTrafficDisguiseClass(
                getTrafficEncodeBody(), getTrafficDecodeBody());
        trafficEncodeMethod = handlerClass.getMethod("encodeTraffic", byte[].class);
        trafficDecodeMethod = handlerClass.getMethod("decodeTraffic", byte[].class);
        trafficEncodeMethod.setAccessible(true);
        trafficDecodeMethod.setAccessible(true);
    }

    public byte[] encodeTraffic(byte[] payload) throws Exception {
        if (!isTrafficOnly()) throw new IllegalStateException("traffic encode 未配置");
        if (handlerClass == null) init();
        Object instance = handlerClass.getDeclaredConstructor().newInstance();
        try {
            return (byte[]) trafficEncodeMethod.invoke(instance, payload);
        } catch (InvocationTargetException ite) {
            throw unwrap(ite);
        }
    }

    public byte[] decodeTraffic(byte[] body) throws Exception {
        if (!isTrafficOnly()) throw new IllegalStateException("traffic decode 未配置");
        if (handlerClass == null) init();
        Object instance = handlerClass.getDeclaredConstructor().newInstance();
        try {
            return (byte[]) trafficDecodeMethod.invoke(instance, body);
        } catch (InvocationTargetException ite) {
            throw unwrap(ite);
        }
    }

    public boolean isTrafficOnly() {
        return trafficEncodeBody != null && !trafficEncodeBody.isBlank()
                && trafficDecodeBody != null && !trafficDecodeBody.isBlank();
    }

    /**
     * 展开 {@link InvocationTargetException}，返回其真实 cause。
     * cause 为 Exception 直接抛；为 Error 包装为 Exception 抛（保留原 cause）；
     * 极少数情况下 cause 为 null（反射框架自身异常），回退抛出原 ITE。
     */
    private static Exception unwrap(InvocationTargetException ite) {
        Throwable cause = ite.getCause();
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        if (cause instanceof Error) {
            // Error 不应作为业务异常被吞，但为了让链路能拿到错误信息，包成 RuntimeException 上抛
            return new RuntimeException(cause.getClass().getName() + ": " + cause.getMessage(), cause);
        }
        return ite;
    }

    // ================== getter/setter ==================

    public String getDisguiseId() {
        return disguiseId;
    }

    public void setDisguiseId(String disguiseId) {
        this.disguiseId = disguiseId;
    }

    public String getDisguiseName() {
        return disguiseName;
    }

    public void setDisguiseName(String disguiseName) {
        this.disguiseName = disguiseName;
    }

    public String getTrafficEncodeBody() { return trafficEncodeBody; }

    public void setTrafficEncodeBody(String trafficEncodeBody) {
        this.trafficEncodeBody = trafficEncodeBody;
        resetRuntimeHandler();
    }

    public String getTrafficDecodeBody() { return trafficDecodeBody; }

    public void setTrafficDecodeBody(String trafficDecodeBody) {
        this.trafficDecodeBody = trafficDecodeBody;
        resetRuntimeHandler();
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(Integer protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getPhpTrafficEncodeBody() {
        return phpTrafficEncodeBody;
    }

    public void setPhpTrafficEncodeBody(String phpTrafficEncodeBody) {
        this.phpTrafficEncodeBody = phpTrafficEncodeBody;
    }

    public String getPhpTrafficDecodeBody() {
        return phpTrafficDecodeBody;
    }

    public void setPhpTrafficDecodeBody(String phpTrafficDecodeBody) {
        this.phpTrafficDecodeBody = phpTrafficDecodeBody;
    }

    public Set<String> getSupportedRuntimes() {
        return supportedRuntimes;
    }

    public void setSupportedRuntimes(Set<String> supportedRuntimes) {
        this.supportedRuntimes = supportedRuntimes;
    }

    public Map<String, Object> getRequirements() {
        return requirements == null ? new LinkedHashMap<>() : requirements;
    }

    public void setRequirements(Map<String, Object> requirements) {
        this.requirements = requirements;
    }

    public boolean supportsRuntime(String runtime) {
        if (runtime == null || runtime.isBlank()) return false;
        if (supportedRuntimes != null && !supportedRuntimes.isEmpty()
                && supportedRuntimes.stream().noneMatch(item -> runtime.equalsIgnoreCase(item))) {
            return false;
        }
        if ("php".equalsIgnoreCase(runtime)) {
            return phpTrafficEncodeBody != null && !phpTrafficEncodeBody.isBlank()
                    && phpTrafficDecodeBody != null && !phpTrafficDecodeBody.isBlank()
                    && isTrafficOnly();
        }
        return "java".equalsIgnoreCase(runtime)
                && isTrafficOnly();
    }

    private synchronized void resetRuntimeHandler() {
        handlerClass = null;
        trafficEncodeMethod = null;
        trafficDecodeMethod = null;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getCreateUserId() {
        return createUserId;
    }

    public void setCreateUserId(String createUserId) {
        this.createUserId = createUserId;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    @Override
    public String toString() {
        return JsonUtil.toJsonString(this);
    }
}
