package com.urlshortener;

import com.urlshortener.cache.UrlCache;
import com.urlshortener.domain.Url;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.exception.DuplicateShortCodeException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.generator.ShortCodeGenerator;
import com.urlshortener.repository.UrlRepository;
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
    private UrlRepository urlRepository;

    @Mock
    private UrlCache urlCache;

    @Mock
    private ShortCodeGenerator shortCodeGenerator;

    private UrlService urlService;

    @BeforeEach
    void setUp() {
        urlService = new UrlService(urlRepository, urlCache, shortCodeGenerator, 3600);
    }

    @Test
    void createUrl_generatesCodeAndReturnsResponse() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com");
        when(shortCodeGenerator.isValidCode("abc123")).thenReturn(true);
        when(shortCodeGenerator.generateUnique()).thenReturn("abc123");

        Url savedUrl = new Url("abc123", "https://example.com", null);
        savedUrl.setId(1L);
        savedUrl.setCreatedAt(Instant.now());
        when(urlRepository.save(any(Url.class))).thenReturn(savedUrl);

        CreateUrlResponse response = urlService.createUrl(request);

        assertEquals("abc123", response.shortCode());
        assertEquals("https://sho.rt/abc123", response.shortUrl());
        assertEquals("https://example.com", response.originalUrl());
        verify(urlCache).putUrl(eq("abc123"), eq("https://example.com"), any(Duration.class));
    }

    @Test
    void createUrl_usesCustomCode() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "mycode", null);
        when(shortCodeGenerator.isValidCode("mycode")).thenReturn(true);

        Url savedUrl = new Url("mycode", "https://example.com", null);
        savedUrl.setId(1L);
        savedUrl.setCreatedAt(Instant.now());
        when(urlRepository.save(any(Url.class))).thenReturn(savedUrl);

        CreateUrlResponse response = urlService.createUrl(request);

        assertEquals("mycode", response.shortCode());
        verify(shortCodeGenerator, never()).generateUnique();
    }

    @Test
    void createUrl_duplicateCode_throwsException() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "dup", null);
        when(shortCodeGenerator.isValidCode("dup")).thenReturn(true);
        when(urlRepository.save(any(Url.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThrows(DuplicateShortCodeException.class,
                () -> urlService.createUrl(request));
    }

    @Test
    void resolveUrl_cacheHit_returnsCachedUrl() {
        when(urlCache.getUrl("abc")).thenReturn(Optional.of("https://cached.com"));

        String result = urlService.resolveUrl("abc");

        assertEquals("https://cached.com", result);
        verify(urlRepository, never()).findByShortCodeAndActiveTrue(anyString());
    }

    @Test
    void resolveUrl_cacheMiss_fallsBackToDb() {
        when(urlCache.getUrl("abc")).thenReturn(Optional.empty());

        Url url = new Url("abc", "https://db.com", null);
        when(urlRepository.findByShortCodeAndActiveTrue("abc")).thenReturn(Optional.of(url));

        String result = urlService.resolveUrl("abc");

        assertEquals("https://db.com", result);
        verify(urlCache).putUrl(eq("abc"), eq("https://db.com"), any(Duration.class));
    }

    @Test
    void resolveUrl_notFound_throwsException() {
        when(urlCache.getUrl("abc")).thenReturn(Optional.empty());
        when(urlRepository.findByShortCodeAndActiveTrue("abc"))
                .thenReturn(Optional.empty());

        assertThrows(UrlNotFoundException.class, () -> urlService.resolveUrl("abc"));
    }

    @Test
    void resolveUrl_expired_throwsException() {
        when(urlCache.getUrl("abc")).thenReturn(Optional.empty());

        Url url = new Url("abc", "https://expired.com",
                Instant.now().minus(1, ChronoUnit.HOURS));
        when(urlRepository.findByShortCodeAndActiveTrue("abc"))
                .thenReturn(Optional.of(url));

        assertThrows(UrlExpiredException.class, () -> urlService.resolveUrl("abc"));
    }

    @Test
    void deleteUrl_deactivatesAndClearsCache() {
        Url url = new Url("abc", "https://example.com", null);
        when(urlRepository.findByShortCodeAndActiveTrue("abc"))
                .thenReturn(Optional.of(url));

        urlService.deleteUrl("abc");

        assertFalse(url.isActive());
        verify(urlRepository).save(url);
        verify(urlCache).removeUrl("abc");
    }

    @Test
    void deleteUrl_notFound_throwsException() {
        when(urlRepository.findByShortCodeAndActiveTrue("missing"))
                .thenReturn(Optional.empty());

        assertThrows(UrlNotFoundException.class, () -> urlService.deleteUrl("missing"));
    }
}
