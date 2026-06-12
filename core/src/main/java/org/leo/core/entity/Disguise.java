package org.leo.core.entity;

import org.leo.core.util.javassist.JavassistDisguiseFactory;
import org.leo.core.util.json.JsonUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Disguise：编码/解码策略
 */
public class Disguise {

    private String disguiseId;
    private String disguiseName;
    private String encodeBody;
    private String decodeBody;

    private Map<String, String> headers;

    private String version;
    private String createUserId;
    private String createTime;
    private String updateTime;
    private String description;
    private String remark;

    /** 运行时字段（不建议持久化） */
    private transient Class<?> handlerClass;
    private transient Method encodeMethod;
    private transient Method decodeMethod;

    // ================== 初始化 ==================

    public synchronized void init() throws Exception {
        if (handlerClass != null) {
            return;
        }

        handlerClass = JavassistDisguiseFactory.createDisguiseClass(
                getEncodeBody(), getDecodeBody()
        );

        encodeMethod = handlerClass.getMethod("encode", HashMap.class);
        decodeMethod = handlerClass.getMethod("decode", byte[].class);

        encodeMethod.setAccessible(true);
        decodeMethod.setAccessible(true);
    }

    // ================== encode ==================

    public byte[] encode(Map<String, Object> params) throws Exception {
        if (handlerClass == null) {
            init();
        }

        Object instance = handlerClass.getDeclaredConstructor().newInstance();

        Object result;
        try {
            result = encodeMethod.invoke(instance, new HashMap<>(params));
        } catch (InvocationTargetException ite) {
            // 用户自定义 encode 代码抛出的真实异常被 JDK 反射层包装在 ITE 里，getMessage()=null。
            // 展开为真实 cause 抛出，上层日志和错误处理才能看到真正的异常类型和消息。
            throw unwrap(ite);
        }

        return (byte[]) result;
    }

    // ================== decode ==================


    public Map<String, Object> decode(byte[] data) throws Exception {
        if (handlerClass == null) {
        init();
        }

        Object instance = handlerClass.getDeclaredConstructor().newInstance();

        Object result;
        try {
            result = decodeMethod.invoke(instance, data);
        } catch (InvocationTargetException ite) {
            // 同 encode：展开 InvocationTargetException，暴露 user-defined decode 抛出的真实异常
            throw unwrap(ite);
        }

        return (Map<String, Object>) result;
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

    public String getEncodeBody() {
        return encodeBody;
    }

    public void setEncodeBody(String encodeBody) {
        this.encodeBody = encodeBody;
    }

    public String getDecodeBody() {
        return decodeBody;
    }

    public void setDecodeBody(String decodeBody) {
        this.decodeBody = decodeBody;
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