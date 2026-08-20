package com.urlshortener.service;

import com.urlshortener.cache.UrlCache;
import com.urlshortener.domain.Url;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlDetailResponse;
import com.urlshortener.distributed.persistence.ShardedUrlRepository;
import com.urlshortener.distributed.routing.ShardRouter;
import com.urlshortener.distributed.routing.ShardRoutingContext;
import com.urlshortener.exception.DuplicateShortCodeException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.generator.ShortCodeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class UrlService {

    private static final Logger log = LoggerFactory.getLogger(UrlService.class);

    private final ShardedUrlRepository shardedUrlRepository;
    private final UrlCache urlCache;
    private final ShortCodeGenerator shortCodeGenerator;
    private final ShardRouter shardRouter;
    private final long defaultTtlSeconds;

    public UrlService(ShardedUrlRepository shardedUrlRepository,
                      UrlCache urlCache,
                      ShortCodeGenerator shortCodeGenerator,
                      ShardRouter shardRouter,
                      @Value("${app.cache.url-ttl-seconds:3600}") long defaultTtlSeconds) {
        this.shardedUrlRepository = shardedUrlRepository;
        this.urlCache = urlCache;
        this.shortCodeGenerator = shortCodeGenerator;
        this.shardRouter = shardRouter;
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public record ResolvedUrl(String originalUrl, String shardId) {}

    public CreateUrlResponse createUrl(CreateUrlRequest request) {
        String shortCode = (request.customCode() != null && !request.customCode().isBlank())
                ? request.customCode()
                : shortCodeGenerator.generateUnique();

        if (!shortCodeGenerator.isValidCode(shortCode)) {
            throw new IllegalArgumentException("Invalid short code: " + shortCode);
        }

        String targetShard = shardRouter.route(shortCode);

        Instant expiresAt = request.ttlSeconds() != null
                ? Instant.now().plusSeconds(request.ttlSeconds())
                : null;

        Url url = new Url(shortCode, request.url(), expiresAt);
        url.setShardId(targetShard);

        try {
            url = shardedUrlRepository.save(url);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateShortCodeException(shortCode);
        }

        Duration ttl = computeTtl(expiresAt);
        urlCache.putUrl(shortCode, request.url(), ttl, targetShard);

        log.info("Created short URL: {} -> {} [shard={}]",
                shortCode, request.url(), targetShard);

        return new CreateUrlResponse(
                shortCode,
                "https://sho.rt/" + shortCode,
                request.url(),
                url.getCreatedAt(),
                expiresAt
        );
    }

    public ResolvedUrl resolveUrl(String shortCode) {
        String targetShard = shardRouter.route(shortCode);

        String cached = urlCache.getUrl(shortCode, targetShard).orElse(null);
        if (cached != null) {
            log.debug("Cache hit for shortCode={} [shard={}]", shortCode, targetShard);
            return new ResolvedUrl(cached, targetShard);
        }

        log.debug("Cache miss for shortCode={} [shard={}]", shortCode, targetShard);

        Url url = shardedUrlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (url.isExpired()) {
            throw new UrlExpiredException(shortCode);
        }

        Duration ttl = computeTtl(url.getExpiresAt());
        urlCache.putUrl(shortCode, url.getOriginalUrl(), ttl, targetShard);

        return new ResolvedUrl(url.getOriginalUrl(), targetShard);
    }

    public UrlDetailResponse getUrlDetail(String shortCode) {
        Url url = shardedUrlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        String targetShard = shardRouter.route(shortCode);
        long redisClicks = urlCache.getClickCount(shortCode, targetShard);

        return new UrlDetailResponse(
                shortCode,
                "https://sho.rt/" + shortCode,
                url.getOriginalUrl(),
                url.getCreatedAt(),
                url.getExpiresAt(),
                url.isActive(),
                url.getClickCount() + redisClicks
        );
    }

    public void deleteUrl(String shortCode) {
        shardedUrlRepository.delete(shortCode);

        String targetShard = shardRouter.route(shortCode);
        urlCache.removeUrl(shortCode, targetShard);

        log.info("Deleted short URL: {} [shard={}]", shortCode, targetShard);
    }

    public void flushAnalytics(String shortCode) {
        String targetShard = shardRouter.route(shortCode);
        long redisClicks = urlCache.getClickCount(shortCode, targetShard);
        if (redisClicks > 0) {
            shardedUrlRepository.incrementClickCount(shortCode, redisClicks);
            urlCache.flushClicks(shortCode, targetShard);
            log.debug("Flushed {} clicks for {} [shard={}]", redisClicks, shortCode, targetShard);
        }
    }

    private Duration computeTtl(Instant expiresAt) {
        if (expiresAt == null) {
            return Duration.ofSeconds(defaultTtlSeconds);
        }
        long remaining = Duration.between(Instant.now(), expiresAt).getSeconds();
        return remaining > 0 ? Duration.ofSeconds(remaining) : Duration.ZERO;
    }
}
