package org.leo.core.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class ThrowableUtilsTest {

    @Test
    void missingErrorHasNoCausesOrRoot() {
        assertTrue(ThrowableUtils.causes(null).isEmpty());
        assertNull(ThrowableUtils.rootCause(null));
        assertNull(ThrowableUtils.unwrapCompletion(null));
    }

    @Test
    void preservesCauseOrderAndTheDeepestException() {
        Throwable root = new IllegalArgumentException("root");
        Throwable middle = new IllegalStateException("middle", root);
        Throwable outer = new RuntimeException("outer", middle);

        assertEquals(List.of(outer, middle, root), ThrowableUtils.causes(outer));
        assertSame(root, ThrowableUtils.rootCause(outer));
    }

    @Test
    void cyclicCausesStopAtTheLastDistinctException() {
        Throwable first = new RuntimeException("first");
        Throwable second = new IllegalStateException("second");
        first.initCause(second);
        second.initCause(first);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertEquals(List.of(first, second), ThrowableUtils.causes(first));
            assertSame(second, ThrowableUtils.rootCause(first));
        });
    }

    @Test
    void exceptionEqualityDoesNotHideDistinctCauses() {
        Throwable inner = new ValueEqualException();
        Throwable outer = new ValueEqualException();
        outer.initCause(inner);

        List<Throwable> causes = ThrowableUtils.causes(outer);

        assertEquals(2, causes.size());
        assertSame(outer, causes.get(0));
        assertSame(inner, causes.get(1));
    }

    @Test
    void unwrapsOnlyAsynchronousWrappersAndKeepsDomainCausesIntact() {
        Throwable nested = new IllegalArgumentException("nested");
        Throwable domain = new IllegalStateException("domain", nested);
        Throwable wrapped = new CompletionException(new ExecutionException(domain));
        Throwable emptyWrapper = new CompletionException("no cause", null);

        assertSame(domain, ThrowableUtils.unwrapCompletion(wrapped));
        assertSame(domain, ThrowableUtils.unwrapCompletion(domain));
        assertSame(emptyWrapper, ThrowableUtils.unwrapCompletion(emptyWrapper));
    }

    @Test
    void asynchronousWrapperCyclesAlsoTerminate() {
        class Wrapper extends CompletionException {
            Wrapper() { super("wrapper"); }
        }
        Throwable first = new Wrapper();
        Throwable second = new Wrapper();
        first.initCause(second);
        second.initCause(first);

        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertSame(second, ThrowableUtils.unwrapCompletion(first)));
    }

    private static final class ValueEqualException extends RuntimeException {
        @Override
        public boolean equals(Object other) { return other instanceof ValueEqualException; }

        @Override
        public int hashCode() { return 1; }
    }
}
