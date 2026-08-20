package org.leo.jmg.mem.shell.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeoAgentTplTest {

    @Test
    void appliesConfiguredResponseCodeAfterResolvingNonPositionalArguments() throws Exception {
        set("headerName", "X-Agent");
        set("headerValue", "ready");
        set("coreClassName", Core.class.getName());
        set("coreClass", "");
        set("respCode", Integer.valueOf(418));
        FixtureRequest request = new FixtureRequest();
        FixtureResponse response = new FixtureResponse();

        boolean handled = new LeoAgentTpl().equals(
                new Object[]{"prefix", Integer.valueOf(7), request, response});

        assertTrue(handled);
        assertEquals(418, response.status);
    }

    private static void set(String name, Object value) throws Exception {
        Field field = LeoAgentTpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    public static class FixtureRequest {
        public String getHeader(String name) {
            return "ready";
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }
    }

    public static class FixtureResponse {
        private int status;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

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
            if (method == null && args != null && args.length == 1
                    && args[0] instanceof ByteArrayOutputStream) {
                return null;
            }
            return null;
        }
    }
}
