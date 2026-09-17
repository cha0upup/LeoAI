package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Version-aware facade around independent target-side web runtime payloads.
 */
public class WebRuntimeManageService extends ComponentService {

    public WebRuntimeManageService(Communication communication, List<RequestLayer> requestLayers,
                                   List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> inspect(String runtimeFamily, String runtimeVersion,
                                       String webFramework) throws Exception {
        WebRuntimeProfileRegistry.RuntimeProfile profile =
                WebRuntimeProfileRegistry.resolve(runtimeFamily, runtimeVersion);
        Map<String, Object> raw = inspectContainer(profile);
        List<Map<String, Object>> contexts = normalizeContexts(raw.get("contexts"), profile);
        ArrayList<String> diagnostics = new ArrayList<>();
        Map<String, Object> framework = null;
        try {
            framework = inspectFramework(webFramework);
        } catch (Exception e) {
            diagnostics.add("框架信息获取失败：" + e.getMessage());
        }
        boolean controllerManageable = framework != null && contexts.size() == 1
                && frameworkSupportsMutation(webFramework, "controller");
        boolean interceptorManageable = framework != null && contexts.size() == 1
                && frameworkSupportsMutation(webFramework, "interceptor");
        Map<String, Object> capabilities = profile.capabilities(framework != null,
                controllerManageable, interceptorManageable);

        List<Map<String, Object>> runtimeFrameworks = new ArrayList<>();
        if (framework != null) {
            Map<String, Object> normalizedFramework = normalizeFramework(framework, webFramework, contexts);
            normalizedFramework.put("capabilities", capabilities);
            runtimeFrameworks.add(normalizedFramework);
            if (contexts.size() == 1) {
                contexts.get(0).put("frameworks", Collections.singletonList(normalizedFramework));
            }
        }

        String runtimeId = stableId("runtime", profile.family, profile.version, profile.profileId);
        LinkedHashMap<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("runtimeId", runtimeId);
        runtime.put("family", profile.family);
        runtime.put("productVersion", profile.version);
        runtime.put("profileId", profile.profileId);
        runtime.put("servletApiVersion", profile.servletApiVersion);
        runtime.put("namespace", profile.namespace);
        runtime.put("adapter", profile.componentId);
        Map<?, ?> features = raw != null && raw.get("features") instanceof Map
                ? (Map<?, ?>) raw.get("features") : Collections.emptyMap();
        runtime.put("features", features);
        runtime.put("strategyId", WebRuntimeProfileRegistry.strategyId(profile, features));
        runtime.put("capabilities", capabilities);
        runtime.put("contexts", contexts);
        runtime.put("frameworks", runtimeFrameworks);

        if ("unknown".equalsIgnoreCase(profile.version)) diagnostics.add("RUNTIME_VERSION_UNKNOWN");
        if (contexts.size() != 1 && framework != null) diagnostics.add("FRAMEWORK_CONTEXT_UNRESOLVED");
        if (raw != null && raw.get("msg") != null) diagnostics.add(String.valueOf(raw.get("msg")));

        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", Integer.valueOf(2));
        snapshot.put("scanId", UUID.randomUUID().toString());
        snapshot.put("runtimes", Collections.singletonList(runtime));
        snapshot.put("diagnostics", diagnostics);
        return snapshot;
    }

    public Map<String, Object> remove(String runtimeFamily, String runtimeVersion, String webFramework,
                                      String componentType, String contextId, String identifier) throws Exception {
        String type = normalizeComponentType(componentType);
        if (contextId == null || contextId.isBlank()) throw new IllegalArgumentException("contextId 不能为空，请刷新容器信息");
        if (identifier == null || identifier.isBlank()) throw new IllegalArgumentException("identifier 不能为空");
        WebRuntimeProfileRegistry.RuntimeProfile profile =
                WebRuntimeProfileRegistry.resolve(runtimeFamily, runtimeVersion);
        if (!profile.supportsMutation(type)) {
            return operation("UNSUPPORTED", 0, 0, false, "PROFILE_READ_ONLY");
        }

        Map<String, Object> raw;
        if ("controller".equals(type) || "interceptor".equals(type)) {
            if (!frameworkSupportsMutation(webFramework, type)) {
                return operation("UNSUPPORTED", 0, 0, false, "FRAMEWORK_READ_ONLY");
            }
            String component = resolveFrameworkComponentName(webFramework);
            if (component == null) return operation("UNSUPPORTED", 0, 0, false, "FRAMEWORK_READ_ONLY");
            List<Map<String, Object>> contexts = normalizeContexts(inspectContainer(profile).get("contexts"), profile);
            if (contexts.size() != 1 || !contextId.equals(contexts.get(0).get("contextId"))) {
                return operation("UNSUPPORTED", 0, 0, false, "FRAMEWORK_CONTEXT_UNRESOLVED");
            }
            HashMap<String, Object> request = params("controller".equals(type)
                    ? "removeController" : "removeInterceptor");
            request.put("frameworkName", webFramework);
            request.put("controller".equals(type) ? "mappingInfo" : "interceptorId", identifier);
            raw = invokeComponent(component, request);
        } else {
            HashMap<String, Object> request = params(methodFor(type));
            request.put("contextId", contextId);
            request.put(identifierField(type), identifier);
            raw = invokeComponent(profile.componentId, request);
        }
        return normalizeOperation(raw);
    }

    static String resolveContainerComponentName(String family, String version) {
        return WebRuntimeProfileRegistry.resolve(family, version).componentId;
    }

    static String resolveProfileId(String family, String version) {
        return WebRuntimeProfileRegistry.resolve(family, version).profileId;
    }

    static String resolveFrameworkComponentName(String webFramework) {
        String normalized = webFramework == null ? "" : webFramework.trim().toLowerCase(Locale.ENGLISH);
        if (normalized.contains("spring") || normalized.contains("webflux")) {
            return "SpringFrameworkManageComponent";
        }
        if (normalized.contains("struts") || normalized.contains("jsf")
                || normalized.contains("faces") || normalized.contains("jax-rs")
                || normalized.contains("jersey") || normalized.contains("resteasy")
                || normalized.contains("wicket") || normalized.contains("play")
                || normalized.contains("micronaut") || normalized.contains("quarkus")
                || normalized.equals("servlet")) {
            return "JavaWebFrameworkManageComponent";
        }
        return null;
    }

    private static boolean frameworkSupportsMutation(String webFramework, String componentType) {
        String normalized = webFramework == null ? "" : webFramework.trim().toLowerCase(Locale.ENGLISH);
        if (normalized.contains("webflux")) return false;
        if (normalized.contains("spring") || normalized.contains("struts")) return true;
        return "interceptor".equals(componentType)
                && (normalized.contains("jsf") || normalized.contains("faces"));
    }

    private Map<String, Object> inspectFramework(String webFramework) throws Exception {
        String component = resolveFrameworkComponentName(webFramework);
        if (component == null) return null;
        HashMap<String, Object> request = params("getFrameworkInfo");
        request.put("frameworkName", webFramework);
        Map<String, Object> response = invokeComponent(component, request);
        requireSuccessfulInspection(response);
        Object info = response.get("frameworkInfo");
        if (!(info instanceof Map)) throw new IllegalStateException("节点未返回框架信息");
        return (Map<String, Object>) info;
    }

    private Map<String, Object> inspectContainer(WebRuntimeProfileRegistry.RuntimeProfile profile) throws Exception {
        Map<String, Object> response = invokeComponent(profile.componentId, params("inspectRuntime"));
        requireSuccessfulInspection(response);
        if (!(response.get("contexts") instanceof List)) throw new IllegalStateException("节点未返回 Context 列表");
        return response;
    }

    private static void requireSuccessfulInspection(Map<String, Object> response) {
        if (response == null || number(response.get("code"), 0) != 200) {
            throw new IllegalStateException(response == null ? "节点返回为空"
                    : text(response.get("msg"), "节点检查失败"));
        }
    }

    private List<Map<String, Object>> normalizeContexts(Object rawContexts,
                                                         WebRuntimeProfileRegistry.RuntimeProfile profile) {
        if (!(rawContexts instanceof List)) return new ArrayList<>();
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (Object item : (List<?>) rawContexts) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> raw = (Map<?, ?>) item;
            String name = text(raw.get("name"), "ROOT");
            String path = text(raw.get("basePath"), "/");
            String host = text(raw.get("host"), "default");
            String contextId = text(raw.get("contextId"), stableId("context", profile.profileId, host, path, name));
            LinkedHashMap<String, Object> context = new LinkedHashMap<>();
            context.put("contextId", contextId);
            context.put("name", name);
            context.put("path", path);
            context.put("host", host);
            context.put("workDir", text(raw.get("workDir"), ""));
            context.put("state", text(raw.get("state"), "UNKNOWN"));
            LinkedHashMap<String, Object> components = new LinkedHashMap<>();
            components.put("servlet", normalizeAssets(raw.get("allServlet"), "servlet", contextId));
            components.put("filter", normalizeAssets(raw.get("allFilter"), "filter", contextId));
            components.put("listener", normalizeAssets(raw.get("allListener"), "listener", contextId));
            components.put("valve", normalizeAssets(raw.get("allValve"), "valve", contextId));
            context.put("components", components);
            context.put("frameworks", new ArrayList<>());
            contexts.add(context);
        }
        return contexts;
    }

    private List<Map<String, Object>> normalizeAssets(Object value, String type, String contextId) {
        if (!(value instanceof List)) return new ArrayList<>();
        List<Map<String, Object>> answer = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> raw = (Map<?, ?>) item;
            LinkedHashMap<String, Object> asset = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) asset.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            String name = assetName(type, raw);
            String className = assetClassName(type, raw);
            String mapping = assetMapping(type, raw);
            asset.put("componentId", stableId(type, contextId, name, className, mapping));
            asset.put("type", type);
            asset.put("name", name);
            asset.put("className", className);
            answer.add(asset);
        }
        return answer;
    }

    private Map<String, Object> normalizeFramework(Map<String, Object> raw, String name,
                                                    List<Map<String, Object>> contexts) {
        LinkedHashMap<String, Object> framework = new LinkedHashMap<>();
        framework.putAll(raw);
        framework.remove("webFramework");
        framework.put("frameworkId", stableId("framework", name));
        framework.put("family", name == null ? "UNKNOWN" : name);
        framework.put("profileId", frameworkProfile(name));
        framework.put("contextId", contexts.size() == 1 ? contexts.get(0).get("contextId") : null);
        return framework;
    }

    private String frameworkProfile(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ENGLISH);
        if (normalized.contains("webflux")) return normalized.contains("spring") ? "spring-webflux" : "webflux";
        if (normalized.contains("spring")) return "spring-mvc";
        if (normalized.contains("struts")) return "struts-2";
        if (normalized.contains("faces") || normalized.contains("jsf")) {
            return normalized.contains("jakarta") ? "faces-jakarta" : "faces-javax";
        }
        return "framework-unknown";
    }

    private Map<String, Object> normalizeOperation(Map<String, Object> raw) {
        if (raw == null) return operation("FAILED", 0, 0, false, "EMPTY_COMPONENT_RESPONSE");
        Object status = raw.get("status");
        if (status == null) {
            return operation("FAILED", 0, 0, false, text(raw.get("msg"), "INVALID_COMPONENT_RESPONSE"));
        }
        String operationStatus = String.valueOf(status);
        boolean changed = "CHANGED".equals(operationStatus);
        return operation(operationStatus,
                number(raw.get("matched"), 0),
                number(raw.get("changed"), 0),
                Boolean.TRUE.equals(raw.get("verified")),
                changed ? null : text(raw.get("msg"), null));
    }

    private Map<String, Object> operation(String status, int matched, int changed,
                                          boolean verified, String diagnostic) {
        LinkedHashMap<String, Object> answer = new LinkedHashMap<>();
        answer.put("status", status);
        answer.put("matched", Integer.valueOf(matched));
        answer.put("changed", Integer.valueOf(changed));
        answer.put("verified", Boolean.valueOf(verified));
        answer.put("diagnostics", diagnostic == null
                ? new ArrayList<>() : Collections.singletonList(diagnostic));
        return answer;
    }

    private HashMap<String, Object> params(String method) {
        HashMap<String, Object> value = new HashMap<>();
        value.put("methodName", method);
        return value;
    }

    private String methodFor(String type) {
        if ("filter".equals(type)) return "removeFilter";
        if ("servlet".equals(type)) return "removeServlet";
        if ("listener".equals(type)) return "removeListener";
        if ("valve".equals(type)) return "removeValve";
        throw new IllegalArgumentException("不支持的组件类型: " + type);
    }

    private String identifierField(String type) {
        if ("filter".equals(type)) return "filterName";
        if ("servlet".equals(type)) return "servletPattern";
        if ("listener".equals(type)) return "listenerId";
        if ("valve".equals(type)) return "valveId";
        throw new IllegalArgumentException("不支持的组件类型: " + type);
    }

    private String normalizeComponentType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
        if ("filter".equals(type) || "servlet".equals(type) || "listener".equals(type)
                || "valve".equals(type) || "controller".equals(type)
                || "interceptor".equals(type)) return type;
        throw new IllegalArgumentException("不支持的组件类型: " + value);
    }

    private String assetName(String type, Map<?, ?> raw) {
        if ("filter".equals(type)) return text(raw.get("filterName"), "");
        if ("servlet".equals(type)) return text(raw.get("wrapperName"), "");
        if ("listener".equals(type)) return text(raw.get("className"), "");
        if ("valve".equals(type)) return text(raw.get("valveClassName"), "");
        return "";
    }

    private String assetClassName(String type, Map<?, ?> raw) {
        if ("filter".equals(type)) return text(raw.get("filterClassName"), "");
        if ("servlet".equals(type)) return text(raw.get("servletClass"), "");
        if ("listener".equals(type)) return text(raw.get("className"), "");
        if ("valve".equals(type)) return text(raw.get("valveClassName"), "");
        return "";
    }

    private String assetMapping(String type, Map<?, ?> raw) {
        if ("servlet".equals(type)) return text(raw.get("url"), "");
        if ("filter".equals(type)) return String.valueOf(raw.get("urlPatterns"));
        return "";
    }

    private static String stableId(String... parts) {
        StringBuilder seed = new StringBuilder();
        for (String part : parts) seed.append(part == null ? "" : part).append('\u0000');
        return UUID.nameUUIDFromBytes(seed.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String text(Object value, String fallback) {
        if (value == null) return fallback;
        String string = String.valueOf(value);
        return string.isEmpty() ? fallback : string;
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
}
