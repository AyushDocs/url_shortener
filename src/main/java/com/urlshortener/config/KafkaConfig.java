package com.urlshortener.config;

import com.urlshortener.analytics.ClickEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", bootstrapServers);
        config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        config.put("value.serializer", "org.springframework.kafka.support.serializer.JsonSerializer");
        config.put("acks", "all");
        config.put("retries", 3);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ConsumerFactory<String, ClickEvent> consumerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id:url-shortener-analytics}") String groupId) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.urlshortener.analytics");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ClickEvent.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, true);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ClickEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, ClickEvent> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, ClickEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }

    @Bean
    public NewTopic clickEventsTopic(
            @Value("${spring.kafka.topic.click-events:click-events}") String topicName,
            @Value("${spring.kafka.topic.click-events-partitions:3}") int partitions) {
        // Partition count caps consumer parallelism: one instance per partition in the group.
        return TopicBuilder.name(topicName).partitions(partitions).replicas(1).build();
    }
}
