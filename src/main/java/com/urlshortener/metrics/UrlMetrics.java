package com.urlshortener.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class UrlMetrics {

    private final Counter urlCreatedCounter;
    private final Counter redirectCounter;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Counter cacheUpdateCounter;
    private final Counter analyticsPublishedCounter;
    private final Timer redirectTimer;

    public UrlMetrics(MeterRegistry meterRegistry) {
        this.urlCreatedCounter = Counter.builder("url.created.total")
                .description("Total number of URLs created")
                .register(meterRegistry);

        this.redirectCounter = Counter.builder("url.redirect.total")
                .description("Total number of redirects served")
                .register(meterRegistry);

        this.cacheHitCounter = Counter.builder("url.cache.hit.total")
                .description("Total cache hits")
                .register(meterRegistry);

        this.cacheMissCounter = Counter.builder("url.cache.miss.total")
                .description("Total cache misses")
                .register(meterRegistry);

        this.cacheUpdateCounter = Counter.builder("url.cache.update.total")
                .description("Total cache updates from DB")
                .register(meterRegistry);

        this.analyticsPublishedCounter = Counter.builder("url.analytics.published.total")
                .description("Total analytics events published")
                .register(meterRegistry);

        this.redirectTimer = Timer.builder("url.redirect.duration")
                .description("Redirect latency")
                .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                .register(meterRegistry);
    }

    public void recordUrlCreated() { urlCreatedCounter.increment(); }
    public void recordRedirect() { redirectCounter.increment(); }
    public void recordCacheHit() { cacheHitCounter.increment(); }
    public void recordCacheMiss() { cacheMissCounter.increment(); }
    public void recordCacheUpdate() { cacheUpdateCounter.increment(); }
    public void recordAnalyticsPublished() { analyticsPublishedCounter.increment(); }

    public void recordRedirectDuration(long durationMs) {
        redirectTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }
}
