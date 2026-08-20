package com.urlshortener.analytics;

import com.urlshortener.cache.UrlCache;
import com.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class ClickEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ClickEventPublisher.class);

    private final KafkaTemplate<String, ClickEvent> kafkaTemplate;
    private final UrlCache urlCache;
    private final String topicName;

    public ClickEventPublisher(KafkaTemplate<String, ClickEvent> kafkaTemplate,
                               UrlCache urlCache,
                               @Value("${spring.kafka.topic.click-events:click-events}") String topicName) {
        this.kafkaTemplate = kafkaTemplate;
        this.urlCache = urlCache;
        this.topicName = topicName;
    }

    public void publish(ClickEvent event) {
        urlCache.recordClick(event.shortCode());

        CompletableFuture<SendResult<String, ClickEvent>> future =
                kafkaTemplate.send(topicName, event.shortCode(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("Failed to publish click event for {}: {}", event.shortCode(), ex.getMessage());
            } else {
                log.debug("Click event published for {} to partition {}",
                        event.shortCode(),
                        result.getRecordMetadata().partition());
            }
        });
    }
}
