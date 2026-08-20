package com.urlshortener.analytics;

import java.time.Instant;

public record ClickEvent(
        String shortCode,
        String ipAddress,
        String userAgent,
        String referer,
        Instant clickedAt
) {
    public ClickEvent(String shortCode) {
        this(shortCode, null, null, null, Instant.now());
    }

    public ClickEvent(String shortCode, String ipAddress, String userAgent, String referer) {
        this(shortCode, ipAddress, userAgent, referer, Instant.now());
    }
}
