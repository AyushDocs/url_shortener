package com.urlshortener;

import com.urlshortener.cache.UrlCache;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class UrlCacheIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("urlshortener")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("app.shards.virtual-nodes", () -> 150);
        registry.add("app.shards.shards[0].name", () -> "shard-0");
        registry.add("app.shards.shards[0].url", postgres::getJdbcUrl);
        registry.add("app.shards.shards[0].username", postgres::getUsername);
        registry.add("app.shards.shards[0].password", postgres::getPassword);
        registry.add("app.shards.shards[0].driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private UrlCache urlCache;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        urlRepository.deleteAll();
    }

    @Test
    void cacheAndRetrieveUrl() {
        urlCache.putUrl("abc", "https://example.com", "shard-0");
        assertEquals("https://example.com", urlCache.getUrl("abc", "shard-0").orElse(null));
    }

    @Test
    void cacheMiss_returnsEmpty() {
        assertTrue(urlCache.getUrl("nonexistent", "shard-0").isEmpty());
    }

    @Test
    void removeUrl_clearsEntry() {
        urlCache.putUrl("abc", "https://example.com", "shard-0");
        urlCache.removeUrl("abc", "shard-0");
        assertTrue(urlCache.getUrl("abc", "shard-0").isEmpty());
    }

    @Test
    void clickTracking_works() {
        urlCache.recordClick("abc", "shard-0");
        urlCache.recordClick("abc", "shard-0");
        urlCache.recordClick("abc", "shard-0");
        assertEquals(3, urlCache.getClickCount("abc", "shard-0"));
    }

    @Test
    void flushClicks_resetsCount() {
        urlCache.recordClick("abc", "shard-0");
        urlCache.recordClick("abc", "shard-0");
        urlCache.flushClicks("abc", "shard-0");
        assertEquals(0, urlCache.getClickCount("abc", "shard-0"));
    }

    @Test
    void differentShards_areIsolated() {
        urlCache.putUrl("abc", "https://shard0.com", "shard-0");
        urlCache.putUrl("abc", "https://shard1.com", "shard-1");

        assertEquals("https://shard0.com", urlCache.getUrl("abc", "shard-0").orElse(null));
        assertEquals("https://shard1.com", urlCache.getUrl("abc", "shard-1").orElse(null));
    }

    @SpringBootApplication
    static class TestConfig {}
}
