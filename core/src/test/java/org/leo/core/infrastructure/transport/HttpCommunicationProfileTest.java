package org.leo.core.infrastructure.transport;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpCommunicationProfileTest {

    @Test
    void explicitHeadersWinOverOneShotSessionProfile() throws Exception {
        AtomicReference<String> userAgent = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/profile", exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(new byte[]{'o', 'k'});
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/profile";
            HttpCommunication communication = new HttpCommunication(
                    url, "POST", Map.of("User-Agent", "configured-agent"), Proxy.NO_PROXY);
            communication.setRequestProfileHeaders(Map.of(
                    "User-Agent", "profile-agent", "Accept", "application/json"));
            communication.sendRequest(new byte[0]);

            assertEquals("configured-agent", userAgent.get());
            assertEquals("application/json", accept.get());
        } finally {
            server.stop(0);
        }
    }
}
