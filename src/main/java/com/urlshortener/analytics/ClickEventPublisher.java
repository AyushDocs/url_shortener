package com.urlshortener.analytics;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.urlshortener.cache.UrlCache;

@Component
public class ClickEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ClickEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final UrlCache urlCache;
    private final String topicName;

    public ClickEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                               UrlCache urlCache,
                               @Value("${spring.kafka.topic.click-events:click-events}") String topicName) {
        this.kafkaTemplate = kafkaTemplate;
        this.urlCache = urlCache;
        this.topicName = topicName;
    }

    public void publish(ClickEvent event) {
        urlCache.recordClick(event.shortCode(), event.shardId());

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(topicName, event.shardId() + ":" + event.shortCode(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("Failed to publish click event for {} [shard={}]: {}",
                        event.shortCode(), event.shardId(), ex.getMessage());
            } else {
                log.debug("Click event published for {} [shard={}] to partition {}",
                        event.shortCode(), event.shardId(),
                        result.getRecordMetadata().partition());
            }
        });
    }
}
