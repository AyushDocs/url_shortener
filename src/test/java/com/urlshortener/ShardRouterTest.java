package com.urlshortener;

import com.urlshortener.distributed.config.ShardProperties;
import com.urlshortener.distributed.routing.ShardRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ShardRouterTest {

    private ShardRouter shardRouter;

    @BeforeEach
    void setUp() {
        ShardProperties props = new ShardProperties(
                List.of(
                        new ShardProperties.Shard("shard-0", "jdbc:postgresql://localhost:5432/db0", "user", "pass", "org.postgresql.Driver"),
                        new ShardProperties.Shard("shard-1", "jdbc:postgresql://localhost:5433/db1", "user", "pass", "org.postgresql.Driver"),
                        new ShardProperties.Shard("shard-2", "jdbc:postgresql://localhost:5434/db2", "user", "pass", "org.postgresql.Driver")
                ),
                150
        );
        shardRouter = new ShardRouter(props);
        shardRouter.init();
    }

    @Test
    void route_returnsKnownShard() {
        Set<String> validShards = Set.of("shard-0", "shard-1", "shard-2");
        for (int i = 0; i < 1000; i++) {
            String shard = shardRouter.route("code-" + i);
            assertTrue(validShards.contains(shard),
                    "Route returned unknown shard: " + shard);
        }
    }

    @Test
    void route_isDeterministic() {
        String key = "aZ91xK";
        String first = shardRouter.route(key);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, shardRouter.route(key));
        }
    }

    @Test
    void route_distributesAcrossAllShards() {
        Map<String, Integer> distribution = new java.util.HashMap<>();
        for (int i = 0; i < 9000; i++) {
            String shard = shardRouter.route("code-" + i);
            distribution.merge(shard, 1, Integer::sum);
        }

        assertEquals(3, distribution.size(), "All 3 shards should receive keys");
        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            assertTrue(entry.getValue() > 1500,
                    "Shard " + entry.getKey() + " got only " + entry.getValue());
        }
    }

    @Test
    void getShard_returnsCorrectConfig() {
        ShardProperties.Shard shard0 = shardRouter.getShard("shard-0");
        assertNotNull(shard0);
        assertEquals("shard-0", shard0.name());
        assertEquals("jdbc:postgresql://localhost:5432/db0", shard0.url());
    }

    @Test
    void getAllShardNames_returnsAll() {
        List<String> names = shardRouter.getAllShardNames();
        assertEquals(3, names.size());
        assertTrue(names.containsAll(Set.of("shard-0", "shard-1", "shard-2")));
    }

    @Test
    void getShardCount_matchesConfig() {
        assertEquals(3, shardRouter.getShardCount());
    }

    @Test
    void sameKeyRoutesSameShard() {
        String key = "abc123";
        String first = shardRouter.route(key);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, shardRouter.route(key),
                    "Same key must always route to the same shard");
        }
    }
}
