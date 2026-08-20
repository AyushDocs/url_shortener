package com.urlshortener;

import com.urlshortener.domain.Url;
import com.urlshortener.distributed.hashring.ConsistentHashRing;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class UrlTest {

    @Test
    void isExpired_returnsFalse_whenNoExpiration() {
        Url url = new Url("abc", "https://example.com", null);
        assertFalse(url.isExpired());
    }

    @Test
    void isExpired_returnsFalse_whenFutureExpiration() {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        Url url = new Url("abc", "https://example.com", future);
        assertFalse(url.isExpired());
    }

    @Test
    void isExpired_returnsTrue_whenPastExpiration() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        Url url = new Url("abc", "https://example.com", past);
        assertTrue(url.isExpired());
    }

    @Test
    void incrementClickCount_increments() {
        Url url = new Url("abc", "https://example.com", null);
        assertEquals(0, url.getClickCount());
        url.incrementClickCount();
        assertEquals(1, url.getClickCount());
        url.incrementClickCount();
        assertEquals(2, url.getClickCount());
    }

    @Test
    void constructor_setsFields() {
        Instant expires = Instant.now().plusSeconds(3600);
        Url url = new Url("abc", "https://example.com", expires);
        assertEquals("abc", url.getShortCode());
        assertEquals("https://example.com", url.getOriginalUrl());
        assertEquals(expires, url.getExpiresAt());
        assertTrue(url.isActive());
        assertEquals(0, url.getClickCount());
    }

    @Test
    void id_isUuid() {
        Url url = new Url("abc", "https://example.com", null);
        assertNull(url.getId());
        UUID id = UUID.randomUUID();
        url.setId(id);
        assertEquals(id, url.getId());
    }

    @Test
    void shardId_isStored() {
        Url url = new Url("abc", "https://example.com", null);
        url.setShardId("shard-1");
        assertEquals("shard-1", url.getShardId());
    }

    @Test
    void consistentHashRing_sameKeyAlwaysRoutesSame() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                List.of("shard-0", "shard-1", "shard-2"), 150);

        String key = "aZ91xK";
        String first = ring.getNode(key);
        for (int i = 0; i < 1000; i++) {
            assertEquals(first, ring.getNode(key));
        }
    }

    @Test
    void consistentHashRing_distribution() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                List.of("shard-0", "shard-1", "shard-2"), 150);

        Map<String, Integer> distribution = java.util.stream.IntStream.range(0, 10000)
                .mapToObj(i -> "code-" + i)
                .collect(Collectors.groupingBy(ring::getNode, Collectors.summingInt(c -> 1)));

        assertEquals(3, distribution.size());
        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            assertTrue(entry.getValue() > 2000,
                    "Shard " + entry.getKey() + " got only " + entry.getValue());
        }
    }

    @Test
    void consistentHashRing_addRemoveNode() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(
                List.of("shard-0", "shard-1"), 150);
        int sizeBefore = ring.getRingSize();

        ring.addNode("shard-2");
        assertTrue(ring.getRingSize() > sizeBefore,
                "Adding a node should increase ring size");

        ring.removeNode("shard-2");
        assertEquals(sizeBefore, ring.getRingSize(),
                "Removing node should restore original ring size");
    }
}
