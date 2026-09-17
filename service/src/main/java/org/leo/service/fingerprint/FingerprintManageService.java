package org.leo.service.fingerprint;

import org.leo.core.config.LeoConfig;
import org.leo.core.entity.User;
import org.leo.core.util.SafeZipReader;
import org.leo.core.util.json.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class FingerprintManageService {

    private static final String FINGERPRINT_DIR_NAME = "fingerprint";
    private static final String FINGERPRINT_FILE_SUFFIX = ".json";
    private static final String DEFAULT_VERSION = "1.0";
    private static final String SAFE_ID_REGEX = "[^A-Za-z0-9_\\-]";

    public List<Map<String, Object>> listFingerprints() {
        File fingerprintDir = resolveFingerprintDir();
        if (!fingerprintDir.exists() || !fingerprintDir.isDirectory()) {
            return new ArrayList<>();
        }
        File[] files = fingerprintDir.listFiles((dir, name) -> name != null && name.endsWith(FINGERPRINT_FILE_SUFFIX));
        ArrayList<Map<String, Object>> list = new ArrayList<>();
        if (files == null) {
            return list;
        }
        for (File file : files) {
            Map<String, Object> summary = toFingerprintSummary(file);
            if (isHttpFingerprint(summary)) list.add(summary);
        }
        return list;
    }

    public HashMap<String, Object> getFingerprintById(String fingerprintId) throws Exception {
        String safeName = getSafeFileName(requireNonBlank(fingerprintId, "fingerprintId 不能为空"));
        HashMap<String, Object> content = loadFingerprintFile(safeName);
        if (content == null) {
            throw new FingerprintNotFoundException("指纹不存在: " + fingerprintId);
        }
        normalizeFingerprintId(content, safeName);
        if (!isHttpFingerprint(content)) {
            throw new FingerprintNotFoundException("HTTP 指纹不存在: " + fingerprintId);
        }
        return content;
    }

    public HashMap<String, Object> saveFingerprint(HashMap<String, Object> params, User user) throws Exception {
        ensureLoggedIn(user);
        String name = requireString(params, "name");
        Object protocol = params.get("protocol");
        if (protocol != null && !isBlank(String.valueOf(protocol))
                && !"http".equalsIgnoreCase(String.valueOf(protocol).trim())) {
            throw new IllegalArgumentException("仅支持 HTTP 指纹");
        }
        Object rule = params.get("rule");
        if (rule == null) {
            throw new IllegalArgumentException("缺少必需参数: rule");
        }
        String version = getVersionFromParams(params);
        if (isBlank(version)) {
            throw new IllegalArgumentException("缺少必需参数: info.version 或 version");
        }
        return saveFingerprintContent(name, rule, params.get("info"), params.get("tags"), version);
    }

    public Map<String, Object> saveFingerprint(String userId, String name, String ruleJson,
                                               String infoJson, String tagsJson,
                                               String version) throws Exception {
        requireNonBlank(userId, "userId 不能为空");
        String normalizedName = requireNonBlank(name, "name 不能为空");
        Object rule = parseJsonObject(requireNonBlank(ruleJson, "ruleJson 不能为空"));
        HashMap<String, Object> info = parseInfo(infoJson);
        String resolvedVersion = resolveVersion(info, version);
        Object tags = isBlank(tagsJson) ? null : parseJson(tagsJson);

        HashMap<String, Object> data = saveFingerprintContent(normalizedName, rule, info, tags, resolvedVersion);
        data.put("status", "saved");
        data.put("name", normalizedName);
        return data;
    }

    public void deleteFingerprint(User user, String fingerprintId) {
        ensureLoggedIn(user);
        deleteFingerprint(fingerprintId);
    }

    public void deleteFingerprint(String userId, String fingerprintId) {
        requireNonBlank(userId, "userId 不能为空");
        deleteFingerprint(fingerprintId);
    }

    public void deleteFingerprint(String fingerprintId) {
        String normalizedFingerprintId = requireNonBlank(fingerprintId, "fingerprintId 不能为空");
        File fingerprintFile = resolveFingerprintFile(normalizedFingerprintId);
        if (!fingerprintFile.exists() || !fingerprintFile.isFile()) {
            throw new FingerprintNotFoundException("指纹不存在: " + normalizedFingerprintId);
        }
        if (!fingerprintFile.delete()) {
            throw new IllegalStateException("删除失败");
        }
    }

    public String generateFingerprintId(String name, String version) {
        String safeName = name == null ? "" : name.trim().replaceAll(SAFE_ID_REGEX, "_");
        String safeVersion = version == null ? "" : version.trim().replaceAll(SAFE_ID_REGEX, "_");
        if (safeName.isEmpty()) {
            safeName = "fingerprint";
        }
        if (safeVersion.isEmpty()) {
            safeVersion = DEFAULT_VERSION;
        }
        return safeName + "_" + safeVersion;
    }

    private HashMap<String, Object> saveFingerprintContent(String name, Object rule, Object infoObj,
                                                           Object tags, String version) throws Exception {
        String normalizedName = requireNonBlank(name, "name 不能为空");
        String resolvedVersion = requireNonBlank(version, "version 不能为空");
        validateRule(rule);
        String fingerprintId = generateFingerprintId(normalizedName, resolvedVersion);
        String safeName = getSafeFileName(fingerprintId);

        HashMap<String, Object> content = new HashMap<>();
        content.put("fingerprintId", fingerprintId);
        content.put("name", normalizedName);
        content.put("rule", rule);
        content.put("protocol", "http");
        if (tags != null) {
            content.put("tags", tags);
        }

        HashMap<String, Object> info = normalizeInfo(infoObj);
        info.put("version", resolvedVersion);
        content.put("info", info);

        File fingerprintDir = resolveFingerprintDir();
        if (!fingerprintDir.exists() && !fingerprintDir.mkdirs()) {
            throw new IllegalStateException("创建指纹目录失败: " + fingerprintDir.getAbsolutePath());
        }
        Files.write(new File(fingerprintDir, safeName + FINGERPRINT_FILE_SUFFIX).toPath(),
                JsonUtil.toJsonString(content).getBytes(StandardCharsets.UTF_8));

        HashMap<String, Object> data = new HashMap<>();
        data.put("fingerprintId", fingerprintId);
        return data;
    }

    public void validateRule(Object ruleValue) {
        if (!(ruleValue instanceof Map<?, ?> rule)) {
            throw new IllegalArgumentException("rule 必须是 JSON 对象");
        }
        Object requestsValue = rule.get("requests");
        if (!(requestsValue instanceof List<?> requests) || requests.isEmpty()) {
            throw new IllegalArgumentException("rule.requests 必须是非空数组");
        }
        if (requests.size() > 16) throw new IllegalArgumentException("rule.requests 不能超过16个");
        if (!(rule.get("match") instanceof Map<?, ?> match)) {
            throw new IllegalArgumentException("rule.match 必须是声明式匹配对象");
        }
        for (Object value : requests) {
            if (!(value instanceof Map<?, ?> request)) throw new IllegalArgumentException("rule.requests 中的请求必须是对象");
            Object pathValue = request.get("uri");
            if (pathValue == null || String.valueOf(pathValue).isBlank()) pathValue = request.get("path");
            String path = pathValue == null ? "/" : String.valueOf(pathValue).trim();
            if (path.isEmpty()) path = "/";
            try {
                java.net.URI uri = java.net.URI.create(path);
                if (uri.isAbsolute() || uri.getRawAuthority() != null || path.startsWith("//") || path.contains("\\"))
                    throw new IllegalArgumentException("规则路径必须是站内相对路径");
            } catch (IllegalArgumentException error) { throw new IllegalArgumentException("无效的规则请求路径: " + path); }
        }
        validateMatch(match, 0);
        validateRequestIndices(match, requests.size());
        if (rule.containsKey("version")) validateVersion(rule.get("version"), requests.size());
    }

    private void validateVersion(Object value, int requestCount) {
        if (!(value instanceof Map<?, ?> extractor)) throw new IllegalArgumentException("rule.version 必须是对象");
        if (!(extractor.get("field") instanceof String field) || !List.of("body", "headers").contains(field))
            throw new IllegalArgumentException("rule.version.field 仅支持 body、headers");
        if (!(extractor.get("prefix") instanceof String prefix) || prefix.isEmpty() || prefix.length() > 256)
            throw new IllegalArgumentException("rule.version.prefix 必须是 1–256 字符的文本前缀");
        if (extractor.containsKey("suffix") && (!(extractor.get("suffix") instanceof String suffix) || suffix.length() > 256))
            throw new IllegalArgumentException("rule.version.suffix 不能超过 256 字符");
        if (extractor.containsKey("ignoreCase") && !(extractor.get("ignoreCase") instanceof Boolean))
            throw new IllegalArgumentException("rule.version.ignoreCase 必须是布尔值");
        if (extractor.keySet().stream().anyMatch(key -> !List.of("request", "field", "prefix", "suffix", "ignoreCase").contains(key)))
            throw new IllegalArgumentException("rule.version 仅支持 request、field、prefix、suffix、ignoreCase");
        validateRequestIndices(extractor, requestCount);
    }

    private void validateRequestIndices(Map<?, ?> match, int count) {
        for (String key : List.of("all", "any")) {
            if (match.get(key) instanceof List<?> children)
                for (Object child : children) validateRequestIndices((Map<?, ?>) child, count);
        }
        if (match.get("not") instanceof Map<?, ?> child) validateRequestIndices(child, count);
        if (match.containsKey("request")) {
            Object index = match.get("request");
            if (!(index instanceof Number number) || number.doubleValue() != number.intValue()
                    || number.intValue() < 0 || number.intValue() >= count)
                throw new IllegalArgumentException("rule.match.request 超出请求序号范围");
        }
    }

    private void validateMatch(Map<?, ?> match, int depth) {
        if (depth > 8) throw new IllegalArgumentException("rule.match 嵌套不能超过8层");
        long forms = List.of("all", "any", "not", "field").stream().filter(match::containsKey).count();
        if (forms != 1) throw new IllegalArgumentException("匹配表达式必须且只能包含 all、any、not 或 field 之一");
        Object children = match.containsKey("all") ? match.get("all") : match.get("any");
        if (children != null) {
            if (!(children instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalArgumentException("rule.match 的 all/any 必须是非空数组");
            }
            for (Object child : list) {
                if (!(child instanceof Map<?, ?> childMap)) {
                    throw new IllegalArgumentException("rule.match 子表达式必须是对象");
                }
                validateMatch(childMap, depth + 1);
            }
            return;
        }
        if (match.get("not") instanceof Map<?, ?> child) {
            validateMatch(child, depth + 1);
            return;
        }
        String field = match.get("field") == null ? "" : String.valueOf(match.get("field")).trim();
        if (!List.of("status", "body", "headers", "raw", "bodyLength", "truncated", "error", "errorCode")
                .contains(field)) {
            throw new IllegalArgumentException("rule.match.field 不支持: " + field);
        }
        String operator = match.get("operator") == null
                ? "contains" : String.valueOf(match.get("operator")).trim().toLowerCase();
        if (!List.of("contains", "notcontains", "equals", "in", "exists", "startswith", "endswith")
                .contains(operator)) {
            throw new IllegalArgumentException("rule.match.operator 不支持: " + operator);
        }
        if (!"exists".equals(operator) && (!match.containsKey("value") || match.get("value") == null))
            throw new IllegalArgumentException("匹配条件缺少 value");
        if (List.of("contains", "notcontains", "startswith", "endswith").contains(operator)
                && String.valueOf(match.get("value")).isEmpty())
            throw new IllegalArgumentException("文本匹配值不能为空");
        if ("in".equals(operator) && (!(match.get("value") instanceof List<?> values) || values.isEmpty()))
            throw new IllegalArgumentException("in 的 value 必须是非空数组");
    }

    private Map<String, Object> toFingerprintSummary(File file) {
        String filename = file.getName();
        String fileId = filename.substring(0, filename.length() - FINGERPRINT_FILE_SUFFIX.length());
        HashMap<String, Object> item = new HashMap<>();
        try {
            HashMap<String, Object> content = loadFingerprintFile(fileId);
            if (content != null) {
                normalizeFingerprintId(content, fileId);
                item.put("fingerprintId", content.get("fingerprintId"));
                if (content.containsKey("protocol")) {
                    item.put("protocol", content.get("protocol"));
                }
                if (content.containsKey("name")) {
                    item.put("name", content.get("name"));
                }
                if (content.containsKey("tags")) {
                    item.put("tags", content.get("tags"));
                }
                if (content.containsKey("info")) {
                    item.put("info", content.get("info"));
                }
                return item;
            }
        } catch (Exception ignored) {
        }
        item.put("fingerprintId", fileId);
        return item;
    }

    private boolean isHttpFingerprint(Map<String, Object> fingerprint) {
        return fingerprint != null && "http".equalsIgnoreCase(String.valueOf(fingerprint.get("protocol")));
    }

    private HashMap<String, Object> loadFingerprintFile(String safeFileName) throws Exception {
        File fingerprintFile = new File(resolveFingerprintDir(), safeFileName + FINGERPRINT_FILE_SUFFIX);
        if (!fingerprintFile.exists() || !fingerprintFile.isFile()) {
            return null;
        }
        String json = new String(Files.readAllBytes(fingerprintFile.toPath()), StandardCharsets.UTF_8);
        Object parsed = JsonUtil.fromJsonString(json, Object.class);
        if (!(parsed instanceof Map<?, ?> parsedMap)) {
            throw new IllegalArgumentException("指纹文件格式无效: " + safeFileName);
        }
        return new HashMap<>(copyStringKeyMap(parsedMap));
    }

    private File resolveFingerprintDir() {
        return new File(new File(LeoConfig.getVfsPath()), FINGERPRINT_DIR_NAME);
    }

    private File resolveFingerprintFile(String fingerprintId) {
        return new File(resolveFingerprintDir(), getSafeFileName(fingerprintId) + FINGERPRINT_FILE_SUFFIX);
    }

    private void normalizeFingerprintId(HashMap<String, Object> content, String fallbackId) {
        Object fingerprintId = content.get("fingerprintId");
        if (fingerprintId == null) {
            fingerprintId = content.get("id");
        }
        content.put("fingerprintId", fingerprintId != null ? fingerprintId : fallbackId);
        content.remove("id");
    }

    private HashMap<String, Object> normalizeInfo(Object infoObj) {
        if (infoObj == null) {
            return new HashMap<>();
        }
        if (!(infoObj instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("info 必须是 JSON 对象");
        }
        HashMap<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private HashMap<String, Object> parseInfo(String infoJson) {
        if (isBlank(infoJson)) {
            return new HashMap<>();
        }
        Object parsed = parseJson(infoJson);
        return normalizeInfo(parsed);
    }

    private String resolveVersion(HashMap<String, Object> info, String version) {
        if (!isBlank(version)) {
            return version.trim();
        }
        Object value = info.get("version");
        if (value != null && !String.valueOf(value).isBlank()) {
            return String.valueOf(value).trim();
        }
        return DEFAULT_VERSION;
    }

    private String getVersionFromParams(HashMap<String, Object> params) {
        Object infoObj = params.get("info");
        if (infoObj instanceof Map<?, ?> map) {
            Object value = map.get("version");
            if (value != null) {
                return String.valueOf(value).trim();
            }
        }
        Object value = params.get("version");
        return value != null ? String.valueOf(value).trim() : null;
    }

    private Object parseJsonObject(String json) {
        Object parsed = parseJson(json);
        if (parsed == null) {
            throw new IllegalArgumentException("JSON 内容不能为空");
        }
        return parsed;
    }

    private Object parseJson(String json) {
        Object parsed = JsonUtil.fromJsonString(json, Object.class);
        if (parsed == null) {
            throw new IllegalArgumentException("JSON 格式无效");
        }
        return parsed;
    }

    private String getSafeFileName(String fingerprintId) {
        String safeName = new File(fingerprintId).getName();
        if (safeName.contains("..") || safeName.contains("/") || safeName.contains("\\")
                || safeName.isEmpty() || !safeName.equals(fingerprintId)) {
            throw new IllegalArgumentException("fingerprintId 包含非法字符");
        }
        return safeName;
    }

    private String requireString(HashMap<String, Object> params, String key) {
        Object value = params.get(key);
        return requireNonBlank(value == null ? null : String.valueOf(value), "缺少必需参数: " + key);
    }

    private String requireNonBlank(String value, String message) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private void ensureLoggedIn(User user) {
        if (user == null || isBlank(user.getUserId())) {
            throw new IllegalArgumentException("用户未登录");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static class FingerprintNotFoundException extends IllegalArgumentException {
        public FingerprintNotFoundException(String message) {
            super(message);
        }
    }

    // ── 导出 ──────────────────────────────────────────────────────────────────

    /**
     * 单条导出：将指纹 JSON 写入输出流（Content-Disposition 由 Controller 设置）。
     */
    public byte[] exportFingerprint(String fingerprintId) throws Exception {
        HashMap<String, Object> detail = getFingerprintById(fingerprintId);
        return JsonUtil.toJsonString(detail).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 批量导出：将多条指纹打包为 zip，每条对应 zip 内一个 <fingerprintId>.json。
     */
    public byte[] exportFingerprintsZip(List<String> fingerprintIds) throws Exception {
        if (fingerprintIds == null || fingerprintIds.isEmpty()) {
            throw new IllegalArgumentException("fingerprintIds 不能为空");
        }
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(buf))) {
            for (String id : fingerprintIds) {
                if (id == null || id.isBlank()) continue;
                try {
                    HashMap<String, Object> detail = getFingerprintById(id.trim());
                    String entryName = (detail.get("fingerprintId") != null
                            ? String.valueOf(detail.get("fingerprintId")) : id.trim()) + ".json";
                    byte[] data = JsonUtil.toJsonString(detail).getBytes(StandardCharsets.UTF_8);
                    ZipEntry entry = new ZipEntry(entryName);
                    entry.setSize(data.length);
                    zos.putNextEntry(entry);
                    zos.write(data);
                    zos.closeEntry();
                } catch (FingerprintNotFoundException ignored) {
                    // 跳过不存在的条目，不中断整体导出
                }
            }
        }
        return buf.toByteArray();
    }

    // ── 导入 ──────────────────────────────────────────────────────────────────

    public enum ConflictPolicy {
        SKIP, OVERWRITE, RENAME;

        public static ConflictPolicy parse(String s) {
            if (s == null) return SKIP;
            return switch (s.toLowerCase()) {
                case "overwrite" -> OVERWRITE;
                case "rename"    -> RENAME;
                default          -> SKIP;
            };
        }
    }

    public record ImportResult(String name, String fingerprintId, String status, String message) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("fingerprintId", fingerprintId);
            m.put("status", status);
            if (message != null && !message.isBlank()) m.put("message", message);
            return m;
        }
    }

    /**
     * 导入指纹。支持：
     * <ul>
     *   <li>单条 .json（对象 {} 或数组 [{}...]）</li>
     *   <li>.zip（内含若干 .json，格式同上）</li>
     * </ul>
     */
    public List<ImportResult> importFingerprints(MultipartFile file,
                                                  ConflictPolicy policy,
                                                  User user) throws Exception {
        ensureLoggedIn(user);
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        List<Map<String, Object>> records = new ArrayList<>();

        if (filename.endsWith(".zip")) {
            records = parseZip(file.getInputStream());
        } else if (filename.endsWith(".json")) {
            records = parseJson(file.getBytes());
        } else {
            throw new IllegalArgumentException("仅支持 .json 或 .zip 文件");
        }

        // 获取现有 ID 集合用于冲突检测
        java.util.Set<String> existingIds = new java.util.HashSet<>();
        for (Map<String, Object> item : listFingerprints()) {
            Object id = item.get("fingerprintId");
            if (id != null) existingIds.add(String.valueOf(id));
        }

        List<ImportResult> results = new ArrayList<>();
        for (Map<String, Object> rec : records) {
            results.add(importOneRecord(rec, existingIds, policy));
        }
        return results;
    }

    private ImportResult importOneRecord(Map<String, Object> rec,
                                          java.util.Set<String> existingIds,
                                          ConflictPolicy policy) {
        // 基本校验
        String name = rec.get("name") != null ? String.valueOf(rec.get("name")).trim() : null;
        if (name == null || name.isBlank()) {
            return new ImportResult(null, null, "failed", "缺少 name");
        }
        if (rec.get("rule") == null) {
            return new ImportResult(name, null, "failed", "缺少 rule");
        }
        String version = extractVersion(rec);
        if (version == null || version.isBlank()) {
            return new ImportResult(name, null, "failed", "缺少 version");
        }
        if (!"http".equalsIgnoreCase(String.valueOf(rec.getOrDefault("protocol", "http")))) {
            return new ImportResult(name, null, "failed", "仅支持 HTTP 指纹");
        }

        String fingerprintId = generateFingerprintId(name, version);
        boolean conflict = existingIds.contains(fingerprintId);

        if (conflict) {
            switch (policy) {
                case SKIP:
                    return new ImportResult(name, fingerprintId, "skipped", "已存在，跳过");
                case RENAME: {
                    String ts = String.valueOf(System.currentTimeMillis());
                    version = version + "_imported_" + ts;
                    fingerprintId = generateFingerprintId(name, version);
                    // 更新 rec 中的版本
                    rec = new HashMap<>(rec);
                    rec.put("version", version);
                    if (rec.get("info") instanceof Map<?, ?> info) {
                        HashMap<String, Object> newInfo = new HashMap<>();
                        info.forEach((k, v) -> newInfo.put(String.valueOf(k), v));
                        newInfo.put("version", version);
                        rec.put("info", newInfo);
                    }
                    break;
                }
                case OVERWRITE:
                    break; // 直接覆盖
            }
        }

        try {
            saveFingerprintContent(
                    name,
                    rec.get("rule"),
                    rec.get("info"),
                    rec.get("tags"),
                    version
            );
            existingIds.add(fingerprintId);
            String status = conflict
                    ? (policy == ConflictPolicy.OVERWRITE ? "overwritten" : "renamed")
                    : "imported";
            return new ImportResult(name, fingerprintId, status, null);
        } catch (Exception e) {
            return new ImportResult(name, fingerprintId, "failed", e.getMessage());
        }
    }

    private String extractVersion(Map<String, Object> rec) {
        Object info = rec.get("info");
        if (info instanceof Map<?, ?> infoMap) {
            Object v = infoMap.get("version");
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
        }
        Object v = rec.get("version");
        return v != null ? String.valueOf(v).trim() : null;
    }

    private List<Map<String, Object>> parseJson(byte[] bytes) throws Exception {
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        Object parsed = JsonUtil.fromJsonString(text, Object.class);
        if (parsed instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    result.add(copyStringKeyMap(m));
                }
            }
            return result;
        } else if (parsed instanceof Map<?, ?> m) {
            return List.of(copyStringKeyMap(m));
        }
        throw new IllegalArgumentException("JSON 格式无效：必须是对象或数组");
    }

    private static Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) copy.put(stringKey, value);
        });
        return copy;
    }

    private List<Map<String, Object>> parseZip(InputStream inputStream) throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        SafeZipReader.forEach(
                inputStream,
                name -> name.toLowerCase().endsWith(".json"),
                SafeZipReader.Limits.DEFAULT,
                (name, bytes) -> {
                    try {
                        records.addAll(parseJson(bytes));
                    } catch (Exception ignored) {
                        // 单个文件解析失败不中断整体
                    }
                }
        );
        return records;
    }
}
