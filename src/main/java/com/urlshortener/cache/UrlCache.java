package com.urlshortener.cache;

import com.urlshortener.dto.UrlDetailResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class UrlCache {

    private static final String URL_PREFIX = "url:";
    private static final String CLICKS_PREFIX = "clicks:";

    private final StringRedisTemplate redisTemplate;
    private final long defaultTtlSeconds;

    public UrlCache(StringRedisTemplate redisTemplate,
                    @org.springframework.beans.factory.annotation.Value("${app.cache.url-ttl-seconds:3600}") long defaultTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public Optional<String> getUrl(String shortCode) {
        String value = redisTemplate.opsForValue().get(URL_PREFIX + shortCode);
        return Optional.ofNullable(value);
    }

    public void putUrl(String shortCode, String originalUrl, Duration ttl) {
        redisTemplate.opsForValue().set(URL_PREFIX + shortCode, originalUrl, ttl);
    }

    public void putUrl(String shortCode, String originalUrl) {
        putUrl(shortCode, originalUrl, Duration.ofSeconds(defaultTtlSeconds));
    }

    public void removeUrl(String shortCode) {
        redisTemplate.delete(URL_PREFIX + shortCode);
        redisTemplate.delete(CLICKS_PREFIX + shortCode);
    }

    public void recordClick(String shortCode) {
        redisTemplate.opsForValue().increment(CLICKS_PREFIX + shortCode);
    }

    public long getClickCount(String shortCode) {
        String val = redisTemplate.opsForValue().get(CLICKS_PREFIX + shortCode);
        return val != null ? Long.parseLong(val) : 0L;
    }

    public void flushClicks(String shortCode) {
        redisTemplate.delete(CLICKS_PREFIX + shortCode);
    }

    public void putClickCount(String shortCode, long count, Duration ttl) {
        redisTemplate.opsForValue().set(CLICKS_PREFIX + shortCode, String.valueOf(count), ttl);
    }
}
