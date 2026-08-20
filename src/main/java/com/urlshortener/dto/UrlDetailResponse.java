package com.urlshortener.dto;

import java.time.Instant;

public record UrlDetailResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        Instant createdAt,
        Instant expiresAt,
        boolean active,
        long clickCount
) {}
