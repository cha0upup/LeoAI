package org.leo.core.net.layer;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTransportStrategyTest {

    @Test
    void seededHeadersRemainStableWithinSession() {
        HeaderNoiseStrategy strategy = new HeaderNoiseStrategy();
        strategy.setEnabled(true);
        strategy.setMinHeaders(2);
        strategy.setMaxHeaders(2);
        strategy.setPrefixes(new String[]{"X-Request-Id", "X-Trace-Id", "X-Client-Version"});

        HeaderNoiseGenerator generator = new HeaderNoiseGenerator(strategy, "host-a");
        assertEquals(generator.generate(), generator.generate());
        assertNotEquals(generator.generate(), new HeaderNoiseGenerator(strategy, "host-b").generate());
    }

    @Test
    void seededUrlIsStableAbsoluteAndMethodCoherent() {
        UrlStrategy strategy = new UrlStrategy();
        strategy.setEnabled(true);
        strategy.setMode(UrlStrategy.Mode.TEMPLATE);
        strategy.setUrlTemplate("/assets/{rand}{ext}");
        strategy.setExtensions(List.of(".png"));

        UrlGenerator generator = new UrlGenerator(strategy,
                "https://example.test/entry.php", "host-a");
        String first = generator.nextUrl("POST");
        assertEquals(first, generator.nextUrl("POST"));
        assertTrue(first.startsWith("https://example.test/assets/"));
        assertTrue(first.endsWith(".json"));
        assertNotEquals(first, new UrlGenerator(strategy,
                "https://example.test/entry.php", "host-b").nextUrl("POST"));
    }

    @Test
    void bucketPaddingUsesDerivedKeyAndHonorsBounds() {
        PaddingStrategy strategy = new PaddingStrategy()
                .setEnabled(true)
                .setMinBytes(64)
                .setMaxBytes(600)
                .setBucketSizes(new int[]{1024, 2048})
                .setMaxTotalBytes(2048);
        Map<String, Object> first = new LinkedHashMap<>(Map.of("operation", "PING"));
        Map<String, Object> second = new LinkedHashMap<>(Map.of("operation", "PING"));

        PaddingUtil.pad(first, strategy, 400, "request-a");
        PaddingUtil.pad(second, strategy, 400, "request-a");

        assertEquals(first, second);
        assertEquals(2, first.size());
        Map.Entry<String, Object> padding = first.entrySet().stream()
                .filter(entry -> !"operation".equals(entry.getKey())).findFirst().orElseThrow();
        assertTrue(padding.getKey().matches("_[a-f0-9]{12}"));
        assertEquals(592, String.valueOf(padding.getValue()).length());

    }

    @Test
    void sessionProfileUsesSameOriginAndStableHeaders() {
        Map<String, String> first = HttpSessionProfile.headers(
                "host-a", "https://example.test:8443/api/entry");
        assertEquals(first, HttpSessionProfile.headers(
                "host-a", "https://example.test:8443/api/entry"));
        assertEquals("https://example.test:8443/", first.get("Referer"));
        assertTrue(first.get("User-Agent").startsWith("Mozilla/5.0"));
        assertFalse(first.get("Accept-Language").isBlank());
    }
}
