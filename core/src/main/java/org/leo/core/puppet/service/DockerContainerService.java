package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Docker 容器管理服务
 * <p>
 * 内联枚举和管理 Docker/Podman 容器、镜像、网络。
 * 自动检测 docker 或 podman 运行时。
 */
public class DockerContainerService extends ComponentService {

    public DockerContainerService(Communication communication,
                                  List<RequestLayer> requestLayers,
                                  List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ==================== public API ====================

    public Map<String, Object> listContainers(boolean all) throws Exception {
        String runtime = detectRuntime();
        if (runtime == null) return noDockerResult();

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("runtime", runtime);
        doListContainers(data, runtime, all);
        return ok(data);
    }

    public Map<String, Object> listImages() throws Exception {
        String runtime = detectRuntime();
        if (runtime == null) return noDockerResult();

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("runtime", runtime);
        doListImages(data, runtime);
        return ok(data);
    }

    public Map<String, Object> inspectContainer(String containerId) throws Exception {
        String runtime = detectRuntime();
        if (runtime == null) return noDockerResult();
        if (containerId == null || containerId.trim().isEmpty()) return badRequest("containerId is required");

        String safeId  = sanitizeId(containerId);
        String output  = execFast(runtime + " inspect " + safeId + " 2>/dev/null");
        if (output == null || output.trim().isEmpty()) {
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("code", 404);
            result.put("msg",  "Container not found: " + safeId);
            return result;
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("runtime",  runtime);
        data.put("inspect",  truncate(output, 32768));
        return ok(data);
    }

    public Map<String, Object> containerLogs(String containerId, int tail) throws Exception {
        String runtime = detectRuntime();
        if (runtime == null) return noDockerResult();
        if (containerId == null || containerId.trim().isEmpty()) return badRequest("containerId is required");

        String safeId = sanitizeId(containerId);
        int    lines  = tail > 0 ? tail : 100;
        String output = execFast(runtime + " logs --tail " + lines + " " + safeId + " 2>&1");

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("runtime",     runtime);
        data.put("containerId", safeId);
        data.put("tail",        lines);
        data.put("logs",        truncate(output, 32768));
        return ok(data);
    }

    public Map<String, Object> listNetworks() throws Exception {
        String runtime = detectRuntime();
        if (runtime == null) return noDockerResult();

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("runtime", runtime);
        doListNetworks(data, runtime);
        return ok(data);
    }

    public Map<String, Object> dockerInfo() throws Exception {
        String runtime = detectRuntime();
        if (runtime == null) return noDockerResult();

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("runtime", runtime);
        String info = execFast(runtime + " info 2>/dev/null");
        data.put("info", truncate(info, 16384));
        String df = execFast(runtime + " system df 2>/dev/null");
        if (df != null && !df.trim().isEmpty()) data.put("diskUsage", truncate(df, 4096));
        return ok(data);
    }

    public Map<String, Object> execInContainer(String containerId, String cmd) throws Exception {
        String runtime = detectRuntime();
        if (runtime == null) return noDockerResult();
        if (containerId == null || containerId.trim().isEmpty()) return badRequest("containerId is required");
        if (cmd == null || cmd.trim().isEmpty()) return badRequest("cmd is required");

        String safeId  = sanitizeId(containerId);
        String safeCmd = cmd.replace("'", "'\\''");
        String output  = execFast(runtime + " exec " + safeId + " /bin/sh -c '" + safeCmd + "' 2>&1");

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("runtime",     runtime);
        data.put("containerId", safeId);
        data.put("cmd",         cmd);
        data.put("output",      truncate(output, 32768));
        return ok(data);
    }

    public Map<String, Object> startContainer(String containerId) throws Exception {
        return simpleContainerOp("start", containerId, -1, false);
    }

    public Map<String, Object> stopContainer(String containerId, int timeout) throws Exception {
        return simpleContainerOp("stop", containerId, timeout, false);
    }

    public Map<String, Object> restartContainer(String containerId, int timeout) throws Exception {
        return simpleContainerOp("restart", containerId, timeout, false);
    }

    public Map<String, Object> pauseContainer(String containerId) throws Exception {
        return simpleContainerOp("pause", containerId, -1, false);
    }

    public Map<String, Object> unpauseContainer(String containerId) throws Exception {
        return simpleContainerOp("unpause", containerId, -1, false);
    }

    public Map<String, Object> removeContainer(String containerId, boolean force) throws Exception {
        return simpleContainerOp("rm", containerId, -1, force);
    }

    public Map<String, Object> removeImage(String imageId, boolean force) throws Exception {
        String runtime = detectRuntime();
        if (runtime == null) return noDockerResult();
        if (imageId == null || imageId.trim().isEmpty()) return badRequest("imageId is required");

        String safeId = sanitizeId(imageId);
        String cmd    = runtime + " rmi " + (force ? "-f " : "") + safeId + " 2>&1";
        String output = execFast(cmd);

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("runtime", runtime);
        data.put("imageId", safeId);
        data.put("force",   force);
        data.put("output",  output != null ? output.trim() : "");
        data.put("success", output != null && !output.toLowerCase().contains("error"));
        return ok(data);
    }

    // ==================== internal operations ====================

    private void doListContainers(Map<String, Object> data, String runtime, boolean all) throws Exception {
        String flag   = all ? "-a " : "";
        String format = "'{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.Status}}\\t{{.Ports}}\\t{{.CreatedAt}}'";
        String output = execFast(runtime + " ps " + flag + "--format " + format + " 2>/dev/null");

        List<Map<String, Object>> containers = new ArrayList<Map<String, Object>>();
        if (output != null && !output.trim().isEmpty()) {
            String[] lines = output.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t");
                Map<String, Object> c = new HashMap<String, Object>();
                if (parts.length >= 1) c.put("id",      parts[0]);
                if (parts.length >= 2) c.put("name",    parts[1]);
                if (parts.length >= 3) c.put("image",   parts[2]);
                if (parts.length >= 4) c.put("status",  parts[3]);
                if (parts.length >= 5) c.put("ports",   parts[4]);
                if (parts.length >= 6) c.put("created", parts[5]);
                containers.add(c);
            }
        }
        data.put("containers", containers);
        data.put("total",      containers.size());
    }

    private void doListImages(Map<String, Object> data, String runtime) throws Exception {
        String format = "'{{.Repository}}\\t{{.Tag}}\\t{{.ID}}\\t{{.Size}}\\t{{.CreatedAt}}'";
        String output = execFast(runtime + " images --format " + format + " 2>/dev/null | head -200");

        List<Map<String, Object>> images = new ArrayList<Map<String, Object>>();
        if (output != null && !output.trim().isEmpty()) {
            String[] lines = output.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t");
                Map<String, Object> img = new HashMap<String, Object>();
                if (parts.length >= 1) img.put("repository", parts[0]);
                if (parts.length >= 2) img.put("tag",        parts[1]);
                if (parts.length >= 3) img.put("id",         parts[2]);
                if (parts.length >= 4) img.put("size",       parts[3]);
                if (parts.length >= 5) img.put("created",    parts[4]);
                images.add(img);
            }
        }
        data.put("images", images);
        data.put("total",  images.size());
    }

    private void doListNetworks(Map<String, Object> data, String runtime) throws Exception {
        String format = "'{{.ID}}\\t{{.Name}}\\t{{.Driver}}\\t{{.Scope}}'";
        String output = execFast(runtime + " network ls --format " + format + " 2>/dev/null");

        List<Map<String, Object>> networks = new ArrayList<Map<String, Object>>();
        if (output != null && !output.trim().isEmpty()) {
            String[] lines = output.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t");
                Map<String, Object> net = new HashMap<String, Object>();
                if (parts.length >= 1) net.put("id",     parts[0]);
                if (parts.length >= 2) net.put("name",   parts[1]);
                if (parts.length >= 3) net.put("driver", parts[2]);
                if (parts.length >= 4) net.put("scope",  parts[3]);
                networks.add(net);
            }
        }
        data.put("networks", networks);
    }

    /**
     * Generic single-container operation: start / stop / restart / pause / unpause / rm.
     * timeout >= 0 only applies for stop/restart.
     * force only applies for rm.
     */
    private Map<String, Object> simpleContainerOp(String op, String containerId,
                                                    int timeout, boolean force) throws Exception {
        String runtime = detectRuntime();
        if (runtime == null) return noDockerResult();
        if (containerId == null || containerId.trim().isEmpty()) return badRequest("containerId is required");

        String safeId = sanitizeId(containerId);
        String cmd;
        if ("stop".equals(op) || "restart".equals(op)) {
            int t = timeout > 0 ? timeout : 10;
            cmd = runtime + " " + op + " -t " + t + " " + safeId + " 2>&1";
        } else if ("rm".equals(op)) {
            cmd = runtime + " rm " + (force ? "-f " : "") + safeId + " 2>&1";
        } else {
            cmd = runtime + " " + op + " " + safeId + " 2>&1";
        }
        String output = execFast(cmd);

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("runtime",     runtime);
        data.put("containerId", safeId);
        data.put("op",          op);
        if (force) data.put("force", true);
        data.put("output",  output != null ? output.trim() : "");
        data.put("success", output != null && !output.toLowerCase().contains("error"));
        return ok(data);
    }

    // ==================== runtime detection ====================

    private String detectRuntime() throws Exception {
        String out = execFast("docker --version 2>/dev/null");
        if (out != null && !out.trim().isEmpty()) return "docker";
        out = execFast("podman --version 2>/dev/null");
        if (out != null && !out.trim().isEmpty()) return "podman";
        return null;
    }

    // ==================== helpers ====================

    private String sanitizeId(String id) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/' || c == ':') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    private Map<String, Object> noDockerResult() {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 404);
        result.put("msg",  "Docker/Podman not found on this host");
        return result;
    }

    private Map<String, Object> badRequest(String msg) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 400);
        result.put("msg",  msg);
        return result;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n... (truncated, total " + s.length() + " chars)";
    }
}
