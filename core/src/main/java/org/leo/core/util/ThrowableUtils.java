package org.leo.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Cause traversal shared by error classification, recovery and diagnostic paths. */
public final class ThrowableUtils {

    private ThrowableUtils() {}

    /** Outermost first; each exception instance is visited once, even when causes form a cycle. */
    public static List<Throwable> causes(Throwable error) {
        List<Throwable> causes = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && visited.add(current); current = current.getCause()) {
            causes.add(current);
        }
        return List.copyOf(causes);
    }

    /** Returns the deepest distinct cause, or null when no error was supplied. */
    public static Throwable rootCause(Throwable error) {
        List<Throwable> causes = causes(error);
        return causes.isEmpty() ? null : causes.get(causes.size() - 1);
    }

    /** Removes Java asynchronous wrappers while preserving the first domain exception. */
    public static Throwable unwrapCompletion(Throwable error) {
        Throwable result = error;
        for (Throwable cause : causes(error)) {
            result = cause;
            if (!(cause instanceof CompletionException || cause instanceof ExecutionException)) break;
        }
        return result;
    }
}
