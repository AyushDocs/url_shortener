package com.urlshortener.distributed.routing;

public final class ShardRoutingContext {

    private static final ThreadLocal<String> CURRENT_SHARD = new ThreadLocal<>();

    private ShardRoutingContext() {}

    public static void setShard(String shardName) {
        CURRENT_SHARD.set(shardName);
    }

    public static String getShard() {
        return CURRENT_SHARD.get();
    }

    public static void clear() {
        CURRENT_SHARD.remove();
    }

    public static boolean hasShard() {
        return CURRENT_SHARD.get() != null;
    }
}
