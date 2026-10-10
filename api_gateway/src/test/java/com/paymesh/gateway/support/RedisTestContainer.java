package com.paymesh.gateway.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** One real Redis for the whole test JVM (started once, removed by Testcontainers at exit). */
public final class RedisTestContainer {

    private static final int REDIS_PORT = 6379;
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }

    private RedisTestContainer() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("REDIS_HOST", REDIS::getHost);
        registry.add("REDIS_PORT", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    /** Empties every rate-limit bucket. */
    public static void flush() {
        try {
            REDIS.execInContainer("redis-cli", "FLUSHALL");
        } catch (Exception e) {
            throw new IllegalStateException("Could not flush test Redis", e);
        }
    }
}
