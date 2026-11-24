package com.urlshortener.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.Optional;

@Component
public class UrlCache {

    private static final String URL_PATTERN = "shard:%s:url:%s";
    private static final String CLICKS_PATTERN = "shard:%s:clicks:%s";

    private final StringRedisTemplate redisTemplate;
    private final long defaultTtlSeconds;

    public UrlCache(StringRedisTemplate redisTemplate,
                    @Value("${app.cache.url-ttl-seconds:3600}") long defaultTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public Optional<String> getUrl(String shortCode, String shard) {
        String value = redisTemplate.opsForValue().get(urlKey(shard, shortCode));
        return Optional.ofNullable(value);
    }

    public void putUrl(String shortCode, String originalUrl, Duration ttl, String shard) {
        redisTemplate.opsForValue().set(urlKey(shard, shortCode), originalUrl, ttl);
    }

    public void putUrl(String shortCode, String originalUrl, String shard) {
        putUrl(shortCode, originalUrl, Duration.ofSeconds(defaultTtlSeconds), shard);
    }

    public void removeUrl(String shortCode, String shard) {
        redisTemplate.delete(urlKey(shard, shortCode));
        redisTemplate.delete(clicksKey(shard, shortCode));
    }

    public void recordClick(String shortCode, String shard) {
        redisTemplate.opsForValue().increment(clicksKey(shard, shortCode));
    }

    public long getClickCount(String shortCode, String shard) {
        String val = redisTemplate.opsForValue().get(clicksKey(shard, shortCode));
        return val != null ? Long.parseLong(val) : 0L;
    }

    public void flushClicks(String shortCode, String shard) {
        redisTemplate.delete(clicksKey(shard, shortCode));
    }

    /**
     * Atomically reads and clears the click counter (Redis GETDEL), so clicks recorded while a
     * flush is in flight are never dropped between the read and the delete.
     */
    public long getAndResetClickCount(String shortCode, String shard) {
        String value = redisTemplate.opsForValue().getAndDelete(clicksKey(shard, shortCode));
        return value != null ? Long.parseLong(value) : 0L;
    }

    public void putClickCount(String shortCode, long count, Duration ttl, String shard) {
        redisTemplate.opsForValue().set(clicksKey(shard, shortCode), String.valueOf(count), ttl);
    }

    private String urlKey(String shard, String shortCode) {
        return String.format(URL_PATTERN, shard, shortCode);
    }

    private String clicksKey(String shard, String shortCode) {
        return String.format(CLICKS_PATTERN, shard, shortCode);
    }
}
