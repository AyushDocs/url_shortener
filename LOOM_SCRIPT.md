# Loom Video Script — URL Shortener System Design

## Video Title
"Building a System-Design-Oriented URL Shortener in Spring Boot"

## Target Length
8–12 minutes

---

## Scene 1: Hook & Overview (0:00 – 0:45)

**On screen:** Architecture diagram

> "Most URL shortener tutorials stop at CRUD. This one doesn't. I built a URL shortener that demonstrates the actual distributed-systems concerns you'd discuss in a system-design interview: cache-aside patterns, async analytics pipelines, sliding-window rate limiting, concurrency handling, and observability. Let me walk you through the architecture."

**Key point:** This isn't a CRUD project — it's a system-design project with working code.

---

## Scene 2: Architecture Walkthrough (0:45 – 2:00)

**On screen:** Full architecture diagram (App → Redis → PostgreSQL, Kafka for analytics)

> "The system has three layers. The stateless Spring Boot app handles API requests. Redis sits in front of PostgreSQL as a cache-aside layer — this is the single biggest performance win. And Kafka handles analytics asynchronously so redirect latency never depends on write throughput."

> "The critical insight: the app instances are stateless. This means you can horizontally scale behind a load balancer. Redis and PostgreSQL handle all shared state."

**Show:** `compose.yaml` — the 6 services running together.

---

## Scene 3: ID Generation & Collision Handling (2:00 – 3:30)

**On screen:** `ShortCodeGenerator.java`

> "Short code generation is one of the most important design decisions. I'm using Base62 — 62 alphanumeric characters. A 6-character code gives 56.8 billion possible combinations."

> "But here's what most tutorials miss: you never rely solely on application-level collision checking. The database has a UNIQUE constraint on short_code. The app generates a random code, checks if it exists, and retries. If the insert fails due to a race condition, the DB constraint catches it and the app retries with a new code. That's a real concurrency story."

**Show:** The `generateUnique()` method with retry loop, and the entity's `@Column(unique = true)`.

---

## Scene 4: The Read Path — Cache-Aside (3:30 – 5:00)

**On screen:** `UrlService.resolveUrl()` + Redis flow diagram

> "The redirect path is the hot path — this is where performance matters. Here's the cache-aside pattern in action."

> "First, check Redis. If it's a hit, redirect immediately — sub-millisecond. If it's a miss, query PostgreSQL, cache the result in Redis with a TTL, then redirect."

> "Notice the TTL is computed from the URL's expiration time. If the URL expires in an hour, the cache entry expires in an hour too. Redis TTL is an optimization — the database is always the source of truth for expiration."

**Show:** The code flow — `urlCache.getUrl()` → `urlRepository.findByShortCodeAndActiveTrue()` → `urlCache.putUrl()`.

---

## Scene 5: Analytics Pipeline (5:00 – 6:30)

**On screen:** `ClickEventPublisher.java` + Kafka flow

> "Every time someone hits a short URL, we need to count that click. But updating the database on every redirect would be a write bottleneck at scale."

> "Instead, each redirect publishes a ClickEvent to Kafka asynchronously. The redirect returns immediately. An analytics worker consumes the events and aggregates them. Redis holds real-time click counts, which get flushed to the database periodically."

> "This is eventual consistency for analytics — and it's the right tradeoff. You don't need exact click counts on every request, but you do need fast redirects."

**Show:** The `ClickEventPublisher` fire-and-forget pattern, the Redis incrementor.

---

## Scene 6: Rate Limiting (6:30 – 7:45)

**On screen:** `SlidingWindowRateLimiter.java`

> "Rate limiting uses a Redis sorted set as a sliding window. Each request adds a timestamp to the sorted set, prunes entries outside the window, and counts what's left."

> "I have two policies: 100 creates per minute per IP, 1000 redirects per minute per IP. This runs as a Spring interceptor before the controller, so it applies uniformly."

**Show:** The sorted set operations, the interceptor registering on all paths.

---

## Scene 7: Concurrency & Expiration (7:45 – 9:00)

**On screen:** `ExpiredUrlCleanupScheduler.java` + concurrency notes

> "Expiration has two layers. The database is the source of truth — `expires_at` column. The scheduled job deactivates expired URLs every 5 minutes. Redis TTL is just an optimization."

> "For concurrency: if two requests simultaneously try to shorten the same URL, they get different random codes. But if by some miracle they generate the same code, the database UNIQUE constraint rejects the second insert, the application catches the DataIntegrityViolationException, and retries. No data corruption."

**Show:** The scheduler, the `isExpired()` check in the entity, the `DataIntegrityViolationException` catch in the service.

---

## Scene 8: Observability (9:00 – 10:00)

**On screen:** Grafana dashboard + Micrometer metrics

> "Every important operation is instrumented with Micrometer counters and timers. URLs created, redirects served, cache hits and misses, analytics events published, redirect latency at p50/p95/p99."

> "Prometheus scrapes the actuator endpoint. Grafana displays a pre-built dashboard. You can answer questions like: what's my cache hit ratio? What's my P95 redirect latency? Am I hitting rate limits?"

**Show:** The Grafana dashboard with live data.

---

## Scene 9: Docker & Deployment (10:00 – 10:45)

**On screen:** `docker compose up`

> "The entire stack runs with one command. PostgreSQL, Redis, Kafka, the Spring Boot app, Prometheus, and Grafana. Health checks ensure dependencies are ready before the app starts."

> "To go to production, you'd add a load balancer in front of multiple app instances, switch to Redis Cluster, and add PostgreSQL read replicas."

**Show:** `docker compose ps` with all services healthy.

---

## Scene 10: Distributed Sharding (11:30 – 13:00)

**On screen:** Consistent hash ring diagram + 3 PostgreSQL instances

> "Now the interesting part. What happens when one database can't handle all the traffic? You shard."

> "I'm using a consistent hash ring. Each short code is hashed with MD5, and the ring maps it to one of three PostgreSQL shards. 150 virtual nodes per shard ensure even distribution."

> "The critical properties: same short code always routes to the same shard — deterministic. Adding a 4th shard only redistributes 1/4 of keys — graceful rebalancing. And each shard has its own UNIQUE constraint, so collision handling still works locally."

**Show:** `ConsistentHashRing.java`, `ShardRouter.java`, the hash ring distribution test.

> "The routing uses ThreadLocal context. The ShardedUrlRepository sets the shard before each DB operation, and the RoutingDataSource — a Spring AbstractRoutingDataSource — picks the right connection pool."

**Show:** `RoutingDataSource.java`, `ShardedUrlRepository.java`.

> "The cache is shard-aware too. Keys are prefixed with the shard name: `shard:shard-0:url:abc123`. This prevents key collisions if two shards happen to have the same short code — which won't happen, but the defense is there."

**Show:** Redis CLI showing shard-prefixed keys.

> "The analytics events carry the shard ID. Kafka consumers can aggregate per-shard or globally. And the cleanup scheduler iterates all shards."

---

## Scene 11: Closing (13:00 – 13:45)

**On screen:** Project structure + key takeaways

> "This project answers the system-design interview questions: Why Base62? Why consistent hashing? Why UUID primary keys? What happens if one shard goes down? How do you count millions of clicks across shards? Every decision has a justification."

> "The code is on GitHub. Link in the description."

**Show:** GitHub repo URL.

---

## B-Roll / Screen Recording Notes

- Show `curl` commands hitting the API and getting responses
- Show logs with `[shard=shard-0]` / `[shard=shard-1]` / `[shard=shard-2]` routing
- Show Redis CLI with shard-prefixed cache keys
- Show Grafana dashboard updating in real-time
- Show Docker Compose logs scrolling
- Show the test suite passing (including hash ring distribution test)
- Show the architecture diagram at transitions
