#!/usr/bin/env bash
# Video-friendly stress-test walkthrough for the URL shortener.
#
# Runs a redirect load test, waits for the Kafka consumer to flush click
# counts, shows the per-shard results, then runs a create/write load test and
# prints the Prometheus counters. Each step is announced so it is easy to
# narrate while recording.
#
# Usage:
#   ./scripts/stress-demo.sh
#   DURATION=60 CONCURRENCY=200 ./scripts/stress-demo.sh
#
# Overridable: BASE_URL, DURATION, CONCURRENCY, CODES, PYTHON

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
DURATION="${DURATION:-30}"
CONCURRENCY="${CONCURRENCY:-50}"
CODES="${CODES:-25}"
PYTHON="${PYTHON:-python3}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCH="$SCRIPT_DIR/benchmark.py"

banner() { printf '\n\033[1;36m%s\033[0m\n' "=== $* ==="; }

banner "Preflight"
echo "target:      $BASE_URL"
echo "duration:    ${DURATION}s per phase"
echo "concurrency: $CONCURRENCY"
curl -fsS "$BASE_URL/actuator/health" && echo

banner "Phase 1/3 — redirect stress (cache-aside hot path)"
"$PYTHON" "$BENCH" \
  --base-url "$BASE_URL" \
  --scenario redirect \
  --duration "$DURATION" \
  --concurrency "$CONCURRENCY" \
  --codes "$CODES" \
  --warmup 5

banner "Phase 2/3 — Kafka click pipeline into the shard databases"
echo "waiting 12s for the consumer flush window (app.analytics.flush-interval-ms)..."
sleep 12
for i in 0 1 2; do
  container="urlshortener-postgres-shard-$i"
  if docker ps --format '{{.Names}}' | grep -qx "$container"; then
    echo "--- $container ---"
    docker exec "$container" psql -U postgres -d "urlshortener_$i" -tAc \
      "select short_code || ' -> ' || click_count || ' clicks'
         from urls where short_code like 'bench%'
        order by click_count desc limit 10"
  else
    echo "container $container not running, skipping"
  fi
done

banner "Phase 3/3 — write stress (creates spread across shards)"
"$PYTHON" "$BENCH" \
  --base-url "$BASE_URL" \
  --scenario create \
  --duration "$DURATION" \
  --concurrency "$CONCURRENCY" \
  --no-seed \
  --warmup 3

banner "Prometheus counters"
curl -fsS "$BASE_URL/actuator/prometheus" \
  | grep -E '^url_(created|redirect|cache_hit|cache_miss|cache_update|analytics_published)_total' \
  || true

banner "Done"
echo "Tip: open Grafana at http://localhost:3000 (admin/admin) to show the same"
echo "     numbers as live dashboard panels."
