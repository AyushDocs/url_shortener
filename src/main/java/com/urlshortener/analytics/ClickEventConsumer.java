package com.urlshortener.analytics;

import com.urlshortener.service.UrlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumes click events from Kafka and persists the accumulated counts.
 *
 * <p>Redirects only increment a Redis counter (see {@link ClickEventPublisher}); this consumer
 * collects the touched short codes and a scheduled flush moves the counts into the owning shard's
 * database. Doing the write here keeps redirect latency independent of database write throughput.
 */
@Component
public class ClickEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClickEventConsumer.class);

    private final Set<String> pendingShortCodes = ConcurrentHashMap.newKeySet();
    private final UrlService urlService;

    public ClickEventConsumer(UrlService urlService) {
        this.urlService = urlService;
    }

    @KafkaListener(
            topics = "${spring.kafka.topic.click-events:click-events}",
            groupId = "${spring.kafka.consumer.group-id:url-shortener-analytics}")
    public void onClickEvent(ClickEvent event) {
        if (event == null || event.shortCode() == null || event.shortCode().isBlank()) {
            return;
        }
        pendingShortCodes.add(event.shortCode());
        log.debug("Click event consumed for {} [shard={}]", event.shortCode(), event.shardId());
    }

    @Scheduled(fixedDelayString = "${app.analytics.flush-interval-ms:5000}")
    public void flushPendingClicks() {
        if (pendingShortCodes.isEmpty()) {
            return;
        }

        Set<String> batch = Set.copyOf(pendingShortCodes);
        pendingShortCodes.removeAll(batch);

        for (String shortCode : batch) {
            try {
                urlService.flushAnalytics(shortCode);
            } catch (Exception ex) {
                // Keep the code pending so the next flush retries instead of losing the clicks.
                pendingShortCodes.add(shortCode);
                log.warn("Failed to flush clicks for {}: {}", shortCode, ex.getMessage());
            }
        }
    }
}
