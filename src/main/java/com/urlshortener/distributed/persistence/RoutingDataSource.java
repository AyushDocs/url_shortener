package com.urlshortener.distributed.persistence;

import com.urlshortener.distributed.routing.ShardRoutingContext;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class RoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return ShardRoutingContext.getShard();
    }
}
