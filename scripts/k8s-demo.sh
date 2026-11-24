#!/usr/bin/env bash
# Multi-replica Kubernetes demos for the URL shortener.
#
# Deploys the stack with kubectl, port-forwards the Service, runs a redirect
# load test against the 3 default replicas, shows the per-pod request counts,
# then scales to 6 replicas and runs the same load again. Good B-roll for the
# video: the same codes are served faster because every new pod adds read
# capacity (shard routing is computed locally, shared state is in Redis).
#
# Requires: kubectl pointed at a cluster, docker, a StorageClass, and a
# metrics-server for the HPA to react (manual scaling works without it).
#
# Usage:
#   ./scripts/k8s-demo.sh
#   CONCURRENCY=200 DURATION=45 ./scripts/k8s-demo.sh
#
# Overridable: PORT, DURATION, CONCURRENCY, CODES, PYTHON, IMAGE, NAMESPACE

set -euo pipefail

NAMESPACE="${NAMESPACE:-urlshortener}"
DEPLOYMENT="url-shortener"
IMAGE="${IMAGE:-url-shortener:latest}"
PORT="${PORT:-8080}"
BASE_URL="http://localhost:${PORT}"
DURATION="${DURATION:-30}"
CONCURRENCY="${CONCURRENCY:-100}"
CODES="${CODES:-25}"
PYTHON="${PYTHON:-python3}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BENCH="$SCRIPT_DIR/benchmark.py"
PF_LOG="$(mktemp -t urlshortener-portforward.XXXXXX)"

banner() { printf '\n\033[1;36m%s\033[0m\n' "=== $* ==="; }
note() { printf '  %s\n' "$*"; }

command -v kubectl >/dev/null || { echo "kubectl not found" >&2; exit 1; }
command -v docker >/dev/null || { echo "docker not found" >&2; exit 1; }

banner "Preflight"
note "context:     $(kubectl config current-context)"
note "namespace:   $NAMESPACE"
note "image:       $IMAGE"
note "load:        ${DURATION}s at concurrency ${CONCURRENCY}"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  banner "Building image $IMAGE"
  docker build -t "$IMAGE" "$ROOT_DIR"
fi

# Local clusters do not share the host Docker image store.
if command -v minikube >/dev/null 2>&1 && minikube status >/dev/null 2>&1; then
  banner "Loading image into minikube"
  minikube image load "$IMAGE"
elif command -v kind >/dev/null 2>&1; then
  banner "Loading image into kind"
  kind load docker-image "$IMAGE"
elif command -v k3d >/dev/null 2>&1; then
  banner "Loading image into k3d"
  k3d image import "$IMAGE"
fi

banner "Applying manifests"
kubectl apply -k "$ROOT_DIR/k8s"

banner "Waiting for the stack"
kubectl -n "$NAMESPACE" rollout status deployment/redis --timeout=180s
kubectl -n "$NAMESPACE" rollout status deployment/zookeeper --timeout=180s
for shard in 0 1 2; do
  kubectl -n "$NAMESPACE" rollout status "statefulset/postgres-shard-$shard" --timeout=180s
done
kubectl -n "$NAMESPACE" rollout status statefulset/kafka --timeout=240s
kubectl -n "$NAMESPACE" rollout status "deployment/$DEPLOYMENT" --timeout=300s

banner "Pods"
kubectl -n "$NAMESPACE" get pods -l app="$DEPLOYMENT" -o wide

banner "Port-forwarding Service $DEPLOYMENT ($PORT -> 80)"
kubectl -n "$NAMESPACE" port-forward "svc/$DEPLOYMENT" "${PORT}:80" >"$PF_LOG" 2>&1 &
PF_PID=$!
trap 'kill "$PF_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 30); do
  if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS "$BASE_URL/actuator/health" && echo
if ! kill -0 "$PF_PID" 2>/dev/null; then
  echo "port-forward exited (port $PORT already in use?)" >&2
  cat "$PF_LOG" >&2
  exit 1
fi

banner "Read stress on 3 replicas"
"$PYTHON" "$BENCH" \
  --base-url "$BASE_URL" \
  --scenario redirect \
  --duration "$DURATION" \
  --concurrency "$CONCURRENCY" \
  --codes "$CODES" \
  --warmup 5

show_pod_redirects() {
  echo
  printf '  %-44s %12s\n' "POD" "REDIRECTS"
  for pod in $(kubectl -n "$NAMESPACE" get pods -l app="$DEPLOYMENT" \
                 --field-selector=status.phase=Running -o name); do
    name="${pod#pod/}"
    count=$(kubectl -n "$NAMESPACE" exec "$name" -- \
      wget -qO- http://localhost:8080/actuator/prometheus 2>/dev/null \
      | awk '/^url_redirect_total/ {printf "%d", $2; exit}')
    printf '  %-44s %12s\n' "$name" "${count:-n/a}"
  done
}

banner "Per-pod redirect counts (Service spread + 3 replicas)"
show_pod_redirects

banner "Scaling to 6 replicas"
note "If a metrics-server is running, the HPA owns the replica count and may scale back."
kubectl -n "$NAMESPACE" scale "deployment/$DEPLOYMENT" --replicas=6
kubectl -n "$NAMESPACE" rollout status "deployment/$DEPLOYMENT" --timeout=300s
kubectl -n "$NAMESPACE" get pods -l app="$DEPLOYMENT"

banner "Read stress on 6 replicas"
"$PYTHON" "$BENCH" \
  --base-url "$BASE_URL" \
  --scenario redirect \
  --duration "$DURATION" \
  --concurrency "$CONCURRENCY" \
  --codes "$CODES" \
  --no-seed \
  --warmup 5

banner "Per-pod redirect counts after scaling"
show_pod_redirects

banner "Autoscaler"
kubectl -n "$NAMESPACE" get hpa "$DEPLOYMENT" || \
  note "metrics-server not installed: install it for CPU-based autoscaling"

banner "Done"
note "Service:    kubectl -n $NAMESPACE port-forward svc/$DEPLOYMENT ${PORT}:80"
note "Scale back: kubectl -n $NAMESPACE scale deployment/$DEPLOYMENT --replicas=3"
note "Teardown:   kubectl delete -k k8s   (add --wait=false to keep the shell responsive)"
