package com.urlshortener;

import com.urlshortener.ratelimiter.SlidingWindowRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class RateLimiterIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private SlidingWindowRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                        redis.getHost(), redis.getFirstMappedPort());
        factory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(factory);
        rateLimiter = new SlidingWindowRateLimiter(redisTemplate);
    }

    @Test
    void allowsRequestsWithinLimit() {
        assertTrue(rateLimiter.tryAcquire("test:1", 5, Duration.ofSeconds(10)));
        assertTrue(rateLimiter.tryAcquire("test:1", 5, Duration.ofSeconds(10)));
        assertTrue(rateLimiter.tryAcquire("test:1", 5, Duration.ofSeconds(10)));
    }

    @Test
    void rejectsWhenLimitExceeded() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.tryAcquire("test:2", 3, Duration.ofSeconds(10));
        }
        assertFalse(rateLimiter.tryAcquire("test:2", 3, Duration.ofSeconds(10)));
    }

    @Test
    void differentKeysAreIndependent() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryAcquire("key:a", 3, Duration.ofSeconds(10));
        }
        assertTrue(rateLimiter.tryAcquire("key:b", 3, Duration.ofSeconds(10)));
    }

    @Test
    void windowExpiry_allowsRequestsAgain() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.tryAcquire("test:3", 3, Duration.ofSeconds(1));
        }
        assertFalse(rateLimiter.tryAcquire("test:3", 3, Duration.ofSeconds(1)));

        try { Thread.sleep(1100); } catch (InterruptedException ignored) {}

        assertTrue(rateLimiter.tryAcquire("test:3", 3, Duration.ofSeconds(1)));
    }
}
