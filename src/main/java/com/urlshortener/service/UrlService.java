package com.urlshortener.service;

import com.urlshortener.cache.UrlCache;
import com.urlshortener.domain.Url;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlDetailResponse;
import com.urlshortener.exception.DuplicateShortCodeException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.generator.ShortCodeGenerator;
import com.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class UrlService {

    private static final Logger log = LoggerFactory.getLogger(UrlService.class);

    private final UrlRepository urlRepository;
    private final UrlCache urlCache;
    private final ShortCodeGenerator shortCodeGenerator;
    private final long defaultTtlSeconds;

    public UrlService(UrlRepository urlRepository,
                      UrlCache urlCache,
                      ShortCodeGenerator shortCodeGenerator,
                      @Value("${app.cache.url-ttl-seconds:3600}") long defaultTtlSeconds) {
        this.urlRepository = urlRepository;
        this.urlCache = urlCache;
        this.shortCodeGenerator = shortCodeGenerator;
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    @Transactional
    public CreateUrlResponse createUrl(CreateUrlRequest request) {
        String shortCode = (request.customCode() != null && !request.customCode().isBlank())
                ? request.customCode()
                : shortCodeGenerator.generateUnique();

        if (!shortCodeGenerator.isValidCode(shortCode)) {
            throw new IllegalArgumentException("Invalid short code: " + shortCode);
        }

        Instant expiresAt = request.ttlSeconds() != null
                ? Instant.now().plusSeconds(request.ttlSeconds())
                : null;

        Url url = new Url(shortCode, request.url(), expiresAt);

        try {
            url = urlRepository.save(url);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateShortCodeException(shortCode);
        }

        Duration ttl = computeTtl(expiresAt);
        urlCache.putUrl(shortCode, request.url(), ttl);

        log.info("Created short URL: {} -> {}", shortCode, request.url());

        return new CreateUrlResponse(
                shortCode,
                "https://sho.rt/" + shortCode,
                request.url(),
                url.getCreatedAt(),
                expiresAt
        );
    }

    @Transactional(readOnly = true)
    public String resolveUrl(String shortCode) {
        String cached = urlCache.getUrl(shortCode).orElse(null);
        if (cached != null) {
            log.debug("Cache hit for shortCode={}", shortCode);
            return cached;
        }

        log.debug("Cache miss for shortCode={}", shortCode);

        Url url = urlRepository.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (url.isExpired()) {
            throw new UrlExpiredException(shortCode);
        }

        Duration ttl = computeTtl(url.getExpiresAt());
        urlCache.putUrl(shortCode, url.getOriginalUrl(), ttl);

        return url.getOriginalUrl();
    }

    @Transactional(readOnly = true)
    public UrlDetailResponse getUrlDetail(String shortCode) {
        Url url = urlRepository.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        long redisClicks = urlCache.getClickCount(shortCode);

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

    @Transactional
    public void deleteUrl(String shortCode) {
        Url url = urlRepository.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        url.setActive(false);
        urlRepository.save(url);
        urlCache.removeUrl(shortCode);

        log.info("Deleted short URL: {}", shortCode);
    }

    @Transactional
    public void flushAnalytics(String shortCode) {
        long redisClicks = urlCache.getClickCount(shortCode);
        if (redisClicks > 0) {
            urlRepository.incrementClickCount(shortCode, redisClicks);
            urlCache.flushClicks(shortCode);
            log.debug("Flushed {} clicks for {}", redisClicks, shortCode);
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
