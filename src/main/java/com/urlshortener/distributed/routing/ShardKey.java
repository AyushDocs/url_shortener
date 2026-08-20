package com.urlshortener.distributed.routing;

public enum ShardKey {
    SHORT_CODE,
    ORIGINAL_URL,
    CREATED_AT,
    EXPIRES_AT,
    SHARD_ID
}
