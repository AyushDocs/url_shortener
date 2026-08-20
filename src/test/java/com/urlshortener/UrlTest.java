package com.urlshortener;

import com.urlshortener.domain.Url;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class UrlTest {

    @Test
    void isExpired_returnsFalse_whenNoExpiration() {
        Url url = new Url("abc", "https://example.com", null);
        assertFalse(url.isExpired());
    }

    @Test
    void isExpired_returnsFalse_whenFutureExpiration() {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        Url url = new Url("abc", "https://example.com", future);
        assertFalse(url.isExpired());
    }

    @Test
    void isExpired_returnsTrue_whenPastExpiration() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        Url url = new Url("abc", "https://example.com", past);
        assertTrue(url.isExpired());
    }

    @Test
    void incrementClickCount_increments() {
        Url url = new Url("abc", "https://example.com", null);
        assertEquals(0, url.getClickCount());
        url.incrementClickCount();
        assertEquals(1, url.getClickCount());
        url.incrementClickCount();
        assertEquals(2, url.getClickCount());
    }

    @Test
    void constructor_setsFields() {
        Instant expires = Instant.now().plusSeconds(3600);
        Url url = new Url("abc", "https://example.com", expires);
        assertEquals("abc", url.getShortCode());
        assertEquals("https://example.com", url.getOriginalUrl());
        assertEquals(expires, url.getExpiresAt());
        assertTrue(url.isActive());
        assertEquals(0, url.getClickCount());
    }
}
