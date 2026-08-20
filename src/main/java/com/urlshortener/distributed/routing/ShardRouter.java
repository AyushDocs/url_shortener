package com.urlshortener.distributed.routing;

import com.urlshortener.distributed.hashring.ConsistentHashRing;
import com.urlshortener.distributed.config.ShardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ShardRouter {

    private static final Logger log = LoggerFactory.getLogger(ShardRouter.class);

    private final ShardProperties shardProperties;
    private ConsistentHashRing<String> hashRing;
    private Map<String, ShardProperties.Shard> shardMap;

    public ShardRouter(ShardProperties shardProperties) {
        this.shardProperties = shardProperties;
    }

    @PostConstruct
    public void init() {
        List<String> shardNames = shardProperties.shards().stream()
                .map(ShardProperties.Shard::name)
                .toList();

        hashRing = new ConsistentHashRing<>(shardNames, shardProperties.virtualNodes());

        shardMap = shardProperties.shards().stream()
                .collect(Collectors.toMap(ShardProperties.Shard::name, s -> s));

        log.info("Shard router initialized with {} shards, {} virtual nodes per shard",
                shardNames.size(), shardProperties.virtualNodes());

        shardNames.forEach(name ->
                log.info("  Shard '{}' mapped to {}", name, shardMap.get(name).url()));
    }

    public String route(String key) {
        return hashRing.getNode(key);
    }

    public ShardProperties.Shard getShard(String shardName) {
        return shardMap.get(shardName);
    }

    public List<String> getAllShardNames() {
        return List.copyOf(shardMap.keySet());
    }

    public int getShardCount() {
        return shardMap.size();
    }
}
