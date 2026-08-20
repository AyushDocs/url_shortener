package com.urlshortener;

import com.urlshortener.cache.UrlCache;
import com.urlshortener.domain.Url;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.distributed.persistence.ShardedUrlRepository;
import com.urlshortener.distributed.routing.ShardRouter;
import com.urlshortener.exception.DuplicateShortCodeException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.generator.ShortCodeGenerator;
import com.urlshortener.service.UrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

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
    void createUrl_generatesCodeAndReturnsResponse() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com");
        when(shortCodeGenerator.isValidCode("abc123")).thenReturn(true);
        when(shortCodeGenerator.generateUnique()).thenReturn("abc123");

        Url savedUrl = new Url("abc123", "https://example.com", null);
        savedUrl.setShardId("shard-0");
        savedUrl.setCreatedAt(Instant.now());
        when(shardedUrlRepository.save(any(Url.class))).thenReturn(savedUrl);

        CreateUrlResponse response = urlService.createUrl(request);

        assertEquals("abc123", response.shortCode());
        assertEquals("https://sho.rt/abc123", response.shortUrl());
        verify(urlCache).putUrl(eq("abc123"), eq("https://example.com"), any(Duration.class), eq("shard-0"));
    }

    @Test
    void createUrl_duplicateCode_throwsException() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "dup", null);
        when(shortCodeGenerator.isValidCode("dup")).thenReturn(true);
        when(shardedUrlRepository.save(any(Url.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThrows(DuplicateShortCodeException.class,
                () -> urlService.createUrl(request));
    }

    @Test
    void resolveUrl_cacheHit_returnsCachedUrl() {
        when(urlCache.getUrl("abc", "shard-0")).thenReturn(Optional.of("https://cached.com"));

        UrlService.ResolvedUrl result = urlService.resolveUrl("abc");

        assertEquals("https://cached.com", result.originalUrl());
        assertEquals("shard-0", result.shardId());
        verify(shardedUrlRepository, never()).findByShortCode(anyString());
    }

    @Test
    void resolveUrl_cacheMiss_fallsBackToDb() {
        when(urlCache.getUrl("abc", "shard-0")).thenReturn(Optional.empty());

        Url url = new Url("abc", "https://db.com", null);
        when(shardedUrlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        UrlService.ResolvedUrl result = urlService.resolveUrl("abc");

        assertEquals("https://db.com", result.originalUrl());
        verify(urlCache).putUrl(eq("abc"), eq("https://db.com"), any(Duration.class), eq("shard-0"));
    }

    @Test
    void resolveUrl_notFound_throwsException() {
        when(urlCache.getUrl("abc", "shard-0")).thenReturn(Optional.empty());
        when(shardedUrlRepository.findByShortCode("abc")).thenReturn(Optional.empty());

        assertThrows(UrlNotFoundException.class, () -> urlService.resolveUrl("abc"));
    }

    @Test
    void resolveUrl_expired_throwsException() {
        when(urlCache.getUrl("abc", "shard-0")).thenReturn(Optional.empty());

        Url url = new Url("abc", "https://expired.com",
                Instant.now().minus(1, ChronoUnit.HOURS));
        when(shardedUrlRepository.findByShortCode("abc")).thenReturn(Optional.of(url));

        assertThrows(UrlExpiredException.class, () -> urlService.resolveUrl("abc"));
    }

    @Test
    void deleteUrl_deactivatesAndClearsCache() {
        urlService.deleteUrl("abc");

        verify(shardedUrlRepository).delete("abc");
        verify(urlCache).removeUrl("abc", "shard-0");
    }

    @Test
    void resolveUrl_routesToCorrectShard() {
        when(shardRouter.route("xY7mK9")).thenReturn("shard-2");
        when(urlCache.getUrl("xY7mK9", "shard-2")).thenReturn(Optional.of("https://example.com"));

        UrlService.ResolvedUrl result = urlService.resolveUrl("xY7mK9");

        assertEquals("shard-2", result.shardId());
        verify(urlCache).getUrl("xY7mK9", "shard-2");
    }
}
