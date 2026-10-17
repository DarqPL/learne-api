package com.learne.backend.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.lettuce.core.RedisURI;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Standalone test JVM: owns exactly one service, transport and registry. No Spring/main endpoints. */
public final class CapacityWorker {
    public static void main(String[] args) throws Exception {
        var done = new CountDownLatch(1);
        // Only this safety observer is daemonized; request executors must really terminate.
        var parent = ProcessHandle.of(Long.parseLong(args[4]));
        Thread.ofPlatform().daemon(true).name("capacity-parent-watchdog").start(() -> {
            long shutdownDeadline = Long.MAX_VALUE;
            while (true) {
                boolean orphaned = parent.isEmpty() || !parent.get().isAlive();
                if (orphaned) done.countDown();
                if (done.getCount() == 0 && shutdownDeadline == Long.MAX_VALUE)
                    shutdownDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(orphaned ? 1 : 3);
                // Hard fallback also covers a stuck transport/server shutdown after parent death.
                if (System.nanoTime() >= shutdownDeadline) Runtime.getRuntime().halt(2);
                try { Thread.sleep(250); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        });
        var executor = Executors.newFixedThreadPool(8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        server.setExecutor(executor);
        var registry = new SimpleMeterRegistry();
        try (var transport = new LimiterRedisConnection(RedisURI.Builder.redis(args[0], Integer.parseInt(args[1])).build());
                var metrics = new RateLimitMetrics(registry, CapacityPolicies.PROPERTIES.policies().keySet(), true)) {
            var store = new RedisTokenBucketStore(transport);
            var service = new RateLimitService(CapacityPolicies.PROPERTIES,
                    new RateLimitKeyFactory(CapacityPolicies.PROPERTIES), () -> store, metrics);
            server.createContext("/", exchange -> {
                String response;
                try {
                    String path = exchange.getRequestURI().getPath();
                    if (path.equals("/stop")) {
                        response = "stopping";
                    } else if (path.equals("/warm")) {
                        service.consume(CapacityPolicies.DEPLETED, RateLimitDimension.USER, args[3]);
                        // Exercise crypto, metrics, script and the established connection before timing.
                        for (int i = 0; i < 200; i++) service.consume(CapacityPolicies.DEPLETED, RateLimitDimension.USER, args[3]);
                        response = "warm";
                    } else if (path.equals("/seed")) {
                        for (int i = 1; i <= 10000; i++) {
                            if (service.consume(CapacityPolicies.DEPLETED, RateLimitDimension.USER,
                                    Integer.toString(i)).outcome() != RateLimitDecision.Outcome.ALLOWED) {
                                throw new AssertionError("seed consume failed");
                            }
                        }
                        response = "seeded";
                    } else {
                        int index = Integer.parseInt(exchange.getRequestURI().getQuery());
                        boolean allowed = CapacityMeasurements.allowed(index);
                        String policy = allowed ? CapacityPolicies.ALLOWED : CapacityPolicies.DEPLETED;
                        String user = CapacityMeasurements.user(index);
                        String outcome;
                        long start = System.nanoTime();
                        try {
                            outcome = service.consume(policy, RateLimitDimension.USER, user).outcome().name();
                        } catch (RuntimeException failure) {
                            outcome = "UNAVAILABLE";
                        }
                        long elapsed = System.nanoTime() - start;
                        response = outcome + "," + elapsed;
                    }
                } catch (Throwable failure) {
                    response = "ERROR,0"; // No exception messages, inputs or credentials in IPC evidence.
                }
                byte[] bytes = response.getBytes(StandardCharsets.US_ASCII);
                try (exchange) {
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally {
                    if (exchange.getRequestURI().getPath().equals("/stop")) done.countDown();
                }
            });
            server.start();
            Path ready = Path.of(args[2]);
            Path temporary = ready.resolveSibling(ready.getFileName() + ".tmp");
            new ObjectMapper().writeValue(temporary.toFile(), Map.of(
                    "pid", ProcessHandle.current().pid(), "os", System.getProperty("os.name"),
                    "java", System.getProperty("java.runtime.version"), "port", server.getAddress().getPort()));
            Files.move(temporary, ready, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            try { done.await(); }
            finally {
                try (var cleanup = new CapacityCleanup()) {
                    cleanup.step(() -> server.stop(0));
                    cleanup.step(() -> executor.shutdownNow());
                    cleanup.step(() -> {
                        if (!cleanup.await(n -> executor.awaitTermination(n, TimeUnit.NANOSECONDS), java.time.Duration.ofSeconds(1)))
                            throw new AssertionError("Worker HTTP handlers did not terminate");
                    });
                }
            }
        } finally {
            server.stop(0);
            executor.shutdownNow();
            registry.close();
        }
    }
}
