package com.urlshortener.controller;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.service.UrlService;
import com.urlshortener.service.AnalyticsService;
import com.urlshortener.metrics.UrlMetrics;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
public class UrlRedirectController {

    private final UrlService urlService;
    private final AnalyticsService analyticsService;
    private final UrlMetrics urlMetrics;

    public UrlRedirectController(UrlService urlService,
                                 AnalyticsService analyticsService,
                                 UrlMetrics urlMetrics) {
        this.urlService = urlService;
        this.analyticsService = analyticsService;
        this.urlMetrics = urlMetrics;
    }

    @GetMapping("/{shortCode:[a-zA-Z0-9]{1,16}}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        long start = System.currentTimeMillis();

        String originalUrl = urlService.resolveUrl(shortCode);
        analyticsService.trackClick(shortCode);

        urlMetrics.recordRedirect();
        urlMetrics.recordRedirectDuration(System.currentTimeMillis() - start);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(originalUrl))
                .build();
    }
}
