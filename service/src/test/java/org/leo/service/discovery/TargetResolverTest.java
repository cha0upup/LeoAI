package org.leo.service.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.service.discovery.NetworkDiscoveryDtos.ResolvedTarget;
import org.leo.service.discovery.NetworkDiscoveryDtos.TargetInput;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TargetResolverTest {

    private final TargetResolver resolver = new TargetResolver();

    @Test
    void resolvesIpv6LiteralWithoutTreatingTheLastSegmentAsAPort() {
        List<ResolvedTarget> targets = resolver.resolve(new TargetInput(
                List.of("2001:db8::1"), List.of()));

        assertEquals(1, targets.size());
        assertNull(targets.get(0).port());
        assertEquals("tcp", targets.get(0).protocol());
        assertFalse(targets.get(0).ip().isBlank());
    }

    @Test
    void resolvesBracketedIpv6Port() {
        List<ResolvedTarget> targets = resolver.resolve(new TargetInput(
                List.of("[2001:db8::1]:8443"), List.of()));

        assertEquals(1, targets.size());
        assertEquals(8443, targets.get(0).port());
        assertEquals("tcp", targets.get(0).protocol());
        assertEquals("2001:db8::1:8443", targets.get(0).rawTarget());
    }

    @Test
    void normalizesIpv4CidrToTheNetworkAddress() {
        List<ResolvedTarget> targets = resolver.resolve(new TargetInput(
                List.of("192.168.1.9/30"), List.of()));

        assertEquals(4, targets.size());
        assertEquals("192.168.1.8", targets.get(0).ip());
        assertEquals("192.168.1.11", targets.get(3).ip());
        assertEquals("192.168.1.9/30", targets.get(0).targetId());
    }

    @Test
    void expandsSmallIpv6Cidr() {
        List<ResolvedTarget> targets = resolver.resolve(new TargetInput(
                List.of("2001:db8::/126"), List.of()));

        assertEquals(4, targets.size());
        assertEquals(4, targets.stream().map(ResolvedTarget::ip).distinct().count());
        assertNull(targets.get(0).port());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.0.1:8080", "https://127.0.0.1:8443/app/?a=1"})
    void preservesHostTargetIdentityAndMetadata(String input) {
        var target = resolver.resolve(new TargetInput(List.of(input), List.of())).get(0);
        assertEquals(UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8)).toString(), target.targetId());
        assertEquals(input, target.rawTarget());
        assertEquals("127.0.0.1", target.host());
        assertEquals("127.0.0.1", target.ip());
        assertEquals(input.startsWith("https") ? "url" : "manual", target.source());
        assertEquals(input.startsWith("https") ? "https" : "tcp", target.protocol());
    }

    @Test
    void excludesExpandedAddressesWithoutLosingApplicationTargets() {
        var targets = resolver.resolve(new TargetInput(
                List.of("192.0.2.0/30", "192.0.2.2-192.0.2.5", "http://192.0.2.4/app/"),
                List.of("192.0.2.1", "192.0.2.3/32", "192.0.2.5-192.0.2.5")));
        assertEquals(List.of("192.0.2.0", "192.0.2.2", "192.0.2.4", "192.0.2.4"), targets.stream().map(ResolvedTarget::ip).toList());
        assertEquals(List.of("cidr", "cidr", "range", "url"), targets.stream().map(ResolvedTarget::source).toList());
        assertEquals("192.0.2.2-192.0.2.5", targets.get(2).rawTarget());
    }
}
