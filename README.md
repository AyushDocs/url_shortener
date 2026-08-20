# URL Shortener — Distributed System Design Project

A system-design-oriented URL shortener built with Spring Boot, demonstrating distributed-systems concerns: consistent-hashing sharding, cache-aside with Redis, async analytics via Kafka, sliding-window rate limiting, concurrent collision handling, expiration, and observability.

## Architecture

```
                    Load Balancer
                         │
             ┌───────────┼───────────┐
             ▼           ▼           ▼
           App-1       App-2       App-3    ← stateless Spring Boot instances
             │           │           │
             └───────────┼───────────┘
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
        ┌──────────┐ ┌───────┐ ┌─────────┐
        │  Redis   │ │Postgres│ │  Kafka  │
        │ (shard-  │ │shard-0 │ │(analytics)│
        │  aware)  │ │shard-1 │ │         │
        └──────────┘ │shard-2 │ └─────────┘
                     └───────┘
```

### Sharding Architecture

```
                       Short Code
                           │
                    ┌──────┴──────┐
                    │  Consistent  │
                    │  Hash Ring   │
                    │  (MD5 + 150  │
                    │  vnodes/shard)│
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
         ┌─────────┐ ┌─────────┐ ┌─────────┐
         │shard-0  │ │shard-1  │ │shard-2  │
         │Postgres │ │Postgres │ │Postgres │
         │:5432    │ │:5433    │ │:5434    │
         └─────────┘ └─────────┘ └─────────┘
```

URLs are deterministically partitioned across database shards using a consistent hash ring. Each shard is an independent PostgreSQL instance with its own `urls` table. The hash ring ensures that the same short code always maps to the same shard, enabling cache-friendly lookups.

**Key properties:**
- **Deterministic routing** — same short code always hits the same shard (no lookup table needed)
- **Even distribution** — 150 virtual nodes per shard via MD5 hashing ensures keys are spread uniformly
- **Graceful rebalancing** — adding/removing a shard only redistributes ~1/N of keys
- **Shard-local uniqueness** — `UNIQUE(short_code)` constraint per shard prevents duplicates

## API

### Create Short URL

```
POST /api/v1/urls

Request:
{
  "url": "https://example.com/very/long/url",
  "customCode": "mylink",       // optional
  "ttlSeconds": 3600            // optional
}

Response 201:
{
  "shortCode": "aZ91xK",
  "shortUrl": "https://sho.rt/aZ91xK",
  "originalUrl": "https://example.com/very/long/url",
  "createdAt": "2026-08-21T00:00:00Z",
  "expiresAt": "2026-08-21T01:00:00Z"
}
```

### Redirect

```
GET /{shortCode}
→ 302 Found → Location: https://example.com/very/long/url
```

### Get URL Details

```
GET /api/v1/urls/{shortCode}

Response 200:
{
  "shortCode": "aZ91xK",
  "shortUrl": "https://sho.rt/aZ91xK",
  "originalUrl": "https://example.com/very/long/url",
  "createdAt": "2026-08-21T00:00:00Z",
  "expiresAt": "2026-08-21T01:00:00Z",
  "active": true,
  "clickCount": 42
}
```

### Delete URL

```
DELETE /api/v1/urls/{shortCode}
→ 204 No Content
```

## System Design Decisions

### Why Consistent Hashing?

With 3 database shards, a consistent hash ring with 150 virtual nodes per shard maps short codes to databases deterministically. No central lookup table needed — every app instance computes the same mapping.

**What happens when you add a 4th shard?** Only ~1/4 of keys get redistributed. The other 3/4 stay on their existing shards. This is why consistent hashing beats modular hashing (`hash % N`).

### Why Base62?

62 alphanumeric characters. A 6-character code gives **56.8 billion** unique combinations. Short codes are evenly distributed across shards by the hash ring.

### How are collisions handled?

Two layers:
1. **Application** — generates random codes, checks the target shard for existence, retries up to 5 times
2. **Database** — `UNIQUE(short_code)` constraint on each shard rejects duplicates

### Why UUID primary keys instead of sequences?

Auto-increment sequences are shard-local — shard-0's ID 100 and shard-1's ID 100 are different URLs. UUIDs eliminate cross-shard ID conflicts and allow any shard to generate IDs independently.

### How does the cache work with sharding?

Cache keys include the shard name: `shard:shard-0:url:abc123`. This prevents key collisions across shards and allows per-shard TTL management. The flow:

```
GET /abc123
   │
   ├── Consistent hash → shard-1
   │
   ├── Redis GET("shard:shard-1:url:abc123")
   │      ├── HIT → redirect
   │      └── MISS → PostgreSQL (shard-1) → Redis SET → redirect
   │
   └── Publish ClickEvent(shard=shard-1) → Kafka
```

### What happens if one shard goes down?

Only URLs on that shard are affected. The other shards continue serving normally. The health check reports degraded status. This is better than a single-database failure where everything goes down.

### How do you count clicks across shards?

Each redirect publishes a `ClickEvent` tagged with `shardId`. Kafka consumers can aggregate per-shard or globally. Redis holds real-time counts per shard: `shard:shard-0:clicks:abc123`.

### How do you scale to more shards?

1. Add a new PostgreSQL instance to the config
2. Update `app.shards.shards` in `application.yaml`
3. The consistent hash ring auto-distributes — new keys spread to all shards, existing keys only migrate from neighboring shards
4. For full rebalancing, run a migration job that re-hashes existing URLs

## Project Structure

```
src/main/java/com/urlshortener/
├── UrlShortenerApplication.java
├── domain/
│   └── Url.java                          # UUID PK, shardId, clickCount
├── repository/
│   └── UrlRepository.java                # JPA interface
├── distributed/
│   ├── hashring/
│   │   └── ConsistentHashRing.java       # MD5 hash ring with virtual nodes
│   ├── routing/
│   │   ├── ShardRouter.java              # Maps keys → shards via hash ring
│   │   ├── ShardRoutingContext.java      # ThreadLocal shard context
│   │   └── ShardKey.java                 # Partition key enum
│   ├── persistence/
│   │   ├── MultiDataSourceConfig.java    # Per-shard DataSource + EntityManagerFactory
│   │   ├── RoutingDataSource.java        # AbstractRoutingDataSource
│   │   └── ShardedUrlRepository.java     # Shard-aware repository delegation
│   └── config/
│       └── ShardProperties.java          # @ConfigurationProperties
├── service/
│   ├── UrlService.java                   # Business logic with shard routing
│   └── AnalyticsService.java             # Click tracking facade
├── controller/
│   ├── UrlManagementController.java      # CRUD API
│   └── UrlRedirectController.java        # GET /{code} → 302 + analytics
├── cache/
│   └── UrlCache.java                     # Shard-aware Redis cache
├── analytics/
│   ├── ClickEvent.java                   # Kafka event with shardId
│   └── ClickEventPublisher.java          # Async publish
├── generator/
│   └── ShortCodeGenerator.java           # Base62 + collision retry
├── ratelimiter/
│   ├── SlidingWindowRateLimiter.java     # Redis sorted-set
│   └── RateLimitInterceptor.java         # HTTP interceptor
├── metrics/
│   └── UrlMetrics.java                   # Micrometer counters/timers
├── scheduler/
│   └── ExpiredUrlCleanupScheduler.java   # Cross-shard cleanup
├── config/
│   ├── RedisConfig.java
│   ├── KafkaConfig.java
│   ├── SecurityConfig.java
│   └── WebConfig.java
└── exception/
    ├── UrlNotFoundException.java
    ├── UrlExpiredException.java
    ├── DuplicateShortCodeException.java
    └── GlobalExceptionHandler.java
```

## Quick Start

### Prerequisites

- Java 17+
- Docker & Docker Compose

### With Docker Compose (full stack)

```bash
docker compose up -d
```

Starts: 3 PostgreSQL shards, Redis, Kafka, Zookeeper, Spring Boot app, Prometheus, Grafana.

### Without Docker

```bash
# Start infrastructure (adjust ports for your 3 shards)
docker compose up -d postgres-shard-0 postgres-shard-1 postgres-shard-2 redis kafka

# Run the app
mvn spring-boot:run
```

### Using the API

```bash
# Create
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com"}'

# Redirect (observe shard routing in logs)
curl -v http://localhost:8080/aZ91xK

# Details
curl http://localhost:8080/api/v1/urls/aZ91xK

# Delete
curl -X DELETE http://localhost:8080/api/v1/urls/aZ91xK
```

## Testing

```bash
# Unit tests (no Docker needed)
mvn test -Dtest="ShortCodeGeneratorTest,UrlTest,UrlServiceTest"

# All tests (needs Docker for Testcontainers)
mvn test
```

The test suite includes:
- Hash ring distribution verification (10K keys across 3 shards)
- Shard routing correctness
- Cache isolation between shards
- Collision handling
- Expiration logic

## Monitoring

Access Grafana at http://localhost:3000 (admin/admin) with pre-built dashboards.

## Tech Stack

- **Spring Boot 4.1** — Web, Data JPA, Data Redis, Kafka, Actuator, Validation
- **PostgreSQL 16** — 3 independent shards
- **Redis 7** — Shard-aware caching + rate limiting
- **Apache Kafka** — Async analytics pipeline
- **Consistent Hashing** — MD5 ring with 150 virtual nodes/shard
- **Micrometer + Prometheus** — Metrics
- **Grafana** — Dashboards
- **Testcontainers** — Integration tests
- **Docker Compose** — Local development environment
