package org.leo.core.net.impl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.leo.core.net.TransportException;
import org.leo.core.net.TransportLimits;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpCommunicationCompressionTest {

    @Test
    void decodesGzipAndDeflateResponses() throws Exception {
        byte[] expected = "transport-response".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gzip", exchange -> writeCompressed(exchange, expected, "gzip"));
        server.createContext("/deflate", exchange -> writeCompressed(exchange, expected, "deflate"));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpCommunication communication =
                    new HttpCommunication(base + "/gzip", "POST", null, Proxy.NO_PROXY);
            assertEquals("transport-response",
                    new String(communication.sendRequest(new byte[0]), StandardCharsets.UTF_8));

            communication.setRequestUrl(base + "/deflate");
            assertEquals("transport-response",
                    new String(communication.sendRequest(new byte[0]), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsOversizedRequestBeforeOpeningConnection() throws Exception {
        HttpCommunication communication = new HttpCommunication(
                "http://127.0.0.1:1/unreachable", "POST", null, Proxy.NO_PROXY);
        TransportException error = assertThrows(TransportException.class,
                () -> communication.sendRequest(new byte[TransportLimits.MAX_MESSAGE_BYTES + 1]));
        assertEquals(TransportException.Reason.MESSAGE_TOO_LARGE, error.getReason());
    }

    @Test
    void rejectsOversizedResponse() throws Exception {
        byte[] response = new byte[TransportLimits.MAX_MESSAGE_BYTES + 1];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            HttpCommunication communication = new HttpCommunication(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/large",
                    "POST", null, Proxy.NO_PROXY);
            TransportException error = assertThrows(TransportException.class,
                    () -> communication.sendRequest(new byte[0]));
            assertEquals(TransportException.Reason.MESSAGE_TOO_LARGE, error.getReason());
        } finally {
            server.stop(0);
        }
    }

    private static void writeCompressed(HttpExchange exchange, byte[] body, String encoding)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OutputStream compressor = "gzip".equals(encoding)
                ? new GZIPOutputStream(output)
                : new DeflaterOutputStream(output);
        compressor.write(body);
        compressor.close();
        byte[] compressed = output.toByteArray();
        exchange.getResponseHeaders().set("Content-Encoding", encoding);
        exchange.sendResponseHeaders(200, compressed.length);
        try (OutputStream response = exchange.getResponseBody()) {
            response.write(compressed);
        }
    }
}
