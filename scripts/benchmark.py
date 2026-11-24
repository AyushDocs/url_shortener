#!/usr/bin/env python3
"""Async load generator for the URL shortener.

Drives the HTTP API with a fixed number of concurrent workers for a fixed
duration and reports throughput plus latency percentiles. Designed for
screen-recording a stress-test demo:

    python3 scripts/benchmark.py --scenario redirect --duration 30 --concurrency 50
    python3 scripts/benchmark.py --scenario create   --duration 30 --concurrency 20
    python3 scripts/benchmark.py --scenario mixed    --duration 60 --concurrency 100

Scenarios
    redirect  GET /{code} against a pool of seeded short codes (hot cache path)
    create    POST /api/v1/urls with unique URLs (sharded inserts + code generation)
    mixed     90% redirects, 10% creates

Rate limiting: the app allows 1000 redirects/min and 100 creates/min per client
IP, so requests are spread over a pool of synthetic X-Forwarded-For addresses
(--ip-pool). That way a real load test measures the service, not the limiter.
Use --ip-pool 1 to intentionally demonstrate the 429 response instead.

Requires Python 3.9+ and aiohttp (already installed on this machine).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import random
import sys
import time
import uuid
from array import array
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

try:
    import aiohttp
except ImportError:  # pragma: no cover - dependency guard
    sys.exit("aiohttp is required: python3 -m pip install aiohttp")

MIX_CREATE_RATIO = 0.10


class Stats:
    """Request counts and raw latencies (array('d') keeps memory flat)."""

    __slots__ = ("latencies", "statuses", "errors", "total")

    def __init__(self) -> None:
        self.latencies = array("d")
        self.statuses: Counter[int] = Counter()
        self.errors: Counter[str] = Counter()
        self.total = 0

    def record(self, seconds: float, status: int | None = None, error: str | None = None) -> None:
        self.total += 1
        self.latencies.append(seconds)
        if error is not None:
            self.errors[error] += 1
        elif status is not None:
            self.statuses[status] += 1


def percentile(sorted_values: array, p: float) -> float:
    if not sorted_values:
        return 0.0
    idx = int(round((p / 100.0) * (len(sorted_values) - 1)))
    return sorted_values[max(0, min(idx, len(sorted_values) - 1))]


def make_ips(count: int) -> list[str]:
    return [f"10.{i // 254 % 256}.{i % 254 + 1}.{(i * 37) % 254 + 1}" for i in range(count)]


def make_codes(prefix: str, count: int) -> list[str]:
    # Short codes are capped at 16 characters by the API.
    return [f"{prefix}{i:05d}" for i in range(count)]


async def seed_codes(session: aiohttp.ClientSession, base: str, codes: list[str],
                     ips: list[str]) -> bool:
    created = reused = failed = 0
    for i, code in enumerate(codes):
        payload = {"url": f"https://example.com/bench/{code}", "customCode": code}
        headers = {"X-Forwarded-For": ips[i % len(ips)]}
        try:
            async with session.post(f"{base}/api/v1/urls", json=payload, headers=headers) as resp:
                body = await resp.text()
                if resp.status == 201:
                    created += 1
                elif resp.status == 409:
                    reused += 1
                else:
                    failed += 1
                    print(f"  seed {code}: HTTP {resp.status} {body[:160]}")
        except Exception as exc:  # noqa: BLE001 - surfaced to the operator
            failed += 1
            print(f"  seed {code}: {type(exc).__name__}: {exc}")
    print(f"  seeded {len(codes)} short codes: {created} created, "
          f"{reused} already existed, {failed} failed")
    return failed == 0


async def do_redirect(session: aiohttp.ClientSession, base: str, code: str,
                      ip: str, stats: Stats) -> None:
    started = time.perf_counter()
    try:
        async with session.get(f"{base}/{code}", allow_redirects=False,
                               headers={"X-Forwarded-For": ip}) as resp:
            await resp.read()
            stats.record(time.perf_counter() - started, status=resp.status)
    except Exception as exc:  # noqa: BLE001
        stats.record(time.perf_counter() - started, error=type(exc).__name__)


async def do_create(session: aiohttp.ClientSession, base: str, ip: str,
                    stats: Stats) -> None:
    payload = {"url": f"https://example.com/bench/{uuid.uuid4().hex[:12]}"}
    started = time.perf_counter()
    try:
        async with session.post(f"{base}/api/v1/urls", json=payload,
                                headers={"X-Forwarded-For": ip}) as resp:
            await resp.read()
            stats.record(time.perf_counter() - started, status=resp.status)
    except Exception as exc:  # noqa: BLE001
        stats.record(time.perf_counter() - started, error=type(exc).__name__)


async def worker(ctx: dict, stats: Stats, deadline: float) -> None:
    session = ctx["session"]
    base = ctx["base"]
    codes = ctx["codes"]
    ips = ctx["ips"]
    scenario = ctx["scenario"]
    rnd = random.Random()

    while time.perf_counter() < deadline:
        ip = ips[rnd.randrange(len(ips))]
        if scenario == "create" or (scenario == "mixed" and rnd.random() < MIX_CREATE_RATIO):
            await do_create(session, base, ip, stats)
        else:
            await do_redirect(session, base, codes[rnd.randrange(len(codes))], ip, stats)


async def progress(stats: Stats, duration: int, stop: asyncio.Event,
                   per_second: list[float]) -> None:
    start = time.perf_counter()
    last_total = 0
    last_at = start

    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), timeout=1.0)
            break
        except asyncio.TimeoutError:
            pass

        now = time.perf_counter()
        total = stats.total
        delta = total - last_total
        elapsed = now - start
        rps = delta / (now - last_at) if now > last_at else 0.0
        if elapsed <= duration:
            per_second.append(rps)

        window = stats.latencies[max(0, stats.total - min(delta, 5000)):]
        p95 = percentile(sorted(window), 95) * 1000 if delta else 0.0
        sys.stderr.write(
            f"\r  {elapsed:5.1f}s/{duration}s   {total:>9,} req   {rps:>7.0f} req/s   "
            f"p95 {p95:>6.1f} ms   429: {stats.statuses.get(429, 0):>6,}   "
        )
        sys.stderr.flush()
        last_total, last_at = total, now

    sys.stderr.write("\r" + " " * 96 + "\r")
    sys.stderr.flush()


def histogram(per_second: list[float]) -> str:
    if not per_second:
        return ""
    peak = max(per_second) or 1.0
    width = 44
    lines = []
    for i, rps in enumerate(per_second, start=1):
        bar = "█" * max(1, int(round(rps / peak * width)))
        lines.append(f"  {i:>3}s |{bar:<{width}}| {rps:>6.0f}")
    return "\n".join(lines)


def summarize(args, stats: Stats, elapsed: float, per_second: list[float]) -> dict:
    latencies = sorted(stats.latencies)
    ms = [v * 1000 for v in latencies]
    successes = sum(n for code, n in stats.statuses.items() if code < 400)
    failures = sum(n for code, n in stats.statuses.items() if code >= 500)
    rate_limited = stats.statuses.get(429, 0)
    rps = stats.total / elapsed if elapsed else 0.0

    print()
    print("┌────────────────────────────── results ──────────────────────────────┐")
    print(f"│ scenario          {args.scenario:<48}│")
    print(f"│ target            {args.base_url:<48}│")
    print(f"│ concurrency       {args.concurrency:<48}│")
    print(f"│ duration          {elapsed:<47.1f}s│")
    print(f"│ total requests    {stats.total:<48,}│")
    print(f"│ throughput        {rps:<45.0f} req/s│")
    print(f"│ success (2xx/3xx) {successes:<48,}│")
    print(f"│ rate limited 429  {rate_limited:<48,}│")
    print(f"│ server errors 5xx {failures:<48,}│")
    print(f"│ transport errors  {sum(stats.errors.values()):<48,}│")
    print("├──────────────────────── latency (ms) ───────────────────────────────┤")
    print(f"│ min {ms[0]:>7.1f}   mean {sum(ms) / len(ms):>7.1f}   p50 {percentile(latencies, 50) * 1000:>7.1f}"
          f"   p90 {percentile(latencies, 90) * 1000:>7.1f}   │")
    print(f"│ p95 {percentile(latencies, 95) * 1000:>7.1f}   p99 {percentile(latencies, 99) * 1000:>7.1f}"
          f"   max {ms[-1]:>7.1f}                                        │")
    print("└─────────────────────────────────────────────────────────────────────┘")

    if stats.statuses:
        print("\n  status codes: " + ", ".join(
            f"{code}×{count:,}" for code, count in sorted(stats.statuses.items())))
    if stats.errors:
        print("  transport errors: " + ", ".join(
            f"{name}×{count:,}" for name, count in stats.errors.most_common()))

    if per_second:
        print("\n  requests/second over time")
        print(histogram(per_second))

    report = {
        "startedAt": args.started_at,
        "scenario": args.scenario,
        "baseUrl": args.base_url,
        "concurrency": args.concurrency,
        "durationSeconds": round(elapsed, 2),
        "totalRequests": stats.total,
        "requestsPerSecond": round(rps, 1),
        "statusCodes": {str(k): v for k, v in sorted(stats.statuses.items())},
        "transportErrors": dict(stats.errors),
        "latencyMs": {
            "min": round(ms[0], 2),
            "mean": round(sum(ms) / len(ms), 2),
            "p50": round(percentile(latencies, 50) * 1000, 2),
            "p90": round(percentile(latencies, 90) * 1000, 2),
            "p95": round(percentile(latencies, 95) * 1000, 2),
            "p99": round(percentile(latencies, 99) * 1000, 2),
            "max": round(ms[-1], 2),
        },
        "requestsPerSecondSeries": [round(v, 1) for v in per_second],
    }
    return report


async def run(args) -> int:
    base = args.base_url.rstrip("/")
    codes = make_codes(args.code_prefix, args.codes)
    ips = make_ips(args.ip_pool)
    args.started_at = datetime.now(timezone.utc).isoformat(timespec="seconds")

    connector = aiohttp.TCPConnector(limit=args.concurrency, ttl_dns_cache=300)
    timeout = aiohttp.ClientTimeout(total=args.timeout)

    async with aiohttp.ClientSession(connector=connector, timeout=timeout) as session:
        if not args.no_seed:
            print(f"== seeding {len(codes)} short codes on {base} ==")
            await seed_codes(session, base, codes, ips)

        ctx = {"session": session, "base": base, "codes": codes,
               "ips": ips, "scenario": args.scenario}

        if args.warmup:
            print(f"== warmup: {args.warmup}s at concurrency {args.concurrency} ==")
            warmup_stats = Stats()
            warmup_until = time.perf_counter() + args.warmup
            await asyncio.gather(*(worker(ctx, warmup_stats, warmup_until)
                                   for _ in range(args.concurrency)))
            print(f"   warmup sent {warmup_stats.total:,} requests")

        print(f"== load: {args.scenario} for {args.duration}s at concurrency {args.concurrency} ==")
        stats = Stats()
        per_second: list[float] = []
        stop = asyncio.Event()
        deadline = time.perf_counter() + args.duration

        started = time.perf_counter()
        monitor = asyncio.create_task(progress(stats, args.duration, stop, per_second))
        await asyncio.gather(*(worker(ctx, stats, deadline) for _ in range(args.concurrency)))
        elapsed = time.perf_counter() - started
        stop.set()
        await monitor

    if stats.total == 0:
        print("no requests were sent", file=sys.stderr)
        return 1

    report = summarize(args, stats, elapsed, per_second)
    if args.json:
        path = Path(args.json)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"\n  report written to {path}")

    server_errors = sum(n for code, n in stats.statuses.items() if code >= 500)
    return 0 if sum(stats.errors.values()) == 0 and server_errors == 0 else 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Load test the URL shortener API.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--scenario", choices=["redirect", "create", "mixed"], default="redirect")
    parser.add_argument("--duration", type=int, default=30, help="measured load duration in seconds")
    parser.add_argument("--warmup", type=int, default=5, help="seconds of untimed warmup")
    parser.add_argument("--concurrency", type=int, default=50, help="parallel in-flight requests")
    parser.add_argument("--codes", type=int, default=25, help="short codes to seed and reuse")
    parser.add_argument("--code-prefix", default="bench", help="prefix for seeded short codes")
    parser.add_argument("--ip-pool", type=int, default=200,
                        help="synthetic X-Forwarded-For clients, spreads rate-limit buckets")
    parser.add_argument("--no-seed", action="store_true", help="skip seeding short codes")
    parser.add_argument("--json", metavar="PATH", help="write the result summary as JSON")
    parser.add_argument("--timeout", type=float, default=10.0, help="per-request timeout in seconds")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
