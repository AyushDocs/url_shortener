package com.urlshortener;

import com.urlshortener.cache.UrlCache;
import com.urlshortener.domain.Url;
import com.urlshortener.distributed.persistence.ShardedUrlRepository;
import com.urlshortener.distributed.routing.ShardRouter;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.generator.ShortCodeGenerator;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.service.UrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExpirationTest {

    @Mock
    private ShardedUrlRepository shardedUrlRepository;
    @Mock
    private UrlCache urlCache;
    @Mock
    private ShortCodeGenerator shortCodeGenerator;
    @Mock
    private ShardRouter shardRouter;

    private UrlService urlService;

    @BeforeEach
    void setUp() {
        urlService = new UrlService(shardedUrlRepository, urlCache, shortCodeGenerator, shardRouter, 3600);
        lenient().when(shardRouter.route(anyString())).thenReturn("shard-0");
    }

    @Test
    void createUrl_withTtl_setsExpiration() {
        when(shortCodeGenerator.isValidCode("ttl1")).thenReturn(true);
        Url saved = new Url("ttl1", "https://example.com", Instant.now().plusSeconds(600));
        saved.setShardId("shard-0");
        saved.setCreatedAt(Instant.now());
        when(shardedUrlRepository.save(any(Url.class))).thenReturn(saved);

        urlService.createUrl(new CreateUrlRequest("https://example.com", "ttl1", 600));

        verify(urlCache).putUrl(eq("ttl1"), anyString(), any(Duration.class), eq("shard-0"));
    }

    @Test
    void createUrl_withoutTtl_noExpiration() {
        when(shortCodeGenerator.generateUnique()).thenReturn("notl");
        when(shortCodeGenerator.isValidCode("notl")).thenReturn(true);
        Url saved = new Url("notl", "https://example.com", null);
        saved.setShardId("shard-0");
        saved.setCreatedAt(Instant.now());
        when(shardedUrlRepository.save(any(Url.class))).thenReturn(saved);

        urlService.createUrl(new CreateUrlRequest("https://example.com"));

        verify(urlCache).putUrl(eq("notl"), anyString(), any(Duration.class), eq("shard-0"));
    }

    @Test
    void resolveUrl_expiredUrl_throwsException() {
        when(urlCache.getUrl("expired", "shard-0")).thenReturn(Optional.empty());

        Url url = new Url("expired", "https://expired.com",
                Instant.now().minus(1, ChronoUnit.HOURS));
        when(shardedUrlRepository.findByShortCode("expired")).thenReturn(Optional.of(url));

        assertThrows(UrlExpiredException.class, () -> urlService.resolveUrl("expired"));
    }

    @Test
    void resolveUrl_notExpired_resolves() {
        when(urlCache.getUrl("valid", "shard-0")).thenReturn(Optional.empty());

        Url url = new Url("valid", "https://valid.com",
                Instant.now().plus(1, ChronoUnit.HOURS));
        when(shardedUrlRepository.findByShortCode("valid")).thenReturn(Optional.of(url));

        UrlService.ResolvedUrl result = urlService.resolveUrl("valid");
        assertEquals("https://valid.com", result.originalUrl());
    }

    @Test
    void resolveUrl_noExpiration_resolves() {
        when(urlCache.getUrl("forever", "shard-0")).thenReturn(Optional.empty());

        Url url = new Url("forever", "https://forever.com", null);
        when(shardedUrlRepository.findByShortCode("forever")).thenReturn(Optional.of(url));

        UrlService.ResolvedUrl result = urlService.resolveUrl("forever");
        assertEquals("https://forever.com", result.originalUrl());
    }

    @Test
    void resolveUrl_notFound_throws() {
        when(urlCache.getUrl("gone", "shard-0")).thenReturn(Optional.empty());
        when(shardedUrlRepository.findByShortCode("gone")).thenReturn(Optional.empty());

        assertThrows(UrlNotFoundException.class, () -> urlService.resolveUrl("gone"));
    }
}
