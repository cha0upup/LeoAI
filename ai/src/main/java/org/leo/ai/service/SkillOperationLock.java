package org.leo.ai.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Per-skill lock shared by all skill write/read-modify-write paths. */
@Component
public class SkillOperationLock {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock lockFor(String scope, String name) {
        // Keep identity stable: another operation may already hold a reference before locking.
        return locks.computeIfAbsent(scope + "/" + name, ignored -> new ReentrantLock());
    }
}
