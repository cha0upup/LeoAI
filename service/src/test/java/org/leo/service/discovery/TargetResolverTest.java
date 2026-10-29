package org.leo.service.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.service.discovery.NetworkDiscoveryDtos.ResolvedTarget;
import org.leo.service.discovery.NetworkDiscoveryDtos.TargetInput;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @ParameterizedTest
    @CsvSource({
            "192.168.1.9/30,192.168.1.8,192.168.1.11,4,cidr",
            "192.168.1.9-192.168.1.11,192.168.1.9,192.168.1.11,3,range",
            "255.255.255.255/32,255.255.255.255,255.255.255.255,1,cidr",
            "255.255.255.254-255.255.255.255,255.255.255.254,255.255.255.255,2,range"})
    void expandsIpv4Intervals(String input, String first, String last, int count, String source) {
        List<ResolvedTarget> targets = resolver.resolve(new TargetInput(
                List.of(input), List.of()));

        assertEquals(count, targets.size());
        assertEquals(first, targets.get(0).ip());
        assertEquals(last, targets.get(count - 1).ip());
        assertEquals(input, targets.get(0).targetId());
        assertEquals(source, targets.get(0).source());
    }

    @ParameterizedTest
    @CsvSource({
            "2001:db8::9/126,2001:db8:0:0:0:0:0:8,2001:db8:0:0:0:0:0:b,4",
            "2001:db8::1/128,2001:db8:0:0:0:0:0:1,2001:db8:0:0:0:0:0:1,1",
            "ffff:ffff:ffff:ffff:ffff:ffff:ffff:fffe/127,ffff:ffff:ffff:ffff:ffff:ffff:ffff:fffe,ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff,2"})
    void normalizesAndExpandsIpv6Cidr(String input, String first, String last, int count) {
        List<ResolvedTarget> targets = resolver.resolve(new TargetInput(
                List.of(input), List.of()));

        assertEquals(count, targets.size());
        assertEquals(first, targets.get(0).ip());
        assertEquals(last, targets.get(count - 1).ip());
        assertEquals(count, targets.stream().map(ResolvedTarget::ip).distinct().count());
        assertNull(targets.get(0).port());
        assertEquals(input, targets.get(0).rawTarget());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0/0", "2001:db8::/0", "0.0.0.0-255.255.255.255"})
    void rejectsRangesThatCannotBeRepresentedByJavaListIndices(String input) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(new TargetInput(List.of(input), List.of())));
        assertTrue(error.getMessage().contains("索引容量"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"198.18.0.0/15", "198.18.0.0-198.19.255.255", "2001:db8::/111"})
    void resolvesMoreThanOneHundredThousandAddressesWithoutATaskQuota(String input) {
        var targets = resolver.resolve(new TargetInput(List.of(input), List.of()));
        assertEquals(131072, targets.size());
        assertFalse(targets.get(targets.size() - 1).ip().isBlank());
    }

    @Test
    void acceptsLargeInputAndExclusionListsWithoutTruncation() {
        List<String> items = java.util.stream.IntStream.range(0, 400)
                .mapToObj(index -> "198.18." + (index / 256) + "." + (index % 256)).toList();
        var targets = resolver.resolve(new TargetInput(items, items.subList(0, 200)));
        assertEquals(200, targets.size());
        assertEquals(items.subList(200, 400), targets.stream().map(ResolvedTarget::ip).toList());
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
