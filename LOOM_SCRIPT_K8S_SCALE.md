# Loom Video Script — URL Shortener: From One Instance to a Scaling Kubernetes Stack

## Video Title
"URL Shortener: Why The App Is Stateless & How We Prove It With Kubernetes + Benchmarks"

## Target Length
10–12 minutes

## What it covers
The stateless-app premise, the shared-state architecture (Redis / Postgres shards /
Kafka), sharding, then a real minikube deployment and measured benchmark runs
(numbers from `scripts/results/*.json` and `README.md`).

---

## Scene 1: The Problem & The Key Design Decision (0:00 – 1:15)

**On screen:** Architecture diagram (App → Redis → PostgreSQL, Kafka for analytics)

> "A URL shortener is easy to build as a single process: `POST /api/v1/urls` returns a
> code, `GET /{code}` returns the long URL. But the moment you need throughput, the
> interesting questions come out: how do you add instances, where does the shared
> state live, what happens when it gets slow?"

> "The number-one design decision in this project: **the app is stateless**. Every
> replica can serve every short code. All shared state lives outside the pods — in
> Redis (cache + rate limits), PostgreSQL (the data), and Kafka (analytics). If you
> can say that clearly, the rest follows."

**Show:** `k8s/app.yaml` header comment, or a diagram of "stateless pods → Redis/PG/Kafka".

---

## Scene 2: The Read Path — Cache-Aside (1:15 – 2:45)

**On screen:** `UrlService` + `UrlCache` code

> "The redirect is the hot path, so we use cache-aside: first `Redis GET
> ("shard:shard-N:url:code")`. On a hit, redirect immediately — sub-millisecond.
> On a miss: query PostgreSQL, write the result back to Redis with a TTL, redirect."

> "The key insight for scaling: reads hit Redis, which is cheap to replicate, and the
> database is only touched on misses."

**Show:** Code — `urlCache.getUrl()` → `urlRepository.findByShortCodeAndActiveTrue()`
→ `urlCache.putUrl()`.

---

## Scene 3: Analytics — Async via Kafka (2:45 – 4:00)

**On screen:** `ClickEventPublisher` + Kafka

> "Every redirect also publishes a ClickEvent to Kafka. The redirect returns without
> waiting — analytics is eventual and that's the right tradeoff. Redis holds real-time
> click counts (atomically flushed with `GETDEL`), and a consumer group aggregates
> events."

> "So: no write on the hot path, no locks, and when we add more pods later, each pod
> just joins the consumer group — events get processed by whoever is free."

**Show:** Click flow diagram + `scripts/k8s/config.yaml` (6 partitions).

---

## Scene 4: Rate Limiting (4:00 – 5:00)

**On screen:** `SlidingWindowRateLimiter`

> "Sliding-window rate limiting with a Redis sorted set: add timestamp, prune old
> entries, count what's left. Because the state is in Redis, the limit is global
> across all replicas — 100 creates/min, 1,000 redirects/min per IP."

**Show:** Sorted set operations + interceptor on all paths.

---

## Scene 5: Sharding — Consistent Hash Ring (5:00 – 7:00)

**On screen:** `ConsistentHashRing` + `ShardRouter` + `ShardedUrlRepository`

> "Now scale the data side: 3 PostgreSQL shards. Which shard owns a short code?
> A consistent hash ring — MD5 of the code mapped to one of the shards, with 150
> virtual nodes per shard so keys spread evenly."

> "Key properties: same code always → same shard (deterministic, no lookup table);
> adding a 4th shard moves only ~1/4 of keys (graceful rebalancing); each shard has
> its own `UNIQUE(short_code)` so collisions are still handled locally."

**Show:** Hash ring diagram; `kubectl get pods -l app=url-shortener` in the k8s run
later (shows 3 shards running as StatefulSets).

> "The app computes the ring locally, so routing is the same in every replica."

---

## Scene 6: Deploying on minikube — Multiple Instances (7:00 – 8:30)

**On screen:** `scripts/k8s-demo.sh` + `kubectl get pods -o wide`

> "Now the payoff of the stateless design. Deploy on minikube (Docker, kubectl,
> minikube — all installed here). The stack: 3 Postgres shards (StatefulSets),
> Redis, ZooKeeper, Kafka, and the app Deployment."

**Show (terminal):**
```bash
docker build -t url-shortener:latest .
minikube image load url-shortener:latest
kubectl apply -k k8s
kubectl -n urlshortener rollout status deployment/url-shortener
kubectl -n urlshortener get pods -l app=url-shortener -o wide
```

> "We start with 3 app replicas — the Deployment's `replicas: 3` — and the Service
> round-robins reads across every ready pod."

---

## Scene 7: Autoscaling — HPA + Benchmarks with Real Numbers (8:30 – 11:00)

**On screen:** HPA + benchmark results

> "Two scaling stories, both measured. First the HPA: CPU target 60%, min 3, max 12 —
> driven by load, it scales up automatically."

**Show:** `kubectl -n urlshortener get hpa url-shortener`

> "Then the load benchmarks. `scripts/bench-run.sh` port-forwards the Service, runs
> `scripts/benchmark.py`, and writes JSON reports."

**Show (terminal) — redirect, 3 replicas:**
```bash
./scripts/bench-run.sh redirect 30 100 scripts/results/k8s-redirect-3.json
```
- 5,675 requests @ **187.4 req/s**, all 302, p50 507 ms, p95 775 ms (`k8s-redirect-3.json`)

**Show — scale and re-run:**
```bash
kubectl -n urlshortener scale deployment/url-shortener --replicas=4
kubectl -n urlshortener rollout status deployment/url-shortener
./scripts/bench-run.sh redirect 30 100 scripts/results/k8s-redirect-4.json
```
- 11,664 requests @ **387.2 req/s**, all 302, p50 256 ms, p95 391 ms (`k8s-redirect-4.json`)

> "Double the replicas, roughly double the throughput — and the redirect p95 drops.
> The app is truly stateless; the throughput you can get is bounded by the shared
> state in Redis, which is the new bottleneck. That's the scaling story: add pods,
> capacity rises, and you watch where the next bottleneck is."

**Show — other scenarios:**
```bash
./scripts/bench-run.sh create   20 20  scripts/results/k8s-create.json
./scripts/bench-run.sh mixed    30 100 scripts/results/k8s-mixed.json
```
- Create: 623 creates @ 29.9 req/s, p50 576.1 ms, p99 1,616.0 ms (sharded writes through Postgres)
- Mixed: 7,755 total — 302 ×6,979 + 201 ×776, @ 244.0 req/s, p95 910 ms

**Show (optional):** `./scripts/k8s-demo.sh` — full 3→6 demo run:
- 3 replicas: 9,007 @ 297.0 req/s
- 6 replicas: 4,855 @ 160.0 req/s (slower — hot 25-code cache, Redis/VM bottleneck)

---

## Scene 8: Closing & Takeaways (11:00 – 11:30)

**On screen:** Project structure + README badges

> "Takeaways for the design story: stateless app → horizontal scaling; shared state
> outside the pods → replicas are fungible; consistent hashing → shard routing with
> graceful rebalancing; async analytics → redirect latency independent of write
> throughput; HPA → capacity reacts to load automatically."

> "Code, manifests, and the full benchmark tables with all test results are in the
> README."

**Show:** GitHub repo URL.

---

## B-Roll / Screen Recording Notes

- Show `curl` hitting the API through the port-forward:
  ```bash
  curl http://localhost:8080/api/v1/urls -X POST -H 'Content-Type: application/json' \
    -d '{"url": "https://example.com"}'
  curl -I http://localhost:8080/<shortCode>
  ```
- Show Redis CLI with shard-prefixed keys (`shard:shard-0:url:abc123`)
- Show the consistent-hash ring distribution test result (10K keys)
- Show Grafana dashboard live (`kubectl port-forward svc/grafana 3000` → localhost:3000)
- Show per-pod redirect counts: `kubectl -n urlshortener exec url-shortener-0 -- \
  wget -qO- http://localhost:8080/actuator/prometheus | grep url_redirect_total`
- Show test output: `mvn test` → `Tests run: 95, Failures: 0, Errors: 0`
