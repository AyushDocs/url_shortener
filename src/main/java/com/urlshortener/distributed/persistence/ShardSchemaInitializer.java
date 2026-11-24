package com.urlshortener.distributed.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Creates the {@code urls} table and indexes on every shard at startup.
 *
 * <p>Hibernate's {@code hbm2ddl} runs against the routing DataSource, which resolves to the
 * default shard whenever no shard is bound to the thread, so an automatic schema update only
 * ever reached shard-0. DDL is disabled on the EntityManagerFactory and applied here, once per
 * shard, with idempotent statements.
 */
@Component
public class ShardSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShardSchemaInitializer.class);

    private static final String[] DDL = {
            """
            CREATE TABLE IF NOT EXISTS urls (
                id uuid PRIMARY KEY,
                short_code varchar(16) NOT NULL,
                original_url text NOT NULL,
                created_at timestamptz NOT NULL,
                expires_at timestamptz,
                active boolean NOT NULL DEFAULT true,
                click_count bigint NOT NULL DEFAULT 0,
                shard_id varchar(32) NOT NULL,
                CONSTRAINT uk_urls_short_code UNIQUE (short_code)
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_expires_at ON urls (expires_at)",
            "CREATE INDEX IF NOT EXISTS idx_active ON urls (active)",
            "CREATE INDEX IF NOT EXISTS idx_shard_id ON urls (shard_id)"
    };

    private final Map<String, DataSource> shardDataSources;

    public ShardSchemaInitializer(
            @Qualifier("shardDataSources") Map<String, DataSource> shardDataSources) {
        this.shardDataSources = shardDataSources;
    }

    @Override
    public void run(ApplicationArguments args) {
        shardDataSources.forEach((shardName, dataSource) -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            try {
                for (String statement : DDL) {
                    jdbc.execute(statement);
                }
                log.info("Initialized schema on shard '{}'", shardName);
            } catch (Exception ex) {
                log.error("Failed to initialize schema on shard '{}': {}",
                        shardName, ex.getMessage());
            }
        });
    }
}
