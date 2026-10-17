package com.learne.backend.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.MaintNotificationsConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.NettyCustomizer;
import io.netty.channel.Channel;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns only the limiter's client, bounded event loops, and in-progress TCP handshake. */
final class LettuceLimiterConnector implements LimiterRedisConnection.Connector {
    private final DefaultClientResources resources;
    private final RedisClient client;
    private final RedisURI uri;
    private final AtomicReference<Attempt> connecting = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    LettuceLimiterConnector(RedisURI uri) {
        if (uri.getHost() == null || uri.getSocket() != null || uri.getSentinelMasterId() != null
                || !uri.getSentinels().isEmpty()) {
            throw new IllegalArgumentException("Unsupported limiter Redis configuration");
        }
        this.uri = RedisURI.builder(uri).withTimeout(Duration.ofSeconds(2)).build();
        resources = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2)
                .nettyCustomizer(new NettyCustomizer() {
                    @Override
                    public void afterChannelInitialized(Channel channel) {
                        Attempt attempt = connecting.get();
                        if (attempt == null) channel.close();
                        else attempt.channel(channel);
                    }
                }).build();
        client = RedisClient.create(resources, this.uri);
        client.setOptions(ClientOptions.builder().autoReconnect(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(128).timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(2)))
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build())
                .maintNotificationsConfig(MaintNotificationsConfig.disabled()).build());
    }

    @Override
    public LimiterRedisConnection.ConnectAttempt connect() {
        if (closed.get()) throw RateLimitException.unavailable();
        var attempt = new Attempt();
        if (!connecting.compareAndSet(null, attempt)) throw RateLimitException.unavailable();
        try {
            // Retain this completion observer even after cancelling the exposed result: cancelling
            // a dependent ConnectionFuture alone does not prove that a late channel was closed.
            client.connectAsync(StringCodec.UTF8, uri).whenComplete((connection, failure) -> {
                if (failure != null) {
                    attempt.failed();
                } else if (attempt.aborted.get() || closed.get() || !attempt.result.complete(connection)) {
                    connection.closeAsync().whenComplete((ignored, closeFailure) -> {
                        if (closeFailure == null) attempt.finished.complete(null);
                    });
                }
                connecting.compareAndSet(attempt, null);
            });
            return attempt;
        } catch (RuntimeException failure) {
            attempt.failed();
            connecting.compareAndSet(attempt, null);
            return attempt;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Attempt attempt = connecting.get();
        if (attempt != null) attempt.abort();
        client.shutdownAsync(0, 2, TimeUnit.SECONDS).whenComplete((ignored, failure) ->
                resources.shutdown(0, 2, TimeUnit.SECONDS));
    }

    static final class Attempt implements LimiterRedisConnection.ConnectAttempt {
        final CompletableFuture<StatefulRedisConnection<String, String>> result = new CompletableFuture<>();
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        final AtomicReference<Channel> channel = new AtomicReference<>();
        final AtomicBoolean aborted = new AtomicBoolean();
        final AtomicBoolean connectFailed = new AtomicBoolean();

        void failed() {
            connectFailed.set(true);
            result.completeExceptionally(RateLimitException.unavailable());
            closeChannel();
        }

        void channel(Channel value) {
            channel.set(value);
            if (aborted.get()) closeChannel();
        }

        void closeChannel() {
            Channel value = channel.get();
            if (value != null) value.close().addListener(future -> {
                if (future.isSuccess()) finished.complete(null);
            });
            else if (connectFailed.get()) finished.complete(null);
        }

        public CompletableFuture<StatefulRedisConnection<String, String>> future() { return result; }

        public CompletableFuture<Void> abort() {
            aborted.set(true);
            result.cancel(true);
            closeChannel();
            return finished;
        }
    }
}
