package org.leo.core.net.layer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.Disguise;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestLayerTest {

    @Test
    void mergesDisguiseAndCustomHeadersAndBuildsBinarySafeRelayHeaders() {
        Disguise disguise = new Disguise();
        disguise.setHeaders(Map.of(
                "Content-Type", "text/plain",
                "X-Profile", "default",
                "Accept-Encoding", "gzip"));
        RequestLayer layer = new RequestLayer("/inner", Map.of(
                "x-profile", "custom"), disguise, "test-key");

        Map<String, String> merged = layer.getMergedHeaders();
        assertEquals("text/plain", merged.get("Content-Type"));
        assertEquals("custom", merged.get("x-profile"));
        assertFalse(merged.containsKey("X-Profile"));

        Map<String, String> relay = layer.getRelayHeaders();
        assertEquals("text/plain", relay.get("Content-Type"));
        assertEquals("identity", relay.get("Accept-Encoding"));
    }

    @Test
    void suppliesContentTypeWhenDisguiseDoesNotDeclareOne() {
        RequestLayer layer = new RequestLayer("/inner", Map.of(), new Disguise(), "test-key");
        assertEquals("application/octet-stream", layer.getRelayHeaders().get("Content-Type"));
    }
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingPerHopKeys(String key) {
        assertThrows(IllegalArgumentException.class,
                () -> new RequestLayer("/", Map.of(), new Disguise(), key));
        assertThrows(IllegalArgumentException.class,
                () -> new ResponseLayer(new Disguise(), key));
    }

}
