package com.urlshortener.ratelimiter;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class SlidingWindowRateLimiter {

    private static final String RATE_LIMIT_PREFIX = "ratelimit:";

    private final StringRedisTemplate redisTemplate;

    public SlidingWindowRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(String key, int maxRequests, Duration window) {
        String redisKey = RATE_LIMIT_PREFIX + key;
        long now = Instant.now().toEpochMilli();
        long windowStart = now - window.toMillis();

        var operations = redisTemplate.opsForZSet();

        operations.removeRangeByScore(redisKey, 0, windowStart);

        Long count = operations.zCard(redisKey);
        if (count != null && count >= maxRequests) {
            return false;
        }

        operations.add(redisKey, String.valueOf(now), (double) now);
        redisTemplate.expire(redisKey, window.plusSeconds(10));

        return true;
    }
}
