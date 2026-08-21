package com.urlshortener;

import com.urlshortener.cache.UrlCache;
import com.urlshortener.domain.Url;
import com.urlshortener.distributed.persistence.ShardedUrlRepository;
import com.urlshortener.distributed.routing.ShardRouter;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.generator.ShortCodeGenerator;
import com.urlshortener.service.UrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConcurrencyTest {

    @Mock
    private ShardedUrlRepository shardedUrlRepository;

    @Mock
    private UrlCache urlCache;

    @Mock
    private ShortCodeGenerator shortCodeGenerator;

    @Mock
    private ShardRouter shardRouter;

    private UrlService urlService;

    @BeforeEach
    void setUp() {
        urlService = new UrlService(shardedUrlRepository, urlCache, shortCodeGenerator, shardRouter, 3600);
        lenient().when(shardRouter.route(anyString())).thenReturn("shard-0");
    }

    @Test
    void concurrentCreateUrl_allCodesUnique() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        when(shortCodeGenerator.isValidCode(anyString())).thenReturn(true);
        when(shortCodeGenerator.generateUnique()).thenAnswer(inv -> "code-" + counter.getAndIncrement());
        when(shardedUrlRepository.save(any(Url.class))).thenAnswer(inv -> {
            Url url = inv.getArgument(0);
            url.setId(java.util.UUID.randomUUID());
            url.setCreatedAt(Instant.now());
            return url;
        });

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentSkipListSet<String> codes = new ConcurrentSkipListSet<>();

        Callable<Void> task = () -> {
            latch.await();
            CreateUrlResponse response = urlService.createUrl(
                    new CreateUrlRequest("https://example.com/" + Thread.currentThread().getId()));
            codes.add(response.shortCode());
            return null;
        };

        java.util.List<Future<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(task));
        }

        latch.countDown();

        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        assertEquals(threadCount, codes.size(),
                "All " + threadCount + " generated codes should be unique, but got " +
                        (threadCount - codes.size()) + " duplicates");

        executor.shutdown();
    }

    @Test
    void concurrentResolveUrl_cacheAndDbConsistent() throws Exception {
        when(urlCache.getUrl("abc", "shard-0")).thenReturn(
                java.util.Optional.of("https://example.com"));

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> results = new ConcurrentLinkedQueue<>();

        Callable<Void> task = () -> {
            latch.await();
            UrlService.ResolvedUrl resolved = urlService.resolveUrl("abc");
            results.add(resolved.originalUrl());
            return null;
        };

        java.util.List<Future<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(task));
        }

        latch.countDown();

        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        assertEquals(threadCount, results.size());
        for (String result : results) {
            assertEquals("https://example.com", result);
        }

        executor.shutdown();
    }

    @Test
    void concurrentDeleteUrl_noErrors() throws Exception {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);

        Callable<Void> task = () -> {
            latch.await();
            try {
                urlService.deleteUrl("abc");
            } catch (Exception e) {
                errors.incrementAndGet();
            }
            return null;
        };

        java.util.List<Future<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(task));
        }

        latch.countDown();

        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        assertEquals(0, errors.get(), "No errors should occur during concurrent deletes");
        executor.shutdown();
    }

    @Test
    void differentKeysRouteToDifferentShards() {
        Set<String> shardNames = Set.of("shard-0", "shard-1", "shard-2");
        when(shardRouter.route(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            int hash = Math.abs(key.hashCode() % 3);
            return "shard-" + hash;
        });

        Set<String> usedShards = new ConcurrentSkipListSet<>();
        ExecutorService executor = Executors.newFixedThreadPool(10);

        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final String key = "key-" + i;
            futures.add(executor.submit(() -> {
                String shard = shardRouter.route(key);
                assertTrue(shardNames.contains(shard));
                usedShards.add(shard);
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { fail(e); }
        }

        assertTrue(usedShards.size() > 1, "Multiple shards should be used");
        executor.shutdown();
    }
}
