package com.learne.backend.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Process and localhost IPC lifecycle, deliberately separate from timed service calls. */
final class CapacityFork implements AutoCloseable {
    private final Process process;
    private final HttpClient http;
    final Map<String, Object> metadata;
    private final String base;

    CapacityFork(String host, int redisPort, Path directory, int index) throws Exception {
        this(host, redisPort, directory, index, process -> { });
    }

    boolean httpTerminated() { return http.isTerminated(); }

    CapacityFork(String host, int redisPort, Path directory, int index, java.util.function.Consumer<Process> afterStart) throws Exception {
        Path ready = directory.resolve("worker-" + index + "-ready.json");
        Path arguments = directory.resolve("worker-" + index + ".args");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(arguments, "-Xmx128m\n-XX:ActiveProcessorCount=2\n-Dio.netty.eventLoopThreads=2\n-cp\n\""
                + classpath.replace('\\', '/') + "\"\n" + CapacityWorker.class.getName() + "\n"
                + host + "\n" + redisPort + "\n\"" + ready.toAbsolutePath().toString().replace('\\', '/')
                + "\"\n" + (20001 + index) + "\n" + ProcessHandle.current().pid() + "\n");
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        process = new ProcessBuilder(executable, "@" + arguments.toAbsolutePath())
                .redirectErrorStream(true).redirectOutput(directory.resolve("worker-" + index + ".log").toFile()).start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        try {
            afterStart.accept(process);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            while (!Files.exists(ready)) {
                if (!process.isAlive() || System.nanoTime() > deadline) throw new AssertionError("Worker startup failed; inspect worker log");
                Thread.sleep(20);
            }
            metadata = new ObjectMapper().readValue(ready.toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
            if (((Number) metadata.get("pid")).longValue() != process.pid()) throw new AssertionError("PID mismatch");
            base = "http://127.0.0.1:" + metadata.get("port");
        } catch (Throwable failure) {
            boolean interrupted = failure instanceof InterruptedException;
            try (var cleanup = new CapacityCleanup()) {
                cleanup.step(() -> process.destroyForcibly());
                cleanup.step(() -> awaitProcess(cleanup, Duration.ofSeconds(3)));
                closeHttp(cleanup);
            } catch (Throwable problem) { failure.addSuppressed(problem); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw failure;
        }
    }

    String call(String path) throws Exception {
        return call(path, Duration.ofSeconds(90));
    }

    private String call(String path, Duration timeout) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new AssertionError("Worker HTTP status " + response.statusCode());
        return response.body();
    }

    @Override
    public void close() throws Exception {
        boolean intentionalCancellation = Thread.currentThread().isInterrupted();
        try (var cleanup = new CapacityCleanup()) {
            cleanup.step(() -> {
                if (process.isAlive()) {
                    if (intentionalCancellation) process.destroyForcibly();
                    else if (!"stopping".equals(call("/stop", Duration.ofSeconds(1))))
                        throw new AssertionError("Invalid worker stop response");
                }
            });
            cleanup.step(() -> {
                if (process.isAlive() && !cleanup.await(n -> process.waitFor(n, TimeUnit.NANOSECONDS), Duration.ofSeconds(2)))
                    process.destroyForcibly();
            });
            // Independent fallback: a failed graceful request or wait must never bypass the kill.
            cleanup.step(() -> { if (process.isAlive()) process.destroyForcibly(); });
            cleanup.step(() -> awaitProcess(cleanup, Duration.ofSeconds(3)));
            closeHttp(cleanup);
            cleanup.step(() -> {
                metadata.put("terminated", !process.isAlive());
                if (process.isAlive()) throw new AssertionError("Worker still alive");
                metadata.put("exitCode", process.exitValue());
                metadata.put("intentionalCancellation", intentionalCancellation);
                if (process.exitValue() != 0 && !intentionalCancellation)
                    throw new AssertionError("Worker exit code " + process.exitValue());
            });
        }
    }

    private void awaitProcess(CapacityCleanup cleanup, Duration timeout) {
        if (!cleanup.awaitProcess(process, timeout))
            throw new AssertionError("Worker termination timed out: " + process.pid());
    }

    private void closeHttp(CapacityCleanup cleanup) {
        cleanup.step(http::shutdownNow);
        cleanup.step(() -> {
            if (!cleanup.await(n -> http.awaitTermination(Duration.ofNanos(n)), Duration.ofSeconds(2)))
                throw new AssertionError("Worker HTTP client did not terminate");
        });
    }
}
