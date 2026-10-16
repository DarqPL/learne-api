package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.resource.DefaultClientResources;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.test.util.ReflectionTestUtils;

/** Test-only bounded acknowledgement of the production transport's asynchronous close. */
final class LimiterTestCleanup {
    private LimiterTestCleanup() { }

    static void closeAndAwait(LimiterRedisConnection transport) throws InterruptedException {
        var connector = ReflectionTestUtils.getField(transport, "connector");
        var resources = (DefaultClientResources) ReflectionTestUtils.getField(connector, "resources");
        var current = (AtomicReference<?>) ReflectionTestUtils.getField(transport, "current");
        var permits = (Semaphore) ReflectionTestUtils.getField(LimiterRedisConnection.class, "CONNECTIONS");
        transport.close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (current.get() != null || permits.availablePermits() != 1
                || !resources.eventExecutorGroup().isTerminated()) {
            assertThat(System.nanoTime()).as("transport shutdown and connection lease release within five seconds")
                    .isLessThan(deadline);
            Thread.sleep(10);
        }
    }
}
