package com.urlshortener.scheduler;

import com.urlshortener.distributed.persistence.ShardedUrlRepository;
import com.urlshortener.distributed.routing.ShardRoutingContext;
import com.urlshortener.distributed.routing.ShardRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ExpiredUrlCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiredUrlCleanupScheduler.class);

    private final ShardedUrlRepository shardedUrlRepository;
    private final ShardRouter shardRouter;

    public ExpiredUrlCleanupScheduler(ShardedUrlRepository shardedUrlRepository,
                                       ShardRouter shardRouter) {
        this.shardedUrlRepository = shardedUrlRepository;
        this.shardRouter = shardRouter;
    }

    // initialDelay: the per-shard schema initializer runs after startup, so don't query before it finishes.
    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void deactivateExpiredUrls() {
        int deactivated = shardedUrlRepository.deactivateExpiredUrls(Instant.now());
        if (deactivated > 0) {
            log.info("Deactivated {} expired URLs across {} shards", deactivated, shardRouter.getShardCount());
        }
    }
}
