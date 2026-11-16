package org.leo.core.util.request;

import java.util.regex.Pattern;

/**
 * Java Component 运行时类名画像配置。
 *
 * <p>该对象只负责把会话与逻辑组件名解析为合法的 ClassFile 二进制名，
 * 与 RPC、组件加载器及 JMG Core 保持独立。</p>
 */
public class ComponentClassNameStrategy {

    private static final Pattern BINARY_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private boolean enabled = true;
    private Mode mode = Mode.APPLICATION;

    public enum Mode {
        /** 使用应用类命名画像。 */
        APPLICATION,
        /** 生成 Outer$Inner 形态。 */
        INNER_CLASS,
        /** 生成 Outer$$Lambda$N 形态。 */
        LAMBDA_SHAPED,
        /** 生成 application.proxy.$ProxyN 形态。 */
        PROXY_SHAPED
    }

    public String resolve(String sessionKey, String componentName) {
        String baseline = ClassNameGenerator.generateComponentClassName(sessionKey, componentName);
        if (!enabled) {
            return baseline;
        }

        Mode selected = mode != null ? mode : Mode.APPLICATION;
        int index = selected == Mode.APPLICATION ? 0 : positiveIndex(sessionKey + "|" + componentName);
        return validate(switch (selected) {
            case APPLICATION -> baseline;
            case INNER_CLASS -> baseline + "$" + innerRole(index);
            case LAMBDA_SHAPED -> baseline + "$$Lambda$" + index;
            case PROXY_SHAPED -> baseline.substring(0, baseline.lastIndexOf('.')) + ".proxy.$Proxy" + index;
        });
    }

    /** 在保存配置时执行一次完整校验。 */
    public void validateConfiguration() {
        resolve("configuration-preview", "BasicInfoComponent");
    }

    private static String innerRole(int index) {
        String[] roles = {"Context", "Holder", "Adapter", "Listener", "Factory", "State"};
        return roles[index % roles.length];
    }

    private static int positiveIndex(String value) {
        long seed = ClassNameGenerator.stableSeed(value);
        return (int) Math.floorMod(seed, 4096L);
    }

    public static String validate(String className) {
        if (className == null || !BINARY_NAME.matcher(className).matches()) {
            throw new IllegalArgumentException("非法 Java 二进制类名: " + className);
        }
        if (className.startsWith("java.")) {
            throw new IllegalArgumentException("java.* 包由引导类加载器保留: " + className);
        }
        return className;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }
}
