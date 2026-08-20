package com.urlshortener.distributed.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import com.urlshortener.distributed.config.ShardProperties;
import com.urlshortener.distributed.routing.ShardRouter;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(ShardProperties.class)
public class MultiDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(MultiDataSourceConfig.class);

    private final ShardRouter shardRouter;

    public MultiDataSourceConfig(ShardRouter shardRouter) {
        this.shardRouter = shardRouter;
    }

    @Bean
    public Map<String, DataSource> shardDataSources() {
        Map<String, DataSource> dataSources = new HashMap<>();

        for (String shardName : shardRouter.getAllShardNames()) {
            ShardProperties.Shard shard = shardRouter.getShard(shardName);
            HikariConfig config = new HikariConfig();
            config.setPoolName("hikari-pool-" + shardName);
            config.setJdbcUrl(shard.url());
            config.setUsername(shard.username());
            config.setPassword(shard.password());
            config.setDriverClassName(shard.driverClassName());
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.setIdleTimeout(30000);
            config.setConnectionTimeout(5000);

            dataSources.put(shardName, new HikariDataSource(config));
            log.info("Configured DataSource for shard '{}' -> {}", shardName, shard.url());
        }

        return dataSources;
    }

    @Bean
    public DataSource routingDataSource() {
        Map<String, DataSource> shardDataSources = shardDataSources();

        RoutingDataSource routingDataSource = new RoutingDataSource();
        routingDataSource.setTargetDataSources(new HashMap<>(shardDataSources));
        routingDataSource.setDefaultTargetDataSource(
                shardDataSources.values().iterator().next());
        return routingDataSource;
    }

    @Primary
    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            @Qualifier("routingDataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.urlshortener.domain");
        em.setPersistenceUnitName("sharded-persistence");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(vendorAdapter);

        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("hibernate.hbm2ddl.auto", "update");
        properties.put("hibernate.format_sql", "true");
        properties.put("hibernate.jdbc.batch_size", "25");
        properties.put("hibernate.jdbc.fetch_size", "50");
        em.setJpaPropertyMap(properties);

        return em;
    }

    @Primary
    @Bean
    public PlatformTransactionManager transactionManager(
            @Qualifier("entityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
