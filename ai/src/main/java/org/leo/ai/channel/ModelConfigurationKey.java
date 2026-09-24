package org.leo.ai.channel;

import com.alibaba.fastjson.JSON;
import org.leo.core.entity.AiModelConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

final class ModelConfigurationKey {
    private ModelConfigurationKey() {}

    static String connection(AiModelConfig config) {
        return digest(config.getProviderId(), config.getProviderKey(), config.getBaseUrl(),
                config.getProtocol(), config.getCompletionsPath(), config.getApiKey(), config.getHeadersJson());
    }

    static String digest(Object... values) {
        try {
            byte[] bytes = JSON.toJSONString(Arrays.asList(values)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
