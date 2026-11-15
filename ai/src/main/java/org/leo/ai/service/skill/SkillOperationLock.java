package org.leo.ai.service.skill;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

/** Shared skill locks with bounded storage and one acquisition order for batch operations. */
@Component
public class SkillOperationLock {

    private static final int LOCK_COUNT = 256;
    private final ReentrantLock[] locks = IntStream.range(0, LOCK_COUNT)
            .mapToObj(ignored -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    public ReentrantLock lockFor(String scope, String name) {
        return locks[stripeIndex(scope, name)];
    }

    /** Hash collisions share a lock; acquire each stripe once in the same order across all batches. */
    public List<ReentrantLock> locksFor(String scope, Collection<String> names) {
        return names.stream()
                .sorted(Comparator.comparingInt(name -> stripeIndex(scope, name)))
                .map(name -> lockFor(scope, name))
                .distinct()
                .toList();
    }

    private static int stripeIndex(String scope, String name) {
        return (scope + "/" + name).hashCode() & (LOCK_COUNT - 1);
    }
}
