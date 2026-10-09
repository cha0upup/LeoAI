package org.leo.web.controller.agentnode.proxy;

import org.leo.core.engine.proxy.ProxyBindAddress;
import org.leo.core.engine.proxy.ProxyStatisticsQuery;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.util.ApiResponse;
import org.leo.web.exception.ApiException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared request validation and response mapping for network proxy endpoints. */
final class ProxyControllerSupport {

    private ProxyControllerSupport() {
    }

    static HashMap<String, Object> call(String failureMessage,
                                        Object nullFallback,
                                        ProxyAction action) {
        try {
            Object result = action.execute();
            if (result instanceof Map<?, ?> response && response.get("code") instanceof Number code
                    && code.intValue() >= 400) {
                Object message = response.get("msg");
                return ApiResponse.error(code.intValue(), message == null ? failureMessage : String.valueOf(message));
            }
            return ApiResponse.success(result == null ? nullFallback : result);
        } catch (ApiException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        } catch (Exception error) {
            return ApiResponse.error(failureMessage + ": " + error.getMessage());
        }
    }

    static HashMap<String, Object> statistics(String failureMessage,
                                              String unavailableMessage,
                                              StatisticsAction action) {
        return call(failureMessage, null, () -> {
            Socks5ProxyStatistics.StatisticsSnapshot snapshot = action.execute();
            return snapshot == null
                    ? ApiResponse.error(unavailableMessage)
                    : toView(snapshot);
        });
    }

    static ProxyStatisticsQuery statisticsQuery(Map<String, Object> params) {
        if (params == null || (!params.containsKey("includeConnections")
                && !params.containsKey("page") && !params.containsKey("pageSize"))) return ProxyStatisticsQuery.ALL;
        Object include = params.getOrDefault("includeConnections", Boolean.TRUE);
        if (!(include instanceof Boolean)) throw new IllegalArgumentException("includeConnections必须为布尔值");
        return new ProxyStatisticsQuery((Boolean) include,
                positiveInteger(params, "page", 1, Integer.MAX_VALUE),
                positiveInteger(params, "pageSize", 50, 200));
    }

    private static int positiveInteger(Map<String, Object> params, String name, int fallback, int maximum) {
        Object value = params.getOrDefault(name, fallback);
        try {
            int number = Integer.parseInt(String.valueOf(value));
            if (number >= 1 && number <= maximum) return number;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + "必须在1到" + maximum + "之间");
    }

    static String optionalBindAddress(Map<String, Object> params) {
        Object value = params == null ? null : params.get("bindAddr");
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("bindAddr必须是字符串类型");
        }
        return ProxyBindAddress.normalize((String) value);
    }

    static int requirePort(Map<String, Object> params, String name) {
        if (params == null) {
            throw new IllegalArgumentException("params不能为空");
        }
        Object value = params.get(name);
        int port;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            port = ((Number) value).intValue();
        } else if (value instanceof Long number && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
            port = number.intValue();
        } else if (value instanceof String text) {
            try {
                port = Integer.parseInt(text.trim());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(name + "必须是数字类型");
            }
        } else {
            throw new IllegalArgumentException(name + "必须是数字类型");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(name + "必须在1到65535之间");
        }
        return port;
    }

    static String requireText(Map<String, Object> params, String name) {
        if (params == null) {
            throw new IllegalArgumentException("params不能为空");
        }
        Object value = params.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + "为必填项");
        }
        return text.trim();
    }

    private static Map<String, Object> toView(Socks5ProxyStatistics.StatisticsSnapshot snapshot) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("port", snapshot.port);
        data.put("activeConnections", snapshot.activeConnections);
        data.put("totalConnections", snapshot.totalConnections);
        data.put("uploadBytes", snapshot.uploadBytes);
        data.put("downloadBytes", snapshot.downloadBytes);
        data.put("uploadRate", snapshot.uploadRate);
        data.put("downloadRate", snapshot.downloadRate);
        data.put("startTime", snapshot.startTime);
        data.put("uptime", snapshot.uptime);

        if (snapshot.connectionsIncluded) {
            List<Map<String, Object>> connections = new ArrayList<>(
                    snapshot.connections == null ? 0 : snapshot.connections.size());
            if (snapshot.connections != null) {
                for (Socks5ProxyStatistics.ConnectionInfo connection : snapshot.connections) {
                    if (connection == null) {
                        continue;
                    }
                    Map<String, Object> view = new LinkedHashMap<String, Object>();
                    view.put("connId", connection.connId);
                    view.put("targetHost", connection.targetHost);
                    view.put("targetPort", connection.targetPort);
                    view.put("clientIp", connection.clientIp);
                    view.put("connectTime", connection.connectTime);
                    view.put("uptime", connection.getUptime());
                    view.put("uploadBytes", connection.uploadBytes);
                    view.put("downloadBytes", connection.downloadBytes);
                    connections.add(view);
                }
            }
            data.put("connections", connections);
        }
        if (snapshot.connectionPageSize > 0) {
            data.put("connectionCount", snapshot.connectionCount);
            data.put("connectionPage", snapshot.connectionPage);
            data.put("connectionPageSize", snapshot.connectionPageSize);
        }
        return data;
    }

    @FunctionalInterface
    interface ProxyAction {
        Object execute() throws Exception;
    }

    @FunctionalInterface
    interface StatisticsAction {
        Socks5ProxyStatistics.StatisticsSnapshot execute() throws Exception;
    }
}
