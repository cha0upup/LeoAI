package org.leo.web.controller.puppetnode.proxy;

import org.leo.core.engine.proxy.ProxyBindAddress;
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
        try {
            Socks5ProxyStatistics.StatisticsSnapshot snapshot = action.execute();
            if (snapshot == null) {
                return ApiResponse.error(unavailableMessage);
            }
            return ApiResponse.success(toView(snapshot));
        } catch (ApiException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        } catch (Exception error) {
            return ApiResponse.error(failureMessage + ": " + error.getMessage());
        }
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

        List<Map<String, Object>> connections = new ArrayList<Map<String, Object>>();
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
