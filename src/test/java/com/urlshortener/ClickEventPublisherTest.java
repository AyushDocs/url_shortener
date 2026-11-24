package com.urlshortener;

import com.urlshortener.cache.UrlCache;
import com.urlshortener.analytics.ClickEvent;
import com.urlshortener.analytics.ClickEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClickEventPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private UrlCache urlCache;

    private static final String TOPIC = "click-events";

    private ClickEventPublisher publisher() {
        return new ClickEventPublisher(kafkaTemplate, urlCache, TOPIC);
    }

    @Test
    void publish_recordsClickInCache() {
        when(kafkaTemplate.send(anyString(), anyString(), any(ClickEvent.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        ClickEvent event = new ClickEvent("abc123", "shard-0", "10.0.0.1", "curl/7", null);
        publisher().publish(event);

        verify(urlCache).recordClick("abc123", "shard-0");
    }

    @Test
    void publish_sendsToKafkaWithCorrectTopicAndKey() {
        when(kafkaTemplate.send(anyString(), anyString(), any(ClickEvent.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        ClickEvent event = new ClickEvent("abc123", "shard-1");
        publisher().publish(event);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ClickEvent> eventCaptor = ArgumentCaptor.forClass(ClickEvent.class);

        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), eventCaptor.capture());

        assertEquals("click-events", topicCaptor.getValue());
        assertEquals("shard-1:abc123", keyCaptor.getValue());
        assertEquals("abc123", eventCaptor.getValue().shortCode());
        assertEquals("shard-1", eventCaptor.getValue().shardId());
    }

    @Test
    void publish_includesClientMetadata() {
        when(kafkaTemplate.send(anyString(), anyString(), any(ClickEvent.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        ClickEvent event = new ClickEvent("abc123", "shard-0", "192.168.1.1", "Mozilla/5.0", "https://google.com");
        publisher().publish(event);

        ArgumentCaptor<ClickEvent> captor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());

        assertEquals("192.168.1.1", captor.getValue().ipAddress());
        assertEquals("Mozilla/5.0", captor.getValue().userAgent());
        assertEquals("https://google.com", captor.getValue().referer());
    }

    @Test
    void publish_kafkaFailure_doesNotThrow() {
        when(kafkaTemplate.send(anyString(), anyString(), any(ClickEvent.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(
                        new RuntimeException("Kafka down")));

        ClickEvent event = new ClickEvent("abc123", "shard-0");

        assertDoesNotThrow(() -> publisher().publish(event));
        verify(urlCache).recordClick("abc123", "shard-0");
    }

    @Test
    void clickEvent_shardIsolation() {
        ClickEvent event1 = new ClickEvent("abc", "shard-0");
        ClickEvent event2 = new ClickEvent("abc", "shard-1");

        assertNotEquals(event1.shardId(), event2.shardId());
    }
}
