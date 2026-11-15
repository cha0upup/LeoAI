package org.leo.ai.service.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SkillOperationLockTest {

    private final SkillOperationLock locks = new SkillOperationLock();
    private final List<String> names = IntStream.range(0, 1024).mapToObj(i -> "skill-" + i).toList();

    @Test
    void lockStorageIsBoundedAndExistingReferencesKeepTheirIdentity() {
        ReentrantLock first = locks.lockFor("agent-node", "first");
        Set<ReentrantLock> resolved = Collections.newSetFromMap(new IdentityHashMap<>());
        resolved.add(first);
        for (String scope : List.of("agent-node", "platform")) {
            for (String name : names) resolved.add(locks.lockFor(scope, name));
        }

        assertTrue(resolved.size() <= 256);
        assertSame(first, locks.lockFor("agent-node", "first"));
    }

    @Test
    void batchOrderIsIndependentOfRequestOrderAndDuplicateOrCollidingNames() {
        List<String> requested = new ArrayList<>(names);
        requested.addAll(names);
        Collections.reverse(requested);

        List<ReentrantLock> batch = locks.locksFor("agent-node", requested);

        assertTrue(batch.size() < names.size());
        assertEquals(new HashSet<>(batch).size(), batch.size());
        assertEquals(locks.locksFor("agent-node", names), batch);
        assertEquals(new HashSet<>(names.stream().map(name -> locks.lockFor("agent-node", name)).toList()),
                new HashSet<>(batch));
        assertTrue(locks.locksFor("agent-node", List.of()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 5, 7})
    void overlappingBatchesKeepTheSameGlobalOrderEvenWhenNamesAlias(int stride) {
        List<ReentrantLock> all = locks.locksFor("agent-node", names);
        Map<ReentrantLock, Integer> order = new IdentityHashMap<>();
        for (int index = 0; index < all.size(); index++) order.put(all.get(index), index);
        List<String> requested = new ArrayList<>(IntStream.range(0, names.size())
                .filter(index -> index % stride == 0).mapToObj(names::get).toList());
        Collections.reverse(requested);

        List<Integer> positions = locks.locksFor("agent-node", requested).stream().map(order::get).toList();

        assertEquals(positions.stream().sorted().toList(), positions);
    }
}
