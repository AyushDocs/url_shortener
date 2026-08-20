package com.urlshortener.controller;

import com.urlshortener.analytics.ClickEvent;
import com.urlshortener.analytics.ClickEventPublisher;
import com.urlshortener.metrics.UrlMetrics;
import com.urlshortener.service.UrlService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
public class UrlRedirectController {

    private final UrlService urlService;
    private final ClickEventPublisher clickEventPublisher;
    private final UrlMetrics urlMetrics;

    public UrlRedirectController(UrlService urlService,
                                 ClickEventPublisher clickEventPublisher,
                                 UrlMetrics urlMetrics) {
        this.urlService = urlService;
        this.clickEventPublisher = clickEventPublisher;
        this.urlMetrics = urlMetrics;
    }

    @GetMapping("/{shortCode:[a-zA-Z0-9]{1,16}}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode,
                                         HttpServletRequest request) {
        long start = System.currentTimeMillis();

        UrlService.ResolvedUrl resolved = urlService.resolveUrl(shortCode);

        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = request.getRemoteAddr();
        }

        clickEventPublisher.publish(new ClickEvent(
                shortCode,
                resolved.shardId(),
                clientIp,
                request.getHeader("User-Agent"),
                request.getHeader("Referer")
        ));

        urlMetrics.recordRedirect();
        urlMetrics.recordRedirectDuration(System.currentTimeMillis() - start);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(resolved.originalUrl()))
                .build();
    }
}
