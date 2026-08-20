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
        urlCache.putUrl("abc", "https://example.com");
        assertEquals("https://example.com", urlCache.getUrl("abc").orElse(null));
    }

    @Test
    void cacheMiss_returnsEmpty() {
        assertTrue(urlCache.getUrl("nonexistent").isEmpty());
    }

    @Test
    void removeUrl_clearsEntry() {
        urlCache.putUrl("abc", "https://example.com");
        urlCache.removeUrl("abc");
        assertTrue(urlCache.getUrl("abc").isEmpty());
    }

    @Test
    void clickTracking_works() {
        urlCache.recordClick("abc");
        urlCache.recordClick("abc");
        urlCache.recordClick("abc");
        assertEquals(3, urlCache.getClickCount("abc"));
    }

    @Test
    void flushClicks_resetsCount() {
        urlCache.recordClick("abc");
        urlCache.recordClick("abc");
        urlCache.flushClicks("abc");
        assertEquals(0, urlCache.getClickCount("abc"));
    }

    @SpringBootApplication
    static class TestConfig {}
}
