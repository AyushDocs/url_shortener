package com.urlshortener.controller;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlDetailResponse;
import com.urlshortener.metrics.UrlMetrics;
import com.urlshortener.service.UrlService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/urls")
public class UrlManagementController {

    private final UrlService urlService;
    private final UrlMetrics urlMetrics;

    public UrlManagementController(UrlService urlService, UrlMetrics urlMetrics) {
        this.urlService = urlService;
        this.urlMetrics = urlMetrics;
    }

    @PostMapping
    public ResponseEntity<CreateUrlResponse> createUrl(@Valid @RequestBody CreateUrlRequest request) {
        CreateUrlResponse response = urlService.createUrl(request);
        urlMetrics.recordUrlCreated();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<UrlDetailResponse> getUrlDetail(@PathVariable String shortCode) {
        return ResponseEntity.ok(urlService.getUrlDetail(shortCode));
    }

    @DeleteMapping("/{shortCode}")
    public ResponseEntity<Void> deleteUrl(@PathVariable String shortCode) {
        urlService.deleteUrl(shortCode);
        return ResponseEntity.noContent().build();
    }
}
