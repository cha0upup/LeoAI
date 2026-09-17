package org.leo.core.puppet.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side registry for runtime version profiles and their verified operations.
 * Target-side payloads stay independent single-class artifacts.
 */
final class WebRuntimeProfileRegistry {

    private WebRuntimeProfileRegistry() {
    }

    static RuntimeProfile resolve(String familyValue, String versionValue) {
        String family = normalizeFamily(familyValue);
        String version = versionValue == null ? "unknown" : versionValue.trim();
        if (version.length() == 0) version = "unknown";
        int major = firstVersionNumber(version);
        String profileId;
        String servletApi = "unknown";
        String namespace = "UNKNOWN";
        String componentId;
        boolean servletMutation = false;
        boolean filterMutation = false;
        boolean listenerMutation = false;
        boolean valveMutation = false;

        if ("TOMCAT".equals(family) || "TOMEE".equals(family)) {
            profileId = tomcatProfile(major, version);
            componentId = "TomcatContainerManageComponent";
            servletApi = tomcatServletApi(major, version);
            namespace = major < 0 ? "UNKNOWN" : major >= 10 ? "JAKARTA" : "JAVAX";
            boolean supportedTomcatLine = major >= 6 && major <= 11;
            servletMutation = supportedTomcatLine;
            filterMutation = supportedTomcatLine;
            listenerMutation = supportedTomcatLine;
            valveMutation = supportedTomcatLine;
        } else if ("WEBLOGIC".equals(family)) {
            profileId = major == 14 ? "weblogic-14c"
                    : major == 12 ? "weblogic-12c"
                    : major == 10 ? "weblogic-10"
                    : "weblogic-unknown";
            componentId = "WeblogicContainerManageComponent";
            boolean supportedWeblogicLine = major == 10 || major == 12 || major == 14;
            namespace = supportedWeblogicLine ? "JAVAX" : "UNKNOWN";
            servletMutation = supportedWeblogicLine;
            filterMutation = supportedWeblogicLine;
            listenerMutation = supportedWeblogicLine;
        } else {
            profileId = genericProfile(family, major);
            componentId = "GenericServletContainerManageComponent";
            namespace = genericNamespace(family, major);
        }

        return new RuntimeProfile(family, version, profileId, servletApi, namespace, componentId,
                servletMutation, filterMutation, listenerMutation, valveMutation);
    }

    static String normalizeFamily(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
        if (normalized.contains("tomee")) return "TOMEE";
        if (normalized.contains("tomcat")) return "TOMCAT";
        if (normalized.contains("weblogic")) return "WEBLOGIC";
        if (normalized.contains("liberty")) return "LIBERTY";
        if (normalized.contains("websphere")) return "WEBSPHERE_TRADITIONAL";
        if (normalized.contains("wildfly") || normalized.contains("jboss")) return "WILDFLY";
        if (normalized.contains("undertow")) return "UNDERTOW";
        if (normalized.contains("jetty")) return "JETTY";
        if (normalized.contains("glassfish")) return "GLASSFISH";
        if (normalized.contains("payara")) return "PAYARA";
        if (normalized.contains("resin")) return "RESIN";
        if (normalized.contains("apusic")) return "APUSIC";
        if (normalized.contains("tongweb")) return "TONGWEB";
        if (normalized.equals("bes") || normalized.contains("宝兰德")) return "BES";
        throw new IllegalArgumentException("不支持的中间件类型: " + value);
    }

    private static String tomcatProfile(int major, String version) {
        if (major < 0) return "tomcat-unknown";
        if (major == 8) {
            if (version.startsWith("8.5") || version.contains("/8.5")) return "tomcat-8.5";
            if (version.startsWith("8.0") || version.contains("/8.0")) return "tomcat-8.0";
            return "tomcat-8.x";
        }
        if (major == 10) {
            if (version.startsWith("10.1") || version.contains("/10.1")) return "tomcat-10.1";
            if (version.startsWith("10.0") || version.contains("/10.0")) return "tomcat-10.0";
        }
        return "tomcat-" + major;
    }

    private static String tomcatServletApi(int major, String version) {
        if (major == 6) return "2.5";
        if (major == 7) return "3.0";
        if (major == 8) return "3.1";
        if (major == 9) return "4.0";
        if (major == 10) return version.startsWith("10.1") || version.contains("/10.1") ? "6.0" : "5.0";
        if (major == 11) return "6.1";
        return "unknown";
    }

    private static String genericProfile(String family, int major) {
        String suffix = major < 0 ? "unknown" : String.valueOf(major);
        if ("WILDFLY".equals(family)) return "wildfly-" + suffix;
        if ("WEBSPHERE_TRADITIONAL".equals(family)) return "websphere-traditional";
        return family.toLowerCase(Locale.ENGLISH).replace('_', '-') + "-" + suffix;
    }

    private static String genericNamespace(String family, int major) {
        if ("JETTY".equals(family)) {
            if (major == 12) return "MULTI";
            return major >= 11 ? "JAKARTA" : "JAVAX";
        }
        if ("WILDFLY".equals(family)) return major >= 27 ? "JAKARTA" : "JAVAX";
        if ("LIBERTY".equals(family)) return "FEATURE_DEPENDENT";
        return "UNKNOWN";
    }

    static int firstVersionNumber(String value) {
        if (value == null) return -1;
        int start = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                start = i;
                break;
            }
        }
        if (start < 0) return -1;
        int end = start;
        while (end < value.length()) {
            char c = value.charAt(end);
            if (c < '0' || c > '9') break;
            end++;
        }
        try {
            return Integer.parseInt(value.substring(start, end));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static String strategyId(RuntimeProfile profile, Map<?, ?> features) {
        if (!"TOMCAT".equals(profile.family) && !"TOMEE".equals(profile.family)) {
            return profile.componentId.equals("GenericServletContainerManageComponent")
                    ? "standard-servlet-read-only" : profile.profileId;
        }
        Object storageValue = features == null ? null : features.get("listenerStorage");
        String storage = storageValue == null ? "" : String.valueOf(storageValue);
        if (storage.endsWith("List")) return profile.profileId + ":list-field";
        if (storage.endsWith("Objects")) return profile.profileId + ":objects-field";
        if (storage.length() > 0) return profile.profileId + ":array-field";
        return profile.profileId + ":feature-probe";
    }

    static final class RuntimeProfile {
        final String family;
        final String version;
        final String profileId;
        final String servletApiVersion;
        final String namespace;
        final String componentId;
        final boolean servletMutation;
        final boolean filterMutation;
        final boolean listenerMutation;
        final boolean valveMutation;

        RuntimeProfile(String family, String version, String profileId, String servletApiVersion,
                       String namespace, String componentId, boolean servletMutation,
                       boolean filterMutation, boolean listenerMutation, boolean valveMutation) {
            this.family = family;
            this.version = version;
            this.profileId = profileId;
            this.servletApiVersion = servletApiVersion;
            this.namespace = namespace;
            this.componentId = componentId;
            this.servletMutation = servletMutation;
            this.filterMutation = filterMutation;
            this.listenerMutation = listenerMutation;
            this.valveMutation = valveMutation;
        }

        boolean supportsMutation(String type) {
            if ("servlet".equals(type)) return servletMutation;
            if ("filter".equals(type)) return filterMutation;
            if ("listener".equals(type)) return listenerMutation;
            if ("valve".equals(type)) return valveMutation;
            return "controller".equals(type) || "interceptor".equals(type);
        }

        Map<String, Object> capabilities(boolean frameworkInspectable, boolean controllerManageable,
                                         boolean interceptorManageable) {
            LinkedHashMap<String, Object> answer = new LinkedHashMap<>();
            answer.put("servlet", capability(true, servletMutation));
            answer.put("filter", capability(true, filterMutation));
            answer.put("listener", capability(true, listenerMutation));
            answer.put("valve", capability("TOMCAT".equals(family) || "TOMEE".equals(family), valveMutation));
            answer.put("controller", capability(frameworkInspectable, controllerManageable));
            answer.put("interceptor", capability(frameworkInspectable, interceptorManageable));
            return answer;
        }

        private Map<String, Object> capability(boolean inspect, boolean mutate) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("detect", Boolean.TRUE);
            value.put("inspect", inspect);
            value.put("remove", mutate);
            if (!mutate) value.put("reason", inspect ? "PROFILE_READ_ONLY" : "NOT_AVAILABLE");
            return value;
        }
    }
}
