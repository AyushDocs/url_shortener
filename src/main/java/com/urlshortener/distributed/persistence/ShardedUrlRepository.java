package com.urlshortener.distributed.persistence;

import com.urlshortener.domain.Url;
import com.urlshortener.distributed.routing.ShardRouter;
import com.urlshortener.distributed.routing.ShardRoutingContext;
import com.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class ShardedUrlRepository {

    private static final Logger log = LoggerFactory.getLogger(ShardedUrlRepository.class);

    private final UrlRepository urlRepository;
    private final ShardRouter shardRouter;

    public ShardedUrlRepository(UrlRepository urlRepository, ShardRouter shardRouter) {
        this.urlRepository = urlRepository;
        this.shardRouter = shardRouter;
    }

    public Optional<Url> findByShortCode(String shortCode) {
        String shard = shardRouter.route(shortCode);
        try {
            ShardRoutingContext.setShard(shard);
            return urlRepository.findByShortCodeAndActiveTrue(shortCode);
        } finally {
            ShardRoutingContext.clear();
        }
    }

    public boolean existsByShortCode(String shortCode) {
        String shard = shardRouter.route(shortCode);
        try {
            ShardRoutingContext.setShard(shard);
            return urlRepository.existsByShortCode(shortCode);
        } finally {
            ShardRoutingContext.clear();
        }
    }

    public Url save(Url url) {
        String shard = shardRouter.route(url.getShortCode());
        try {
            ShardRoutingContext.setShard(shard);
            url.setShardId(shard);
            return urlRepository.save(url);
        } finally {
            ShardRoutingContext.clear();
        }
    }

    public void delete(String shortCode) {
        String shard = shardRouter.route(shortCode);
        try {
            ShardRoutingContext.setShard(shard);
            urlRepository.findByShortCodeAndActiveTrue(shortCode).ifPresent(url -> {
                url.setActive(false);
                urlRepository.save(url);
            });
        } finally {
            ShardRoutingContext.clear();
        }
    }

    public int incrementClickCount(String shortCode, long increment) {
        String shard = shardRouter.route(shortCode);
        try {
            ShardRoutingContext.setShard(shard);
            return urlRepository.incrementClickCount(shortCode, increment);
        } finally {
            ShardRoutingContext.clear();
        }
    }

    public int deactivateExpiredUrls(Instant now) {
        int total = 0;
        for (String shard : shardRouter.getAllShardNames()) {
            try {
                ShardRoutingContext.setShard(shard);
                total += urlRepository.deactivateExpiredUrls(now);
            } finally {
                ShardRoutingContext.clear();
            }
        }
        return total;
    }

    public List<Url> findAllOnShard(String shortCode) {
        String shard = shardRouter.route(shortCode);
        try {
            ShardRoutingContext.setShard(shard);
            return urlRepository.findByShortCodeAndActiveTrue(shortCode)
                    .map(List::of)
                    .orElse(List.of());
        } finally {
            ShardRoutingContext.clear();
        }
    }
}
