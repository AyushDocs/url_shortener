package com.urlshortener.analytics;

import java.time.Instant;

public record ClickEvent(
        String shortCode,
        String shardId,
        String ipAddress,
        String userAgent,
        String referer,
        Instant clickedAt
) {
    public ClickEvent(String shortCode, String shardId) {
        this(shortCode, shardId, null, null, null, Instant.now());
    }

    public ClickEvent(String shortCode, String shardId, String ipAddress, String userAgent, String referer) {
        this(shortCode, shardId, ipAddress, userAgent, referer, Instant.now());
    }
}
