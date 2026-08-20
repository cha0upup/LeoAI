package org.leo.core.puppet.impl;

import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.payload.PayloadCodec;
import org.leo.core.puppet.service.ComponentLoadRegistry;
import org.leo.core.puppet.service.ComponentService;
import org.leo.core.util.request.ComponentClassNameStrategy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Centralizes configuration broadcast and lifecycle operations for a Java puppet's services. */
final class JavaPuppetServiceRegistry {

    private final List<ComponentService> services = new ArrayList<ComponentService>();

    void replace(ComponentLoadRegistry loadRegistry, ComponentService... replacements) {
        services.clear();
        if (replacements == null) return;
        for (ComponentService service : replacements) {
            if (service == null) continue;
            service.setComponentLoadRegistry(loadRegistry);
            services.add(service);
        }
    }

    void setHostId(String hostId) {
        for (ComponentService service : services) service.setHostId(hostId);
    }

    void setRequestLayers(List<RequestLayer> requestLayers) {
        for (ComponentService service : services) service.setRequestLayers(requestLayers);
    }

    void setResponseLayers(List<ResponseLayer> responseLayers) {
        for (ComponentService service : services) service.setResponseLayers(responseLayers);
    }

    void setPayloadCodec(PayloadCodec payloadCodec) {
        for (ComponentService service : services) service.setPayloadCodec(payloadCodec);
    }

    void setUrlStrategy(UrlStrategy strategy) {
        if (strategy == null) return;
        for (ComponentService service : services) service.setUrlStrategy(strategy);
    }

    void setPaddingStrategy(PaddingStrategy strategy) {
        if (strategy == null) return;
        for (ComponentService service : services) service.setPaddingStrategy(strategy);
    }

    void setHeaderNoiseStrategy(HeaderNoiseStrategy strategy) {
        if (strategy == null) return;
        for (ComponentService service : services) service.setHeaderNoiseStrategy(strategy);
    }

    void setComponentClassNameStrategy(ComponentClassNameStrategy strategy) {
        if (strategy == null) return;
        for (ComponentService service : services) service.setComponentClassNameStrategy(strategy);
    }

    void setMaxReqCount(int maxReqCount) {
        for (ComponentService service : services) service.setMaxReqCount(maxReqCount);
    }

    void setHostIdMismatchRecovery(Function<String, Map<String, Object>> recovery) {
        for (ComponentService service : services) service.setHostIdMismatchRecovery(recovery);
    }

    void seedLoadedComponents(String hostId, Set<String> componentNames) {
        if (hostId == null || componentNames == null) return;
        for (ComponentService service : services) {
            service.seedLoadedComponents(hostId, componentNames);
        }
    }

    Set<String> loadedComponents(String hostId) {
        Set<String> merged = new HashSet<String>();
        if (hostId == null) return merged;
        for (ComponentService service : services) {
            merged.addAll(service.getLoadedComponentNames(hostId));
        }
        return merged;
    }

    void clear() {
        for (ComponentService service : services) service.clearLoadedComponentCache();
        services.clear();
    }

    int size() {
        return services.size();
    }
}
