#!/usr/bin/env bash
# Run one benchmark scenario against the deployed url-shortener service.
#
# The app tier is stateless and the Service round-robins to every ready pod, so
# this measures the whole multi-replica stack through the Service.
#
# Usage:
#   ./scripts/bench-run.sh redirect 30 100 results/k8s-redirect-3.json
#   ./scripts/bench-run.sh create   20 20  results/k8s-create.json
#   DURATION=45 CONCURRENCY=200 ./scripts/bench-run.sh mixed results/k8s-mixed.json
#
# Overridable: NAMESPACE, PORT, OUT

set -euo pipefail

SCENARIO="${1:-redirect}"            # redirect | create | mixed
DURATION="${2:-${DURATION:-30}}"
CONCURRENCY="${3:-${CONCURRENCY:-100}}"
OUT="${4:-results/benchmark.json}"

NAMESPACE="${NAMESPACE:-urlshortener}"
DEPLOYMENT="url-shortener"
PORT="${PORT:-8081}"
BASE_URL="http://localhost:${PORT}"
PYTHON="${PYTHON:-python3}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BENCH="$SCRIPT_DIR/benchmark.py"
PF_LOG="$(mktemp -t urlshortener-bench-portforward.XXXXXX)"

banner() { printf '\n\033[1;36m%s\033[0m\n' "=== $* ==="; }
note() { printf '  %s\n' "$*"; }

command -v kubectl >/dev/null || { echo "kubectl not found" >&2; exit 1; }
command -v "$PYTHON" >/dev/null || { echo "$PYTHON not found" >&2; exit 1; }

REPLICAS=$(kubectl -n "$NAMESPACE" get deployment "$DEPLOYMENT" \
  -o jsonpath='{.spec.replicas}')
banner "Benchmark: $SCENARIO (${DURATION}s @ concurrency ${CONCURRENCY}, ${REPLICAS} replicas)"

# Port-forward the Service; the redirect path needs the port free.
kubectl -n "$NAMESPACE" port-forward "svc/$DEPLOYMENT" "${PORT}:80" >"$PF_LOG" 2>&1 &
PF_PID=$!
trap 'kill "$PF_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 30); do
  if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
if ! curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then
  echo "port-forward not healthy; log:" >&2
  cat "$PF_LOG" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"
note "output: $OUT"

set +e
"$PYTHON" "$BENCH" \
  --base-url "$BASE_URL" \
  --scenario "$SCENARIO" \
  --duration "$DURATION" \
  --concurrency "$CONCURRENCY" \
  --codes 25 \
  --json "$OUT"
RC=$?
set -e
note "exit code: $RC (non-zero means the load run had errors, e.g. 5xx)"
exit 0
