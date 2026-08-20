package org.leo.phpcore.disguise;

import org.leo.core.disguise.DisguiseRuntimeValidator;
import org.leo.core.entity.Disguise;
import org.leo.core.runtime.PuppetRuntime;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Validates custom PHP disguise bodies in an isolated PHP CLI process when available. */
@Component
public final class PhpDisguiseValidator implements DisguiseRuntimeValidator {

    private static final int VALIDATION_TIMEOUT_SECONDS = 5;

    @Override
    public PuppetRuntime getRuntime() {
        return PuppetRuntime.PHP;
    }

    @Override
    public Map<String, Object> validate(Disguise disguise) throws Exception {
        PhpSourceSupport.requirePhp(disguise);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("runtime", "php");

        String php = resolvePhpBinary();
        if (php == null) {
            result.put("verified", false);
            result.put("message", "PHP CLI 不可用，已完成结构校验但未执行运行时互逆测试");
            return result;
        }

        Path script = Files.createTempFile("leo-php-disguise-", ".php");
        try {
            String source = "<?php\n"
                    + PhpSourceSupport.requestDecodeFunction(disguise)
                    + PhpSourceSupport.responseEncodeFunction(disguise)
                    + "$sample = \"\\x00\\x01leo\\xff\";\n"
                    + "$encoded = leo_traffic_encode($sample);\n"
                    + "$decoded = leo_traffic_decode($encoded);\n"
                    + "if ($decoded !== $sample) { fwrite(STDERR, 'roundtrip mismatch'); exit(3); }\n"
                    + "echo 'OK';\n";
            Files.writeString(script, source, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(php, "-n", script.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(VALIDATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalArgumentException("PHP 伪装验证超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || !"OK".equals(output)) {
                throw new IllegalArgumentException("PHP 伪装验证失败: " + output);
            }
            result.put("verified", true);
            result.put("message", "PHP encode/decode 互逆验证通过");
            return result;
        } finally {
            Files.deleteIfExists(script);
        }
    }

    private String resolvePhpBinary() {
        String configured = System.getProperty("leo.php.binary");
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return configured;
        }
        for (String candidate : new String[]{"/opt/homebrew/bin/php", "/usr/local/bin/php", "/usr/bin/php"}) {
            if (Files.isExecutable(Path.of(candidate))) return candidate;
        }
        return null;
    }
}
