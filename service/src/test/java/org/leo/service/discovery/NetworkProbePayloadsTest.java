package org.leo.service.discovery;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.leo.service.discovery.NetworkProbePayloads.copyMap;
import static org.leo.service.discovery.NetworkProbePayloads.copyMapOrEmpty;

class NetworkProbePayloadsTest {

    @Test
    @SuppressWarnings("unchecked")
    void snapshotsRemainIsolatedFromLaterNestedChangesInEitherDirection() {
        var evidence = new LinkedHashMap<String, Object>(Map.of("statusCode", 200));
        var observations = new ArrayList<>(List.of(new LinkedHashMap<String, Object>(Map.of("evidence", evidence))));
        var source = new LinkedHashMap<String, Object>(Map.of("observations", observations));

        var snapshot = copyMap(source);
        var copiedObservations = (List<Map<String, Object>>) snapshot.get("observations");
        var copiedEvidence = (Map<String, Object>) copiedObservations.get(0).get("evidence");
        evidence.put("statusCode", 500);
        observations.add(new LinkedHashMap<>(Map.of("error", "timeout")));
        source.put("status", "RUNNING");

        assertEquals(1, copiedObservations.size());
        assertEquals(200, copiedEvidence.get("statusCode"));
        assertFalse(snapshot.containsKey("status"));
        copiedEvidence.put("body", "captured response");
        copiedObservations.clear();
        assertFalse(evidence.containsKey("body"));
        assertEquals(2, observations.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesOrderedSetsAndCopiesContainersInsideThem() {
        var attributes = new LinkedHashMap<String, Object>(Map.of("protocol", "http"));
        var members = new LinkedHashSet<Object>(Arrays.asList("web", attributes, null));
        var pending = new ArrayDeque<>(List.of("target-a", "target-b"));

        var snapshot = copyMap(Map.of("tags", members, "pending", pending));
        var copiedMembers = assertInstanceOf(Set.class, snapshot.get("tags"));
        assertEquals(Arrays.asList("web", Map.of("protocol", "http"), null), new ArrayList<>(copiedMembers));
        var copiedAttributes = (Map<String, Object>) copiedMembers.stream().filter(Map.class::isInstance).findFirst().orElseThrow();
        copiedAttributes.put("protocol", "https");
        copiedMembers.clear();
        pending.clear();

        assertEquals("http", attributes.get("protocol"));
        assertEquals(3, members.size());
        assertEquals(List.of("target-a", "target-b"), snapshot.get("pending"));
    }

    @Test
    void normalizesWireKeysWithoutDroppingNullValuesAndReturnsFreshEmptyMaps() {
        var source = new LinkedHashMap<Object, Object>();
        source.put(null, "ignored");
        source.put(7, "first");
        source.put("7", "last");
        source.put("nullable", null);
        source.put("nested", Map.of(42, "answer"));

        var snapshot = copyMap(source);
        assertEquals(List.of("7", "nullable", "nested"), new ArrayList<>(snapshot.keySet()));
        assertEquals("last", snapshot.get("7"));
        assertNull(snapshot.get("nullable"));
        assertEquals(Map.of("42", "answer"), snapshot.get("nested"));
        assertEquals(snapshot, copyMapOrEmpty(source));

        for (Object invalid : Arrays.asList(null, "not a map", List.of())) {
            var empty = copyMapOrEmpty(invalid);
            assertEquals(Map.of(), empty);
            empty.put("status", "pending");
            assertEquals(Map.of(), copyMapOrEmpty(invalid));
        }
    }
}
