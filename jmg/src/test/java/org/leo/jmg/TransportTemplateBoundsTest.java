package org.leo.jmg;

import org.junit.jupiter.api.Test;
import org.leo.jmg.jsp.httpchunk.JspServer;
import org.leo.jmg.jsp.httpchunk.JspxServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportTemplateBoundsTest {

    @Test
    void chunkedTemplatesBoundRequestsAndResponses() throws Exception {
        String normalJsp = new org.leo.jmg.jsp.http.JspServer().wrap(
                "org.example.Core", new byte[]{1, 2, 3}, 200, "X-Test", "secret");
        String normalJspx = new org.leo.jmg.jsp.http.JspxServer().wrap(
                "org.example.Core", new byte[]{1, 2, 3}, 200, "X-Test", "secret");
        String jsp = new JspServer().wrap(
                "org.example.Core", new byte[]{1, 2, 3}, 200, "X-Test", "secret");
        String jspx = new JspxServer().wrap(
                "org.example.Core", new byte[]{1, 2, 3}, 200, "X-Test", "secret");

        assertTrue(jsp.contains("dataLen<0||dataLen>16777216"));
        assertTrue(jsp.contains("respData.length>16777216"));
        assertTrue(jsp.contains("readUnsignedByte()"));
        assertTrue(jsp.contains("readLong()"));
        assertTrue(jsp.contains("writeByte(responseType)"));
        assertTrue(jsp.contains("writeLong(transportId)"));
        assertTrue(jsp.contains("response.setStatus(200)"));
        assertTrue(jsp.contains("if (!\"POST\".equals(request.getMethod()) ||"));
        assertTrue(jsp.contains("response.setContentType(\"text/html;charset=UTF-8\")"));
        assertTrue(jsp.contains("response.sendError(404)"));
        assertTrue(jsp.contains("request.getHeader(\"X-Test\")"));
        assertTrue(jsp.contains("secret"));
        assertTrue(jsp.contains("java.lang.reflect.InvocationHandler"));
        assertTrue(jsp.contains("invoke(null, null"));
        assertFalse(jsp.contains(".equals(byteArrayOutputStream)"));
        assertFalse(jsp.contains("heartbeat"));
        assertTrue(jspx.contains("dataLen &lt; 0 || dataLen &gt; 16777216"));
        assertTrue(jspx.contains("respData.length &gt; 16777216"));
        assertTrue(jspx.contains("readUnsignedByte()"));
        assertTrue(jspx.contains("writeLong(transportId)"));
        assertFalse(jspx.contains("heartbeat"));
        assertTrue(jspx.contains("if (!\"POST\".equals(request.getMethod()) ||"));
        assertTrue(jspx.contains("response.setContentType(\"text/html;charset=UTF-8\")"));
        assertTrue(jspx.contains("response.sendError(404)"));
        assertTrue(jspx.contains("request.getHeader(\"X-Test\")"));
        assertTrue(jspx.contains("secret"));
        assertTrue(jspx.contains("java.lang.reflect.InvocationHandler"));
        assertTrue(jspx.contains("invoke(null, null"));
        assertFalse(jspx.contains(".equals(byteArrayOutputStream)"));
        assertTrue(normalJsp.contains("request.getHeader(\"X-Test\")"));
        assertTrue(normalJspx.contains("request.getHeader(\"X-Test\")"));
        assertTrue(normalJspx.contains("response.sendError(404)"));
    }

    @Test
    void chunkedTemplatesRejectBodylessResponseStatuses() {
        assertThrows(IllegalArgumentException.class,
                () -> new JspServer().wrap("org.example.Core", new byte[]{1}, 204));
        assertThrows(IllegalArgumentException.class,
                () -> new JspxServer().wrap("org.example.Core", new byte[]{1}, 304));
    }

    @Test
    void packagedWebSocketTemplateContainsFragmentValidation() throws Exception {
        String resource = "org/leo/jmg/mem/shell/http/LeoWebSocketTpl.class";
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            assertTrue(input != null, "WebSocket template resource should exist");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            String constants = new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
            assertTrue(constants.contains("invalid frame metadata"));
            assertTrue(constants.contains("fragment sequence mismatch"));
            assertTrue(constants.contains("response exceeds message limit"));
        }
    }
}
