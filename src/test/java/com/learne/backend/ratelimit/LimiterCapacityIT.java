package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;

import com.learne.backend.cache.CapacityCacheFixture;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in destructive fixture, always in RedisTestSupport's new isolated 128-MiB container. */
@EnabledIfSystemProperty(named = "redis.capacity.enabled", matches = "true")
class LimiterCapacityIT extends RedisTestSupport {
    private static final long MIB = 1024L * 1024;
    record Sample(int index, int worker, String expected, String outcome, long serviceNanos,
            long dispatchLagNanos, long ipcRoundTripNanos, long offeredOffsetNanos) { }

    @Test
    void twoIndependentJvmCapacityWithCacheHeadroom() throws Exception {
        Path directory = Path.of("target", "ratelimit-capacity", System.getProperty("os.name").replace(' ', '-')
                + "-" + Instant.now().toString().replace(':', '-'));
        Files.createDirectories(directory);
        var report = new LinkedHashMap<String, Object>();
        var gates = new LinkedHashMap<String, Boolean>();
        var memory = Collections.synchronizedList(new ArrayList<Map<String, Long>>());
        var monitorErrors = Collections.synchronizedList(new ArrayList<String>());
        var monitorTimes = Collections.synchronizedList(new ArrayList<Long>());
        var samples = new Sample[6000];
        boolean workloadQuiescent = true; // No asynchronous measurement writers yet.
        report.put("startedAt", Instant.now().toString());
        report.put("parentPid", ProcessHandle.current().pid());
        report.put("parentOs", System.getProperty("os.name"));
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("architecture", System.getProperty("os.arch"));
        report.put("host", System.getProperty("capacity.host", "Windows host / Docker Desktop Linux guest; not native VPS acceptance"));
        report.put("nativeLinuxAcceptance", "pending: this run is not evidence of native Linux/VPS capacity");
        report.put("parentAvailableProcessors", Runtime.getRuntime().availableProcessors());
        report.put("parentMaxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("workerJvmFlags", List.of("-Xmx128m", "-XX:ActiveProcessorCount=2", "-Dio.netty.eventLoopThreads=2"));
        report.put("mavenVersion", System.getProperty("maven.version", "see Maven command output"));
        report.put("mavenImage", System.getProperty("capacity.maven.image", "Windows Maven wrapper (no Maven container)"));
        report.put("redisImage", "redis:7-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499");
        report.put("lettuceVersion", io.lettuce.core.RedisClient.class.getPackage().getImplementationVersion());
        report.put("testcontainersVersion", org.testcontainers.DockerClientFactory.class.getPackage().getImplementationVersion());
        report.put("gates", gates);
        report.put("memorySnapshots", memory);
        report.put("monitorErrors", monitorErrors);
        report.put("samples", samples);
        try (var observer = redisClient.connect();
                var first = new CapacityFork(redisUri().getHost(), redisUri().getPort(), directory, 0);
                var second = new CapacityFork(redisUri().getHost(), redisUri().getPort(), directory, 1)) {
            var workers = List.of(first, second);
            report.put("workers", workers.stream().map(w -> w.metadata).toList());
            gates.put("twoIndependentPids", !first.metadata.get("pid").equals(second.metadata.get("pid"))
                    && workers.stream().noneMatch(w -> ((Number) w.metadata.get("pid")).longValue() == ProcessHandle.current().pid()));
            report.put("linuxRuntime", System.getProperty("os.name").equals("Linux")
                    && workers.stream().allMatch(w -> w.metadata.get("os").equals("Linux")));
            var commands = observer.sync();
            report.put("redisVersion", field(commands.info("server"), "redis_version"));
            gates.put("redisVersion", "7.4.11".equals(field(commands.info("server"), "redis_version")));
            gates.put("redisConfiguration", "67108864".equals(commands.configGet("maxmemory").get("maxmemory"))
                    && "noeviction".equals(commands.configGet("maxmemory-policy").get("maxmemory-policy")));
            assertThat(first.call("/warm")).isEqualTo("warm");
            assertThat(second.call("/warm")).isEqualTo("warm");
            // Warm the observer as well, then take the baseline before creating the 10k bucket cohort.
            commands.memoryUsage(CapacityPolicies.key(20001));
            Map<String, Long> baseline = snapshot(commands);
            report.put("baseline", baseline);
            assertThat(first.call("/seed")).isEqualTo("seeded");
            Map<String, Long> afterBuckets = snapshot(commands);
            long delta = afterBuckets.get("usedMemory") - baseline.get("usedMemory");
            report.put("after10000Buckets", afterBuckets);
            report.put("bucketDeltaBytes", delta);
            report.put("bucketDeltaBytesPerKey", delta / 10000.0);
            report.put("bucketKeyBytes", CapacityPolicies.key(1).getBytes(StandardCharsets.US_ASCII).length);
            var usage = new ArrayList<Long>();
            for (int i = 1; i <= 10000; i += 100) usage.add(commands.memoryUsage(CapacityPolicies.key(i)));
            report.put("bucketMemoryUsageSamplesBytes", usage);
            gates.put("bucketDeltaAtMost16MiB", delta > 0 && delta <= 16 * MIB);
            byte[] serialized = CapacityCacheFixture.value();
            assertThat(serialized).hasSize(16384);
            String value = new String(serialized, StandardCharsets.US_ASCII);
            for (int i = 0; i < 1024; i++) {
                String key = CapacityCacheFixture.key(i);
                assertThat(key.getBytes(StandardCharsets.US_ASCII)).hasSize(128);
                assertThat(value.getBytes(StandardCharsets.US_ASCII)).hasSize(16384); // Assert actual SET bytes.
                commands.setex(key, 600, value);
            }
            gates.put("cacheFixture", commands.strlen(CapacityCacheFixture.key(0)) == 16384
                    && commands.ttl(CapacityCacheFixture.key(1023)) > 590);
            report.put("cache", Map.of("keys", 1024, "keyBytes", 128, "serializedValueBytes", serialized.length,
                    "totalSerializedValueBytes", 1024L * serialized.length, "ttlSeconds", 600,
                    "serializer", "CacheConfig.redisValueSerializer (test-only package bridge)"));
            memory.add(snapshot(commands));
            var monitor = Executors.newSingleThreadScheduledExecutor();
            var dispatch = Executors.newVirtualThreadPerTaskExecutor();
            long started = System.nanoTime();
            var monitoringConnection = redisClient.connect();
            workloadQuiescent = false;
            try {
                monitor.scheduleWithFixedDelay(() -> {
                    try {
                        memory.add(snapshot(monitoringConnection.sync()));
                        monitorTimes.add(System.nanoTime() - started);
                    }
                    catch (RuntimeException failure) { monitorErrors.add(failure.getClass().getSimpleName()); }
                }, 0, 1, TimeUnit.SECONDS);
                for (int i = 0; i < 6000; i++) {
                    long target = started + CapacityMeasurements.offsetNanos(i);
                    long remaining;
                    while ((remaining = target - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
                    long offered = System.nanoTime();
                    int index = i;
                    // Only submission is on the scheduling thread; responses never delay later offers.
                    dispatch.submit(() -> {
                        long actualDispatch = System.nanoTime();
                        String outcome = "IPC_FAILURE";
                        long service = -1;
                        try {
                            String[] response = workers.get(index % 2).call("/consume?" + index).split(",");
                            outcome = response[0];
                            service = Long.parseLong(response[1]);
                        } catch (Exception failure) { /* Retain every failed sample, with an explicit outcome. */ }
                        samples[index] = new Sample(index, index % 2, CapacityMeasurements.allowed(index) ? "ALLOWED" : "REJECTED",
                                outcome, service, Math.max(0, actualDispatch - target),
                                System.nanoTime() - actualDispatch, offered - started);
                    });
                }
                dispatch.shutdown();
                assertThat(dispatch.awaitTermination(100, TimeUnit.SECONDS)).isTrue();
                // Observe the entire specified 60-second interval, including its final 10 ms.
                while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(60)) LockSupport.parkNanos(100_000);
            } finally {
                CapacityCleanup.closeWorkload(monitor, dispatch, () -> {
                    var closed = monitoringConnection.closeAsync();
                    try (var cleanup = new CapacityCleanup()) {
                        if (!cleanup.await(n -> {
                            try { closed.get(n, TimeUnit.NANOSECONDS); return true; }
                            catch (java.util.concurrent.TimeoutException timeout) { return false; }
                            catch (java.util.concurrent.ExecutionException failure) { throw new AssertionError("Monitor connection close failed", failure); }
                        }, java.time.Duration.ofSeconds(2))) throw new AssertionError("Monitor connection close timed out");
                    }
                }, () -> { monitoringConnection.closeAsync(); }); // Emergency I/O abort; not a successful join.
                workloadQuiescent = true; // Set only after both joins and normal connection close succeed.
            }
            memory.add(snapshot(commands));
            monitorTimes.add(System.nanoTime() - started);
            long elapsed = System.nanoTime() - started;
            report.put("elapsedSeconds", elapsed / 1e9);
            report.put("achievedCompletionRps", 6000 / (elapsed / 1e9));
            long lastDispatchOffset = java.util.Arrays.stream(samples).filter(s -> s != null)
                    .mapToLong(s -> CapacityMeasurements.offsetNanos(s.index()) + s.dispatchLagNanos()).max().orElseThrow();
            report.put("lastDispatchSeconds", lastDispatchOffset / 1e9);
            report.put("offeredRps", 100);
            report.put("workerRps", 50);
            report.put("scheduleIntervalNanos", 10_000_000);
            gates.put("6000DispatchedWithin61Seconds", java.util.Arrays.stream(samples).allMatch(s -> s != null)
                    && lastDispatchOffset >= CapacityMeasurements.offsetNanos(5999)
                    && lastDispatchOffset <= TimeUnit.SECONDS.toNanos(61));
            report.put("dispatchedCount", java.util.Arrays.stream(samples).filter(s -> s != null).count());
            report.put("unavailableCount", java.util.Arrays.stream(samples).filter(s -> s != null && s.outcome().equals("UNAVAILABLE")).count());
            report.put("incorrectOutcomeCount", java.util.Arrays.stream(samples).filter(s -> s == null || !s.expected().equals(s.outcome())).count());
            var workerCounts = new ArrayList<Map<String, Object>>();
            for (int worker = 0; worker < 2; worker++) {
                int instance = worker;
                long allowed = java.util.Arrays.stream(samples).filter(s -> s != null && s.worker() == instance && s.outcome().equals("ALLOWED")).count();
                long rejected = java.util.Arrays.stream(samples).filter(s -> s != null && s.worker() == instance && s.outcome().equals("REJECTED")).count();
                workerCounts.add(Map.of("worker", worker, "allowed", allowed, "rejected", rejected));
                gates.put("worker" + worker + "BalancedOutcomes", allowed == 1500 && rejected == 1500);
            }
            report.put("workerOutcomeCounts", workerCounts);
            gates.put("allCohortOutcomesCorrectZeroUnavailable", java.util.Arrays.stream(samples)
                    .allMatch(s -> s != null && s.expected().equals(s.outcome())));
            for (String cohort : List.of("ALLOWED", "REJECTED", "ALL")) {
                long[] times = java.util.Arrays.stream(samples).filter(s -> s != null)
                        .filter(s -> cohort.equals("ALL") || s.expected().equals(cohort))
                        .mapToLong(Sample::serviceNanos).toArray();
                report.put(cohort.toLowerCase() + "Service", distribution(times));
                if (!cohort.equals("ALL")) gates.put(cohort.toLowerCase() + "P95AtMost50ms",
                        times.length == 3000 && java.util.Arrays.stream(times).allMatch(t -> t >= 0)
                                && CapacityMeasurements.percentile(times, .95) <= 50_000_000);
            }
            report.put("ipcRoundTrip", distribution(java.util.Arrays.stream(samples).filter(s -> s != null).mapToLong(Sample::ipcRoundTripNanos).toArray()));
            report.put("parentDispatchLag", distribution(java.util.Arrays.stream(samples).filter(s -> s != null).mapToLong(Sample::dispatchLagNanos).toArray()));
            long maxUsed = memory.stream().mapToLong(m -> m.get("usedMemory")).max().orElseThrow();
            long maxRss = memory.stream().mapToLong(m -> m.get("rss")).max().orElseThrow();
            report.put("maxUsedMemoryBytes", maxUsed);
            report.put("maxRssBytes", maxRss);
            gates.put("usedAtMost48MiB", maxUsed <= 48 * MIB);
            gates.put("rssAtMost96MiB", maxRss <= 96 * MIB);
            report.put("monitorOffsetsNanos", monitorTimes);
            report.put("monitorMaxGapNanos", CapacityMeasurements.maxGapNanos(monitorTimes));
            gates.put("oneSecondMonitoring", monitorErrors.isEmpty()
                    && CapacityMeasurements.monitoringCovered(monitorTimes, elapsed));
            gates.put("noEvictionOrOom", memory.stream().allMatch(m -> m.get("evicted") == 0 && m.get("oom") == 0));
            long retained = 0;
            for (int i = 1; i <= 10000; i++) retained += commands.exists(CapacityPolicies.key(i));
            gates.put("10000BucketsRetained", retained == 10000);
            long cacheRetained = 0;
            for (int i = 0; i < 1024; i++) cacheRetained += commands.exists(CapacityCacheFixture.key(i));
            gates.put("1024CacheKeysRetained", cacheRetained == 1024);
            report.put("measurementsCompleted", true);
        } finally {
            CapacityReport.write(directory.resolve("report.json"), workloadQuiescent, () -> {
                report.put("finishedAt", Instant.now().toString());
                @SuppressWarnings("unchecked")
                var workers = (List<Map<String, Object>>) report.getOrDefault("workers", List.of());
                gates.put("workloadQuiescent", true);
                gates.put("workersTerminatedCleanly", workers.size() == 2 && workers.stream()
                        .allMatch(w -> Boolean.TRUE.equals(w.get("terminated")) && Integer.valueOf(0).equals(w.get("exitCode"))));
                report.put("allMeasuredGatesPassed", Boolean.TRUE.equals(report.get("measurementsCompleted"))
                        && gates.values().stream().allMatch(Boolean::booleanValue));
                return report;
            });
        }
        // Persist failed gates too: a failing measurement must remain inspectable.
        assertThat(gates).isNotEmpty().allSatisfy((name, passed) -> assertThat(passed).as(name).isTrue());
    }

    private static Map<String, Object> distribution(long[] values) {
        if (values.length == 0) return Map.of("count", 0);
        return Map.of("count", values.length, "p95Nanos", CapacityMeasurements.percentile(values, .95),
                "p99Nanos", CapacityMeasurements.percentile(values, .99), "maxNanos", java.util.Arrays.stream(values).max().orElseThrow());
    }

    private static String field(String info, String name) {
        return info.lines().filter(line -> line.startsWith(name + ":"))
                .map(line -> line.substring(name.length() + 1).trim()).findFirst().orElse("");
    }

    private static Map<String, Long> snapshot(RedisCommands<String, String> commands) {
        String memory = commands.info("memory");
        String oom = field(commands.info("errorstats"), "errorstat_OOM");
        return Map.of("epochMillis", System.currentTimeMillis(), "usedMemory", Long.parseLong(field(memory, "used_memory")),
                "rss", Long.parseLong(field(memory, "used_memory_rss")),
                "evicted", Long.parseLong(field(commands.info("stats"), "evicted_keys")),
                "oom", oom.isEmpty() ? 0L : Long.parseLong(oom.substring("count=".length()).split(",")[0]));
    }
}
