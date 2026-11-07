package org.leo.service.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServiceIdentificationPolicyTest {
    @ParameterizedTest
    @CsvSource({"SSH-2.0-OpenSSH,ssh", "220 FTP ready,ftp", "MariaDB ready,mysql",
            "redis ready,redis", "MongoDB ready,mongodb", "PostgreSQL ready,postgresql",
            "220 SMTP ready,smtp", "HTTP/1.1 200 OK,http", "Server: fixture,http"})
    void identifiesProtocolsWithoutSchedulingOrPersistence(String banner, String expected) {
        assertEquals(expected, ServiceIdentificationPolicy.detectService(banner));
    }

    @Test void ignoresEmptyAndUnknownBanners() {
        assertEquals("", ServiceIdentificationPolicy.detectService(null));
        assertEquals("", ServiceIdentificationPolicy.detectService("unknown fixture"));
    }

    @Test void retainsLiveCandidatesAndSkipsIdentifiedProtocols() {
        var unknown = new LinkedHashMap<String, Object>(Map.of("port", 8080));
        var tls = new LinkedHashMap<String, Object>(Map.of("port", 443));
        var ssh = new LinkedHashMap<String, Object>(Map.of("port", 22, "service", "SSH"));
        var http = new LinkedHashMap<String, Object>(Map.of("port", 80, "service", "http"));
        var candidates = ServiceIdentificationPolicy.httpCandidates(List.of(unknown, tls, ssh, http));
        assertEquals(List.of(unknown, tls), candidates);
        assertSame(unknown, candidates.get(0));
        assertEquals(List.of(unknown), ServiceIdentificationPolicy.httpsCandidates(candidates));
        unknown.put("service", "http");
        assertTrue(ServiceIdentificationPolicy.httpsCandidates(candidates).isEmpty());
    }

    @Test void mergesHttpEvidenceWithoutSharingTheIncomingEvidenceMap() {
        var endpoint = new LinkedHashMap<String, Object>(Map.of("host", "fixture", "port", 8080));
        var evidence = new LinkedHashMap<String, Object>(Map.of("statusCode", 200, "server", "fixture",
                "title", "fixture title", "banner", "HTTP/1.1 200 OK"));
        ServiceIdentificationPolicy.applyServiceObservation(endpoint, Map.of("stage", "http-head",
                "protocol", "https", "evidence", evidence));
        assertEquals("https", endpoint.get("service"));
        assertEquals("https", endpoint.get("protocol"));
        assertEquals(200, endpoint.get("statusCode"));
        assertEquals("fixture title", endpoint.get("title"));
        assertEquals(true, endpoint.get("serviceIdentified"));
        assertEquals(0.9, endpoint.get("confidence"));
        evidence.clear();
        assertEquals(200, ((Map<?, ?>) endpoint.get("evidence")).get("statusCode"));
    }

    @Test void invalidHttpStatusDoesNotInventAServiceOrDiscardErrors() {
        var endpoint = new LinkedHashMap<String, Object>(Map.of("port", 8080));
        ServiceIdentificationPolicy.applyServiceObservation(endpoint, Map.of("stage", "http-head",
                "evidence", Map.of("statusCode", 600), "error", "fixture timeout"));
        assertFalse(endpoint.containsKey("service"));
        assertFalse(endpoint.containsKey("serviceIdentified"));
        assertEquals("fixture timeout", endpoint.get("probeError"));
    }

    @Test void preservesBannerIdentificationAndTlsAndIpv6UrlConventions() {
        var endpoint = new LinkedHashMap<String, Object>(Map.of("port", 22));
        ServiceIdentificationPolicy.applyServiceObservation(endpoint,
                Map.of("stage", "tcp-exchange", "evidence", Map.of("banner", "SSH-2.0-OpenSSH")));
        assertEquals("ssh", endpoint.get("service"));
        assertEquals(0.7, endpoint.get("confidence"));
        assertEquals("https", ServiceIdentificationPolicy.httpProbeScheme(443));
        assertEquals("https", ServiceIdentificationPolicy.httpProbeScheme(8443));
        assertEquals("http", ServiceIdentificationPolicy.httpProbeScheme(8080));
        assertEquals("[::1]", ServiceIdentificationPolicy.hostForUrl("::1"));
        assertEquals("[::1]", ServiceIdentificationPolicy.hostForUrl("[::1]"));
        assertEquals("fixture", ServiceIdentificationPolicy.hostForUrl("fixture"));
    }
}
