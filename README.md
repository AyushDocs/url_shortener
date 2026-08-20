# URL Shortener — System Design Project

A system-design-oriented URL shortener built with Spring Boot, demonstrating distributed-systems concerns: ID generation, collision handling, persistence, caching, concurrency, rate limiting, expiration, analytics, and horizontal scalability.

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
        │  Cache   │ │  DB   │ │ (analytics)│
        └──────────┘ └───────┘ └─────────┘
```

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

### Why Base62?

62 alphanumeric characters (`a-z`, `A-Z`, `0-9`). A 6-character code gives **56.8 billion** unique combinations — more than enough to avoid collisions for millions of URLs.

### How are collisions handled?

Two layers:

1. **Application** — generates random codes, checks DB for existence, retries up to 5 times
2. **Database** — `UNIQUE(short_code)` constraint rejects any duplicate insert

On collision, the application retries with a new random code. On exhausting retries, falls back to a deterministic Base62 encoding.

### Why PostgreSQL?

- ACID transactions for reliable URL creation
- `UNIQUE` constraint enforces collision-free codes at the database level
- Strong ecosystem for indexing and query optimization
- Horizontal scaling via read replicas

### Why Redis?

- Sub-millisecond reads for the hot path (redirect)
- Cache-aside pattern: cache miss → DB → populate cache
- TTL-based expiration aligns with URL expiry
- Sorted-set sliding window for rate limiting

### What happens on a cache miss?

```
GET /abc123
   │
   ├── Redis GET → MISS
   │
   ├── PostgreSQL query
   │
   ├── Redis SET (with TTL)
   │
   └── 302 redirect
```

### What happens if Redis goes down?

The system degrades gracefully — all redirects fall through to PostgreSQL. Performance degrades but the service stays up. This is why the DB is the source of truth for `expires_at`.

### How do you handle expiration?

- `expires_at` in PostgreSQL is the **source of truth**
- Redis TTL is an **optimization** — if Redis loses data, the DB still protects expired URLs
- Scheduled job deactivates expired URLs every 5 minutes

### How do you count millions of clicks?

Each redirect publishes a `ClickEvent` to Kafka asynchronously. A consumer aggregates clicks into the database. Redis holds real-time counts, flushed periodically. This avoids write bottlenecks on every redirect.

### How do you prevent rate limiting abuse?

Redis sliding window rate limiter:
- **100 requests/minute** for URL creation per IP
- **1000 requests/minute** for redirects per IP
- Exceeding returns `429 Too Many Requests`

### How would you scale to multiple regions?

1. Stateless app instances behind a load balancer
2. Redis Cluster for distributed caching
3. PostgreSQL read replicas for scaling reads
4. Kafka partitioning for analytics throughput
5. CDN/edge caching for hot short URLs

## System Design Evolution

| Phase | What | Why |
|-------|------|-----|
| **Phase 1** | Spring Boot + PostgreSQL | Core shortening and redirect |
| **Phase 2** | Redis cache-aside | Sub-millisecond reads |
| **Phase 3** | Rate limiting + concurrency handling | Reliability |
| **Phase 4** | Kafka analytics pipeline | Async click tracking |
| **Phase 5** | Prometheus + Grafana | Observability |
| **Phase 6** | Docker Compose | Deployable system |

## Quick Start

### Prerequisites

- Java 17+
- Docker & Docker Compose

### With Docker Compose (full stack)

```bash
docker compose up -d
```

Starts: PostgreSQL, Redis, Kafka, Zookeeper, Spring Boot app, Prometheus, Grafana.

- App: http://localhost:8080
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)

### Without Docker

```bash
# Start just PostgreSQL and Redis
docker compose up -d postgres redis kafka

# Run the app
mvn spring-boot:run
```

### Using the API

```bash
# Create
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com"}'

# Redirect
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

## Monitoring

Access Grafana at http://localhost:3000 with the pre-built dashboard showing:

- URLs created
- Total redirects
- Cache hit rate
- Analytics events published
- Redirect latency (p50, p95, p99)
- HTTP request rate
- Cache operations (hits/s, misses/s, updates/s)

## Metrics Exposed

| Metric | Description |
|--------|-------------|
| `url.created.total` | URLs created |
| `url.redirect.total` | Redirects served |
| `url.cache.hit.total` | Cache hits |
| `url.cache.miss.total` | Cache misses |
| `url.redirect.duration` | Redirect latency histogram |
| `url.analytics.published.total` | Click events published to Kafka |
| `http.server.requests` | HTTP request metrics |

## Tech Stack

- **Spring Boot 4.1** — Web, Data JPA, Data Redis, Kafka, Actuator, Validation
- **PostgreSQL 16** — Persistent storage
- **Redis 7** — Caching + rate limiting
- **Apache Kafka** — Async analytics pipeline
- **Micrometer + Prometheus** — Metrics
- **Grafana** — Dashboards
- **Testcontainers** — Integration tests
- **Docker Compose** — Local development environment
