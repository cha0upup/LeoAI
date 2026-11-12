package org.leo.service.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.leo.service.discovery.NetworkProbePayloads.copyMapOrEmpty;
import static org.leo.service.discovery.NetworkProbePayloads.integer;
import static org.leo.service.discovery.NetworkProbePayloads.text;

/** Pure service-identification rules; no task scheduling, node I/O or persistence. */
final class ServiceIdentificationPolicy {
    private ServiceIdentificationPolicy() { }

    private static final Pattern MYSQL_VERSION = Pattern.compile("\\b\\d+\\.\\d+(?:\\.\\d+)?\\b");

    static List<Map<String, Object>> httpCandidates(List<Map<String, Object>> endpoints) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> endpoint : endpoints) {
            String service = text(endpoint.get("service")).toLowerCase(Locale.ROOT);
            if (isDefinitiveNonHttpService(service) || "http".equals(service) || "https".equals(service)) {
                continue;
            }
            result.add(endpoint);
        }
        return result;
    }

    static List<Map<String, Object>> httpsCandidates(List<Map<String, Object>> candidates) {
        // Candidates retain the live endpoint maps updated by the HTTP stage.
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> endpoint : candidates) {
            if (isDefaultHttpsPort(integer(endpoint.get("port"), -1))) continue;
            String service = text(endpoint.get("service")).toLowerCase(Locale.ROOT);
            if (service.isEmpty() || "unknown".equals(service)) result.add(endpoint);
        }
        return result;
    }

    static void applyServiceObservation(Map<String, Object> endpoint, Map<String, Object> observation) {
        String stage = text(observation.get("stage"));
        endpoint.put("probe", stage);
        Map<String, Object> evidence = copyMapOrEmpty(observation.get("evidence"));
        if (!evidence.isEmpty()) endpoint.put("evidence", evidence);
        if (evidence.get("banner") != null) endpoint.put("banner", evidence.get("banner"));
        if (isHttpObservation(stage, evidence)) {
            endpoint.put("statusCode", evidence.get("statusCode"));
            int port = integer(endpoint.get("port"), -1);
            String observedProtocol = text(observation.get("protocol")).toLowerCase(Locale.ROOT);
            String scheme = "https".equals(observedProtocol) ? "https" : httpProbeScheme(port);
            endpoint.put("protocol", scheme);
            endpoint.put("service", scheme);
            endpoint.put("confidence", Double.valueOf(0.9));
        }
        String detectedService = detectService(text(evidence.get("banner")));
        if (!detectedService.isEmpty() && endpoint.get("service") == null) {
            endpoint.put("service", detectedService);
            if ("http".equals(detectedService)) endpoint.put("protocol", "http");
            endpoint.put("confidence", Double.valueOf(0.7));
        }
        if (endpoint.get("service") != null && !text(endpoint.get("service")).isEmpty()) {
            endpoint.put("serviceIdentified", Boolean.TRUE);
        }
        for (String key : List.of("server", "location", "contentType", "responseSize")) {
            if (evidence.get(key) != null) endpoint.put(key, evidence.get(key));
        }
        if (evidence.get("title") != null && !text(evidence.get("title")).isEmpty()) {
            endpoint.put("title", evidence.get("title"));
        }
        if (observation.get("error") != null) endpoint.put("probeError", observation.get("error"));
    }

    static String detectService(String banner) {
        String value = banner == null ? "" : banner.toLowerCase(Locale.ROOT);
        if (value.startsWith("ssh-") || value.contains("openssh")) return "ssh";
        if (value.contains("ftp")) return "ftp";
        if (value.contains("mysql") || value.contains("mariadb")) return "mysql";
        // MySQL sends a binary handshake. After control bytes are sanitized,
        // the server version and authentication plugin remain while the word
        // "mysql" is often absent (for example: 8.0.43 + caching_sha2_password).
        if ((value.contains("caching_sha2_password") || value.contains("sha256_password"))
                && MYSQL_VERSION.matcher(value).find()) return "mysql";
        if (value.contains("redis")) return "redis";
        if (value.contains("mongodb")) return "mongodb";
        if (value.contains("postgres")) return "postgresql";
        if (value.contains("smtp")) return "smtp";
        if (value.contains("http/1.") || value.contains("server:")) return "http";
        return "";
    }

    static String httpProbeScheme(int port) {
        return isDefaultHttpsPort(port) ? "https" : "http";
    }

    static boolean isDefaultHttpsPort(int port) {
        return port == 443 || port == 8443;
    }

    static boolean isHttpObservation(String stage, Map<String, Object> evidence) {
        if (!"http-head".equals(stage) && !"http-request".equals(stage)) return false;
        int statusCode = integer(evidence.get("statusCode"), -1);
        return statusCode >= 100 && statusCode <= 599;
    }

    static boolean isDefinitiveNonHttpService(String service) {
        return switch (service) {
            case "ssh", "ftp", "mysql", "mariadb", "redis", "mongodb", "postgresql", "postgres", "smtp" -> true;
            default -> false;
        };
    }

    static String hostForUrl(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }
}
