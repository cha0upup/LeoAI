package org.leo.jmg.mem;

import org.leo.jmg.ServerInjectorMapper;

import java.util.Locale;

/**
 * 应用服务器 / 运行环境类型（与 {@link ServerInjectorMapper} 注册表中的 server 段一致）
 */
public enum ServerType {

    TOMCAT("Tomcat"),
    JETTY("Jetty"),
    JBOSS_AS("JBossAS"),
    JBOSS_EAP6("JBossEAP6"),
    UNDERTOW("Undertow"),
    JBOSS_EAP7("JBossEAP7"),
    WILDFLY("Wildfly"),
    RESIN("Resin"),
    GLASSFISH("Glassfish"),
    PAYARA("Payara"),
    WEBLOGIC("WebLogic"),
    WEBSPHERE("WebSphere"),
    SPRING_WEBMVC("SpringWebMVC"),
    APUSIC("Apusic"),
    BES("BES"),
    INFORSUITE("InforSuite"),
    TONGWEB("TongWeb"),
    STRUCT2("Struct2");

    private final String value;

    ServerType(String value) {
        this.value = value;
    }

    /** 与 Mapper、API 中使用的字符串一致 */
    public String getValue() {
        return value;
    }

    /**
     * 解析 API / 用户输入；大小写不敏感；TongWeb6/7/8 等别名映射到 {@link #TONGWEB}
     */
    public static ServerType fromString(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String t = s.trim();
        for (ServerType e : values()) {
            if (e.value.equalsIgnoreCase(t)) {
                return e;
            }
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("tongweb")) {
            return TONGWEB;
        }
        return null;
    }
}
