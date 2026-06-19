package org.leo.service.disguise;

import org.leo.core.config.LeoConfig;
import org.leo.core.entity.Disguise;
import org.leo.core.entity.User;
import org.leo.core.manager.DisguiseManager;
import org.leo.core.util.aes.AesUtil;
import org.leo.core.util.javassist.JavassistDisguiseFactory;
import org.leo.core.util.json.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class DisguiseService {

    private static final String SAFE_CHAR_REGEX = "[^A-Za-z0-9_-]";
    private static final String DEFAULT_VERSION = "1.0.0";
    private static final String FILE_SUFFIX = ".disguise";

    private final DisguiseManager disguiseManager;

    @Autowired
    public DisguiseService(DisguiseManager disguiseManager) {
        this.disguiseManager = disguiseManager;
    }

    public void addDisguise(HashMap<String, Object> params, User user) throws Exception {
        ensureLoggedIn(user);
        addDisguise(params, user.getUserId());
    }

    public void addDisguise(HashMap<String, Object> params, String userId) throws Exception {
        requireNonBlank(userId, "用户未登录");
        String disguiseName = requireString(params, "disguiseName");
        String encodeBody = requireString(params, "encodeBody");
        String decodeBody = requireString(params, "decodeBody");
        Map<String, String> headers = parseHeaders(requireString(params, "headers"));
        String version = defaultVersion(optionalString(params, "version"));
        String description = optionalString(params, "description");
        String remark = optionalString(params, "remark");
        String disguiseId = optionalString(params, "disguiseId");
        if (isBlank(disguiseId)) {
            disguiseId = generateDisguiseId(disguiseName, version);
        }
        ensureDisguiseIdNotExists(disguiseId);
        ensureDisguiseLogic(encodeBody, decodeBody);

        Disguise disguise = new Disguise();
        disguise.setDisguiseId(disguiseId);
        disguise.setDisguiseName(disguiseName);
        disguise.setEncodeBody(encodeBody);
        disguise.setDecodeBody(decodeBody);
        disguise.setHeaders(headers);
        disguise.setVersion(version);
        disguise.setDescription(description);
        disguise.setRemark(remark);
        disguise.setCreateUserId(userId);
        disguise.setCreateTime(String.valueOf(System.currentTimeMillis()));

        installAndPersist(disguise);
    }

    public void updateDisguise(HashMap<String, Object> params) throws Exception {
        String disguiseId = requireString(params, "disguiseId");
        Disguise existingDisguise = disguiseManager.getDisguiseById(disguiseId);
        if (existingDisguise == null) {
            throw new IllegalArgumentException("disguise不存在");
        }

        if (params.containsKey("disguiseName")) {
            existingDisguise.setDisguiseName(optionalString(params, "disguiseName"));
        }
        if (params.containsKey("encodeBody")) {
            existingDisguise.setEncodeBody(optionalString(params, "encodeBody"));
        }
        if (params.containsKey("decodeBody")) {
            existingDisguise.setDecodeBody(optionalString(params, "decodeBody"));
        }
        if (params.containsKey("headers")) {
            existingDisguise.setHeaders(parseHeaders(requireString(params, "headers")));
        }
        if (params.containsKey("version")) {
            existingDisguise.setVersion(defaultVersion(optionalString(params, "version")));
        }
        if (params.containsKey("description")) {
            existingDisguise.setDescription(optionalString(params, "description"));
        }
        if (params.containsKey("remark")) {
            existingDisguise.setRemark(optionalString(params, "remark"));
        }

        ensureDisguiseLogic(existingDisguise.getEncodeBody(), existingDisguise.getDecodeBody());
        existingDisguise.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        installAndPersist(existingDisguise);
    }

    public void deleteDisguise(String disguiseId) {
        requireNonBlank(disguiseId, "disguiseId不能为空");
        Disguise disguise = disguiseManager.getDisguiseById(disguiseId);
        if (disguise == null) {
            throw new IllegalArgumentException("disguise不存在");
        }

        File disguiseFile = resolveDisguiseFile(disguiseId);
        if (!disguiseFile.exists()) {
            throw new IllegalArgumentException("disguise文件不存在，内置伪装或文件已丢失: " + disguiseId);
        }
        if (!disguiseFile.delete()) {
            throw new IllegalStateException("文件不存在或删除失败");
        }
        disguiseManager.unload(disguiseId);
    }

    public ArrayList<Disguise> getDisguises() {
        return disguiseManager.getDisguiseAsList();
    }

    public Disguise getDisguiseById(String disguiseId) {
        requireNonBlank(disguiseId, "disguiseId不能为空");
        Disguise disguise = disguiseManager.getDisguiseById(disguiseId);
        if (disguise == null) {
            throw new IllegalArgumentException("disguise不存在");
        }
        return disguise;
    }

    public void testDisguise(String encodeBody, String decodeBody) throws Exception {
        ensureDisguiseLogic(encodeBody, decodeBody);
    }

    public HashMap<String, Object> uploadDisguise(MultipartFile file, User user) throws Exception {
        ensureLoggedIn(user);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (isBlank(originalFilename)) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        Disguise disguise;
        try {
            String decrypted = AesUtil.decrypt(new String(file.getBytes(), StandardCharsets.UTF_8), LeoConfig.getPluginEncryptKey());
            disguise = (Disguise) JsonUtil.fromJsonString(decrypted, Disguise.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Disguise 文件解析失败: " + e.getMessage(), e);
        }
        if (disguise == null) {
            throw new IllegalArgumentException("Disguise 文件内容无效");
        }

        disguise.setCreateUserId(user.getUserId());
        disguise.setCreateTime(String.valueOf(System.currentTimeMillis()));
        disguise.setVersion(defaultVersion(disguise.getVersion()));

        String disguiseId = generateDisguiseId(disguise.getDisguiseName(), disguise.getVersion());
        ensureDisguiseIdNotExists(disguiseId);
        disguise.setDisguiseId(disguiseId);

        ensureDisguiseLogic(disguise.getEncodeBody(), disguise.getDecodeBody());
        installAndPersist(disguise);

        HashMap<String, Object> data = new HashMap<>();
        data.put("disguiseId", disguise.getDisguiseId());
        data.put("disguiseName", disguise.getDisguiseName());
        return data;
    }

    // ── 导出 ──────────────────────────────────────────────────────────────────

    /**
     * 导出单条伪装为加密的 .disguise 字节流。
     * 自定义伪装直接读取 VFS 文件；内置伪装（无 VFS 文件）在内存中序列化+加密后返回。
     */
    public byte[] exportDisguise(String disguiseId) throws Exception {
        requireNonBlank(disguiseId, "disguiseId不能为空");
        Disguise disguise = disguiseManager.getDisguiseById(disguiseId);
        if (disguise == null) {
            throw new IllegalArgumentException("disguise不存在: " + disguiseId);
        }
        File file = resolveDisguiseFile(disguiseId);
        if (file.exists()) {
            return Files.readAllBytes(file.toPath());
        }
        // 内置伪装没有 VFS 文件，实时序列化+加密
        String encrypted = AesUtil.encrypt(disguise.toString(), LeoConfig.getPluginEncryptKey());
        return encrypted.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 批量导出为 ZIP，每条伪装是 ZIP 中的一个 .disguise 条目。
     */
    public byte[] exportDisguisesZip(List<String> disguiseIds) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (String disguiseId : disguiseIds) {
                if (isBlank(disguiseId)) continue;
                try {
                    byte[] data = exportDisguise(disguiseId);
                    String entryName = getSafeFileName(disguiseId) + FILE_SUFFIX;
                    zos.putNextEntry(new ZipEntry(entryName));
                    zos.write(data);
                    zos.closeEntry();
                } catch (Exception e) {
                    // 跳过单条失败，不中断整体
                }
            }
        }
        return baos.toByteArray();
    }

    // ── 导入 ──────────────────────────────────────────────────────────────────

    public enum ConflictPolicy {
        SKIP, OVERWRITE, RENAME;

        public static ConflictPolicy parse(String value) {
            if (value == null) return SKIP;
            return switch (value.toLowerCase()) {
                case "overwrite" -> OVERWRITE;
                case "rename"    -> RENAME;
                default          -> SKIP;
            };
        }
    }

    public record ImportResult(String disguiseId, String disguiseName, String status, String message) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("disguiseId",   disguiseId);
            m.put("disguiseName", disguiseName);
            m.put("status",       status);
            m.put("message",      message);
            return m;
        }
    }

    /**
     * 导入 .disguise 或 .zip 文件，按冲突策略处理，返回逐条结果。
     */
    public List<ImportResult> importDisguises(MultipartFile file, ConflictPolicy policy, User user) throws Exception {
        ensureLoggedIn(user);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("文件不能为空");
        String fname = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        byte[] bytes = file.getBytes();
        if (fname.endsWith(".zip")) {
            return importFromZip(bytes, policy, user);
        } else if (fname.endsWith(FILE_SUFFIX)) {
            List<ImportResult> results = new ArrayList<>();
            results.add(importOneDisguiseBytes(bytes, policy, user));
            return results;
        } else {
            throw new IllegalArgumentException("不支持的文件类型，仅支持 .disguise 或 .zip");
        }
    }

    private List<ImportResult> importFromZip(byte[] zipBytes, ConflictPolicy policy, User user) throws Exception {
        List<ImportResult> results = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (entry.isDirectory() || !name.endsWith(FILE_SUFFIX)) {
                    zis.closeEntry();
                    continue;
                }
                byte[] entryBytes = zis.readAllBytes();
                results.add(importOneDisguiseBytes(entryBytes, policy, user));
                zis.closeEntry();
            }
        }
        return results;
    }

    private ImportResult importOneDisguiseBytes(byte[] data, ConflictPolicy policy, User user) {
        Disguise disguise;
        try {
            String decrypted = AesUtil.decrypt(new String(data, StandardCharsets.UTF_8), LeoConfig.getPluginEncryptKey());
            disguise = (Disguise) JsonUtil.fromJsonString(decrypted, Disguise.class);
        } catch (Exception e) {
            return new ImportResult(null, null, "failed", "文件解析失败: " + e.getMessage());
        }
        if (disguise == null || isBlank(disguise.getDisguiseId())) {
            return new ImportResult(null, null, "failed", "文件内容无效");
        }

        // 保留原始 disguiseId，按冲突策略处理
        String disguiseId = disguise.getDisguiseId();
        boolean exists = disguiseManager.getDisguiseById(disguiseId) != null;

        if (exists) {
            switch (policy) {
                case SKIP:
                    return new ImportResult(disguiseId, disguise.getDisguiseName(), "skipped", "已存在，已跳过");
                case RENAME:
                    disguiseId = disguiseId + "_import_" + System.currentTimeMillis();
                    disguise.setDisguiseId(disguiseId);
                    exists = false;
                    break;
                case OVERWRITE:
                    // 先卸载旧的
                    try {
                        File oldFile = resolveDisguiseFile(disguise.getDisguiseId());
                        if (oldFile.exists()) oldFile.delete();
                        disguiseManager.unload(disguise.getDisguiseId());
                    } catch (Exception ignored) {}
                    exists = true;
                    break;
            }
        }

        try {
            disguise.setCreateUserId(user.getUserId());
            disguise.setCreateTime(String.valueOf(System.currentTimeMillis()));
            disguise.setVersion(defaultVersion(disguise.getVersion()));
            ensureDisguiseLogic(disguise.getEncodeBody(), disguise.getDecodeBody());
            installAndPersist(disguise);
            String statusStr = (policy == ConflictPolicy.OVERWRITE && exists) ? "overwritten" : "imported";
            String msg       = (policy == ConflictPolicy.OVERWRITE && exists) ? "已覆盖"     : "导入成功";
            return new ImportResult(disguiseId, disguise.getDisguiseName(), statusStr, msg);
        } catch (Exception e) {
            return new ImportResult(disguiseId, disguise.getDisguiseName(), "failed", "保存失败: " + e.getMessage());
        }
    }

    private void installAndPersist(Disguise disguise) throws Exception {
        boolean installed = disguiseManager.inStallDisguise(disguise);
        if (!installed) {
            throw new IllegalStateException("安装disguise失败: " + disguise.getDisguiseId());
        }

        try {
            saveDisguise(disguise);
        } catch (Exception e) {
            disguiseManager.unload(disguise.getDisguiseId());
            throw e;
        }
    }

    private void saveDisguise(Disguise disguise) throws Exception {
        if (disguise == null || isBlank(disguise.getDisguiseId())) {
            throw new IllegalArgumentException("disguise或disguiseId不能为空");
        }
        File disguiseDir = resolveDisguiseDir();
        if (!disguiseDir.exists() && !disguiseDir.mkdirs()) {
            throw new IllegalStateException("创建disguise目录失败: " + disguiseDir.getAbsolutePath());
        }
        File disguiseFile = new File(disguiseDir, getSafeFileName(disguise.getDisguiseId()) + FILE_SUFFIX);
        try (FileOutputStream fileOutputStream = new FileOutputStream(disguiseFile)) {
            String encrypted = AesUtil.encrypt(disguise.toString(), LeoConfig.getPluginEncryptKey());
            fileOutputStream.write(encrypted.getBytes(StandardCharsets.UTF_8));
            fileOutputStream.flush();
        }
    }

    private File resolveDisguiseDir() {
        File root = new File(LeoConfig.getVfsPath());
        return new File(root, "disguise");
    }

    private File resolveDisguiseFile(String disguiseId) {
        return new File(resolveDisguiseDir(), getSafeFileName(disguiseId) + FILE_SUFFIX);
    }

    private void ensureDisguiseIdNotExists(String disguiseId) {
        if (disguiseManager.getDisguiseById(disguiseId) != null) {
            throw new IllegalArgumentException("disguiseId已存在: " + disguiseId);
        }
    }

    private void ensureDisguiseLogic(String encodeBody, String decodeBody) throws Exception {
        requireNonBlank(encodeBody, "encodeBody不能为空");
        requireNonBlank(decodeBody, "decodeBody不能为空");
        boolean testResult = JavassistDisguiseFactory.testDisguise(encodeBody, decodeBody);
        if (!testResult) {
            throw new IllegalArgumentException("测试失败：encode和decode方法无法正确互逆，请检查代码逻辑");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseHeaders(String headersJson) {
        Object parsed = JsonUtil.fromJsonString(headersJson, HashMap.class);
        if (!(parsed instanceof HashMap<?, ?> headers)) {
            throw new IllegalArgumentException("headers格式无效，必须为JSON对象");
        }
        return (Map<String, String>) headers;
    }

    private void ensureLoggedIn(User user) {
        if (user == null || isBlank(user.getUserId())) {
            throw new IllegalArgumentException("用户未登录");
        }
    }

    private String generateDisguiseId(String disguiseName, String version) {
        String safeName = disguiseName == null ? "" : disguiseName.replaceAll(SAFE_CHAR_REGEX, "_");
        if (isBlank(safeName)) {
            safeName = "Disguise_" + System.currentTimeMillis();
        }
        return safeName + "_" + defaultVersion(version);
    }

    private String getSafeFileName(String fileName) {
        String safeName = new File(fileName).getName();
        if (safeName.contains("..") || safeName.contains("/") || safeName.contains("\\") || !safeName.equals(fileName)) {
            throw new IllegalArgumentException("文件名包含非法字符");
        }
        return safeName;
    }

    private String requireString(HashMap<String, Object> params, String key) {
        Object value = params.get(key);
        String stringValue = value == null ? null : String.valueOf(value);
        requireNonBlank(stringValue, key + "不能为空");
        return stringValue;
    }

    private String optionalString(HashMap<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String defaultVersion(String version) {
        return isBlank(version) ? DEFAULT_VERSION : version;
    }

    private void requireNonBlank(String value, String message) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
