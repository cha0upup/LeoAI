package org.leo.jmg.mem.shell.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeoJettyHandlerTplTest {

    @Test
    void headerGateKeepsCustomStatusAndResponseBody() throws Exception {
        configure();
        LeoJettyHandlerTpl shell = new LeoJettyHandlerTpl();
        Request request = new Request("prefix-secret-suffix", "request");
        Response response = new Response();

        assertTrue(shell.handleRequest(request, response));
        assertEquals(418, response.status);
        assertEquals("handled", new String(response.output.toByteArray(), "UTF-8"));
    }

    @Test
    void unmatchedRequestContinuesOriginalHandler() throws Exception {
        configure();
        LeoJettyHandlerTpl shell = new LeoJettyHandlerTpl();
        NextHandler next = new NextHandler();
        Field field = LeoJettyHandlerTpl.class.getDeclaredField("nextHandler");
        field.setAccessible(true);
        field.set(shell, next);

        assertFalse(shell.handleRequest(new Request("other", "request"), new Response()));
        shell.forward(new Object[]{"/", new Object(), new Object(), new Object()});
        assertTrue(next.called);
    }

    private static void configure() throws Exception {
        set("headerName", "X-Test");
        set("headerValue", "secret");
        set("coreClassName", Core.class.getName());
        set("coreClass", "unused");
        set("respCode", Integer.valueOf(418));
    }

    private static void set(String name, Object value) throws Exception {
        Field field = LeoJettyHandlerTpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    public static class Request {
        private final String header;
        private final byte[] body;

        Request(String header, String body) throws Exception {
            this.header = header;
            this.body = body.getBytes("UTF-8");
        }

        public String getHeader(String name) {
            return header;
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(body);
        }
    }

    public static class Response {
        int status;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        public void setStatus(int status) {
            this.status = status;
        }

        public OutputStream getOutputStream() {
            return output;
        }
    }

    public static class Core implements java.lang.reflect.InvocationHandler {
        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            try {
                if (method != null || args == null || args.length != 1
                        || !(args[0] instanceof ByteArrayOutputStream)) return null;
                ByteArrayOutputStream output = (ByteArrayOutputStream) args[0];
                output.reset();
                output.write("handled".getBytes("UTF-8"));
                return null;
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    public static class NextHandler {
        boolean called;

        public void handle(Object first, Object second, Object third, Object fourth) {
            called = true;
        }
    }
}
