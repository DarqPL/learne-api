package com.learne.backend.teacher.content.enrichment;

import com.learne.backend.jobs.service.PermanentJobException;
import com.learne.backend.jobs.service.RetryableJobException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded, owned transport. The timed wait covers headers AND the complete bounded body. */
public class EnrichmentHttpTransport implements AutoCloseable {
    public static final int MAX_BYTES = 1_048_576;
    private final EnrichmentClientProperties properties;
    private final ThreadPoolExecutor executor;
    private final HttpClient client;
    private final Semaphore admission;
    private final Map<CompletableFuture<?>, LimitedBody> active = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public EnrichmentHttpTransport(EnrichmentClientProperties properties) {
        this.properties = properties;
        this.admission = new Semaphore(properties.maxInFlight());
        this.executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), r -> {
                    Thread thread = new Thread(r, "enrichment-http-io");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.client = HttpClient.newBuilder().executor(executor)
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).build();
    }

    public Duration requestTimeout() { return Duration.ofMillis(properties.timeoutMs()); }

    /** No startup I/O. Used by the next admission gate; always limited to five seconds. */
    public Response capabilities(URI uri, String internalToken) {
        HttpRequest request;
        try {
            if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET();
            if (internalToken != null && !internalToken.isBlank()) builder.header("X-Internal-Service-Token", internalToken);
            request = builder.build();
        } catch (IllegalArgumentException error) {
            throw new PermanentJobException("ENRICHMENT_CONFIGURATION_UNAVAILABLE");
        }
        return exchange(request, Duration.ofMillis(Math.min(5_000, properties.timeoutMs())));
    }

    public Response exchange(HttpRequest request, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(requestTimeout()) > 0)
            throw new PermanentJobException("ENRICHMENT_CONFIGURATION_UNAVAILABLE");
        long deadline = System.nanoTime() + timeout.toNanos();
        if (closed || !admission.tryAcquire()) throw new RetryableJobException("BUSY", null);
        LimitedBody body = new LimitedBody();
        CompletableFuture<HttpResponse<byte[]>> exchange = null;
        try {
            synchronized (active) {
                if (closed) throw new RetryableJobException("BUSY", null);
                exchange = client.sendAsync(request, info -> {
                    var lengths = info.headers().allValues("Content-Length");
                    if (!lengths.isEmpty()) {
                        try {
                            if (lengths.size() != 1 || Long.parseLong(lengths.getFirst()) > MAX_BYTES) body.reject();
                        } catch (NumberFormatException invalid) { body.reject(); }
                    }
                    return body;
                });
                active.put(exchange, body);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            HttpResponse<byte[]> response = exchange.get(remaining, TimeUnit.NANOSECONDS);
            return new Response(response.statusCode(), response.headers(), response.body());
        } catch (TimeoutException error) {
            throw new RetryableJobException("ENRICHMENT_DEADLINE_EXCEEDED", null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RetryableJobException("ENRICHMENT_INTERRUPTED", null);
        } catch (ExecutionException | CancellationException | RejectedExecutionException error) {
            throw new RetryableJobException(body.rejected ? "ENRICHMENT_RESPONSE_TOO_LARGE" : "ENRICHMENT_UNAVAILABLE", null);
        } catch (IllegalArgumentException error) {
            throw new PermanentJobException("ENRICHMENT_CONFIGURATION_UNAVAILABLE");
        } finally {
            body.cancel();
            if (exchange != null) {
                exchange.cancel(true); // Cancel the actual JDK exchange, not a wrapper timeout future.
                active.remove(exchange);
            }
            admission.release();
        }
    }

    @Override public void close() {
        synchronized (active) {
            closed = true;
            active.forEach((future, body) -> { body.cancel(); future.cancel(true); });
        }
        client.shutdownNow(); // HttpClient.close() can wait indefinitely on a peer.
        executor.shutdownNow();
        try { executor.awaitTermination(200, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    public record Response(int status, HttpHeaders headers, byte[] body) {
        public Response {
            headers = HttpHeaders.of(headers.map(), (name, value) -> true);
            body = body.clone();
        }
        @Override public byte[] body() { return body.clone(); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean cancelled;
        private volatile boolean rejected;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (cancelled || rejected) value.cancel(); else value.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (cancelled || rejected) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) { reject(); return; }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        synchronized void reject() {
            rejected = true;
            cancel();
            result.completeExceptionally(new IOException("Enrichment response limit"));
        }
        synchronized void cancel() { cancelled = true; if (subscription != null) subscription.cancel(); }
        @Override public void onError(Throwable error) { result.completeExceptionally(new IOException("Enrichment transport failed")); }
        @Override public synchronized void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
