package com.urlshortener.distributed.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.shards")
public record ShardProperties(
        List<Shard> shards,
        int virtualNodes
) {
    public record Shard(
            String name,
            String url,
            String username,
            String password,
            String driverClassName
    ) {}
}
