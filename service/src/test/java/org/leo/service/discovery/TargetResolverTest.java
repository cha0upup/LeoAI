package org.leo.service.discovery;

import org.junit.jupiter.api.Test;
import org.leo.service.discovery.NetworkDiscoveryDtos.ResolvedTarget;
import org.leo.service.discovery.NetworkDiscoveryDtos.TargetInput;

import java.util.List;

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
    }

    @Test
    void normalizesIpv4CidrToTheNetworkAddress() {
        List<ResolvedTarget> targets = resolver.resolve(new TargetInput(
                List.of("192.168.1.9/30"), List.of()));

        assertEquals(4, targets.size());
        assertEquals("192.168.1.8", targets.get(0).ip());
        assertEquals("192.168.1.11", targets.get(3).ip());
    }

    @Test
    void expandsSmallIpv6Cidr() {
        List<ResolvedTarget> targets = resolver.resolve(new TargetInput(
                List.of("2001:db8::/126"), List.of()));

        assertEquals(4, targets.size());
        assertEquals(4, targets.stream().map(ResolvedTarget::ip).distinct().count());
        assertNull(targets.get(0).port());
    }
}
