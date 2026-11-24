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

### Kubernetes (multiple app instances)

The app tier is stateless — shard routing is computed in-process and all shared state is in Redis, PostgreSQL and Kafka — so replicas can be scaled horizontally and any replica can serve any read. `k8s/` holds the whole stack (3 shard StatefulSets, Redis, ZooKeeper, Kafka) plus a 3–12 replica Deployment with a `ClusterIP` Service, CPU-based HPA, PodDisruptionBudget, startup/readiness/liveness probes and a rolling update that keeps capacity available.

```bash
# Build the image and make it visible to your cluster
docker build -t url-shortener:latest .
minikube image load url-shortener:latest    # or: kind load docker-image url-shortener:latest

# Deploy everything
kubectl apply -k k8s
kubectl -n urlshortener rollout status deployment/url-shortener

# Reach the load-balanced Service
kubectl -n urlshortener port-forward svc/url-shortener 8080:80
curl http://localhost:8080/actuator/health

# Scale reads, then watch the Service spread them across pods
kubectl -n urlshortener scale deployment/url-shortener --replicas=6
kubectl -n urlshortener get pods -l app=url-shortener -o wide
kubectl -n urlshortener get hpa url-shortener
```

`./scripts/k8s-demo.sh` automates the demo: deploy, benchmark reads on 3 replicas, print the per-pod redirect counters, scale to 6, benchmark again.

Notes for the Kubernetes profile:
- The per-pod Hikari pool is `APP_SHARDS_CONNECTION_POOL_SIZE` (4 in `k8s/config.yaml`), so 12 replicas × 3 shards × 4 connections stays under the shard's `max_connections=200`. Raise both together.
- Click events use 6 topic partitions (`SPRING_KAFKA_TOPIC_CLICK_EVENTS_PARTITIONS`), so up to 6 replicas consume analytics in parallel. A topic that already exists keeps its partition count — recreate it or add partitions with `kafka-topics.sh`.
- The HPA needs a metrics-server in the cluster; without one it reports unknown metrics and replicas must be scaled manually. When it is installed, the HPA owns the replica count and may override a manual `kubectl scale`.
- Rate limiting and click counters live in Redis, so limits stay global across replicas and each pod's analytics flush is atomic (`GETDEL`) — replicas never double-count a click. The expiration cleanup scheduler runs on every replica; it is idempotent, so extra runs only add read load.
- Images built for Docker Compose are not automatically visible inside minikube/kind; the demo script loads them.

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

### Test results

`mvn test` (default run, integration tests disabled):

```
Tests run: 95, Failures: 0, Errors: 0, Skipped: 11
BUILD SUCCESS
```

Breakdown by class (from `target/surefire-reports`):

| Class | Tests run | Failures | Errors | Skipped |
|---|---|---|---|---|
| ConsistentHashRingTest (4 nested classes) | 10 | 0 | 0 | 0 |
| UrlTest | 10 | 0 | 0 | 0 |
| UrlServiceTest | 8 | 0 | 0 | 0 |
| ShardedUrlRepositoryTest | 6 | 0 | 0 | 0 |
| ShortCodeGeneratorTest | 9 | 0 | 0 | 0 |
| UrlManagementControllerTest | 8 | 0 | 0 | 0 |
| UrlRedirectControllerTest | 5 | 0 | 0 | 0 |
| ClickEventPublisherTest | 5 | 0 | 0 | 0 |
| ShardRoutingContextTest | 5 | 0 | 0 | 0 |
| ShardRouterTest | 7 | 0 | 0 | 0 |
| ExpirationTest | 6 | 0 | 0 | 0 |
| ConcurrencyTest | 4 | 0 | 0 | 0 |
| UrlCacheIntegrationTest | 6 | 0 | 0 | 6 |
| RateLimiterIntegrationTest | 4 | 0 | 0 | 4 |
| UrlShortenerApplicationTests | 1 | 0 | 0 | 1 |

The 11 skips are the Testcontainers-based integration tests, enabled with
`mvn test -DrunIntegrationTests=true` (they need a Docker daemon):

```
Tests run: 87, Failures: 0, Errors: 3
BUILD FAILURE
```

The 3 errors are environment issues, not code failures: Testcontainers 1.20.1 uses the
docker-java client API version 1.32 while the Docker daemon here requires a minimum of
1.40 ("client version 1.32 is too old. Please upgrade your client to a newer version").
Setting `DOCKER_API_VERSION=1.45` does not change it. All unit, controller and hash-ring
tests pass.

## Benchmarks & Test Results

### Load benchmarks (`scripts/bench-run.sh`)

`scripts/bench-run.sh` port-forwards the deployed Service, waits for
`/actuator/health`, runs `scripts/benchmark.py` and writes the JSON result to the path
given as the last argument (default `results/benchmark.json`):

```bash
./scripts/bench-run.sh redirect 30 100 scripts/results/k8s-redirect-3.json
./scripts/bench-run.sh redirect 30 100 scripts/results/k8s-redirect-4.json
./scripts/bench-run.sh create   20 20  scripts/results/k8s-create.json
./scripts/bench-run.sh mixed    30 100 scripts/results/k8s-mixed.json
```

Environment for these runs: minikube v1.39.0 (context `minikube`), kubectl v1.37.2,
Docker 29.9.3, host Java 21 (the image runs Java 17), 3–4 url-shortener replicas on a
minikube VM with 8 vCPU. Absolute numbers are indicative of the stack, not of
high-performance hardware. All runs had 0 transport errors; `--json` files under
`scripts/results/` hold the full per-second series too.

| Result file | Scenario | Replicas | Total | Throughput | p50 | p95 | p99 | Status |
|---|---|---|---|---|---|---|---|---|
| `k8s-redirect-3.json` | redirect | 3 | 5,675 | 187.4 req/s | 507.1 ms | 775.1 ms | 1,013.1 ms | 302 ×5,675 |
| `k8s-redirect-4.json` | redirect | 4 | 11,664 | 387.2 req/s | 255.6 ms | 391.3 ms | 438.5 ms | 302 ×11,664 |
| `k8s-create.json` | create | 4 | 623 | 29.9 req/s | 576.1 ms | 1,246.0 ms | 1,615.8 ms | 201 ×623 |
| `k8s-mixed.json` | mixed | 4 | 7,755 | 244.0 req/s | 318.0 ms | 910.0 ms | 1,521.4 ms | 302 ×6,979 + 201 ×776 |

### k8s demo run (`scripts/k8s-demo.sh`): 3 replicas, then scaled to 6

| Phase | Total | Throughput | p50 | p95 | p99 | Status |
|---|---|---|---|---|---|---|
| 3 replicas (redirect, 30s @ 100) | 9,007 | 297.0 req/s | 313.0 ms | 495.3 ms | 581.4 ms | 302 ×9,007 |
| 6 replicas (redirect, 30s @ 100) | 4,855 | 160.0 req/s | 586.1 ms | 1,105.0 ms | 1,230.7 ms | 302 ×4,855 |

The 6-replica phase was slower in this environment (higher latency, lower throughput).
The demo uses only 25 short codes, so the read path is served from the hot cache and
the bottleneck shifts to Redis and the shared state of the minikube VM before more
replicas help; this is why the run measures capacity, not peak scaling numbers.

### Deployment notes for minikube

Two issues found while deploying and fixed in `k8s/kafka.yaml` (single-broker Kafka):

- `KAFKA_PORT` is now set to `""`. Kubernetes injects the legacy service env var
  `KAFKA_PORT` into every pod in the namespace (a Service named `kafka` exists), and
  cp-kafka 7.6.0 exits 1 on any set `KAFKA_PORT` ("port is deprecated").
- The readiness probe now uses `tcpSocket: 9092` instead of the exec-based
  `kafka-broker-api-versions` — the exec command hangs against this broker while the
  broker binds `0.0.0.0:9092` and answers TCP connections.

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
