package com.urlshortener;

import com.urlshortener.distributed.routing.ShardRoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShardRoutingContextTest {

    @AfterEach
    void cleanup() {
        ShardRoutingContext.clear();
    }

    @Test
    void setAndGet_shard() {
        ShardRoutingContext.setShard("shard-1");
        assertEquals("shard-1", ShardRoutingContext.getShard());
    }

    @Test
    void clear_removesShard() {
        ShardRoutingContext.setShard("shard-1");
        ShardRoutingContext.clear();
        assertNull(ShardRoutingContext.getShard());
    }

    @Test
    void hasShard_reflectsState() {
        assertFalse(ShardRoutingContext.hasShard());
        ShardRoutingContext.setShard("shard-0");
        assertTrue(ShardRoutingContext.hasShard());
        ShardRoutingContext.clear();
        assertFalse(ShardRoutingContext.hasShard());
    }

    @Test
    void setShard_overwritesPrevious() {
        ShardRoutingContext.setShard("shard-0");
        ShardRoutingContext.setShard("shard-2");
        assertEquals("shard-2", ShardRoutingContext.getShard());
    }

    @Test
    void threadIsolation() throws Exception {
        ShardRoutingContext.setShard("main-shard");

        Thread other = new Thread(() -> {
            assertNull(ShardRoutingContext.getShard(),
                    "Other thread should not see main thread's shard");
            ShardRoutingContext.setShard("other-shard");
        });
        other.start();
        other.join();

        assertEquals("main-shard", ShardRoutingContext.getShard(),
                "Main thread should not be affected by other thread");
    }
}
