package com.learne.backend.ratelimit;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class RedisTestSupport {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis:7-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(128L * 1024 * 1024))
            .withCommand("redis-server", "--maxmemory", "64mb", "--maxmemory-policy", "noeviction");

    protected static RedisClient redisClient;

    /** Isolated container endpoint for transport integration tests; never application Redis. */
    protected static RedisURI redisUri() {
        return RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(6379)).build();
    }

    protected static RedisClient newRedisClient() {
        return RedisClient.create(redisUri());
    }

    @BeforeAll
    protected static void startRedis() {
        REDIS.start();
        redisClient = newRedisClient();
    }

    @AfterAll
    protected static void closeRedis() {
        try {
            if (redisClient != null) {
                redisClient.shutdown();
            }
        } finally {
            REDIS.close();
        }
    }
}
