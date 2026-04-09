#!/usr/bin/env python3
"""Compare two JSONL telemetry exports and report timing/contention changes.

Usage:
    python3 scripts/telemetry_diff.py baseline.jsonl current.jsonl

Output: JSON with before/after/improvement statistics for each span name,
lock contention metrics, and candidate resolution source distribution.
"""

import json
import math
import statistics
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any


def parse_jsonl(path: str) -> list[dict[str, Any]]:
    """Parse a JSONL file into a list of span dicts."""
    spans: list[dict[str, Any]] = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                spans.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return spans


def percentile(values: list[float], pct: float) -> float:
    """Return the pct-th percentile from a sorted list."""
    if not values:
        return 0.0
    k = (len(values) - 1) * (pct / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return values[int(k)]
    d0 = values[int(f)] * (c - k)
    d1 = values[int(c)] * (k - f)
    return d0 + d1


def compute_stats(spans: list[dict[str, Any]]) -> dict[str, Any]:
    """Compute per-name statistics for a set of spans."""
    by_name: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for span in spans:
        name = span.get("name", "<unknown>")
        by_name[name].append(span)

    result: dict[str, Any] = {}
    for name, items in sorted(by_name.items()):
        durations = sorted(
            [s["durationNanos"] / 1e6 for s in items if "durationNanos" in s]
        )
        entry: dict[str, Any] = {"count": len(items)}
        if durations:
            entry["p50_ms"] = round(percentile(durations, 50), 2)
            entry["p95_ms"] = round(percentile(durations, 95), 2)
            entry["max_ms"] = round(max(durations), 2)

        # Lock-specific metrics
        wait_nanos = [
            s.get("attributes", {}).get("kast.lock.waitNanos", 0)
            for s in items
            if "kast.lock.waitNanos" in s.get("attributes", {})
        ]
        if wait_nanos:
            entry["avg_wait_ms"] = round(statistics.mean(wait_nanos) / 1e6, 2)

        hold_nanos = [
            s.get("attributes", {}).get("kast.lock.holdNanos", 0)
            for s in items
            if "kast.lock.holdNanos" in s.get("attributes", {})
        ]
        if hold_nanos:
            entry["avg_hold_ms"] = round(statistics.mean(hold_nanos) / 1e6, 2)

        result[name] = entry
    return result


def lock_contention(spans: list[dict[str, Any]]) -> dict[str, Any]:
    """Compute lock contention summary from kast.lock.acquire spans."""
    lock_spans = [
        s for s in spans if s.get("name") == "kast.lock.acquire"
    ]
    writes = [
        s for s in lock_spans
        if s.get("attributes", {}).get("kast.lock.type") == "WRITE"
    ]
    reads = [
        s for s in lock_spans
        if s.get("attributes", {}).get("kast.lock.type") == "READ"
    ]

    result: dict[str, Any] = {}

    write_holds = sorted(
        [s.get("attributes", {}).get("kast.lock.holdNanos", 0) / 1e6 for s in writes]
    )
    if write_holds:
        result["write_hold_max_ms"] = round(max(write_holds), 2)
        result["write_hold_p95_ms"] = round(percentile(write_holds, 95), 2)

    read_waits = sorted(
        [s.get("attributes", {}).get("kast.lock.waitNanos", 0) / 1e6 for s in reads]
    )
    if read_waits:
        result["read_wait_p95_ms"] = round(percentile(read_waits, 95), 2)
        result["read_wait_max_ms"] = round(max(read_waits), 2)

    return result


def candidate_distribution(spans: list[dict[str, Any]]) -> dict[str, int]:
    """Count candidate resolution spans by source."""
    dist: dict[str, int] = defaultdict(int)
    for s in spans:
        if s.get("name") != "kast.session.candidateKotlinFilePaths":
            continue
        source = s.get("attributes", {}).get("kast.candidates.source", "unknown")
        dist[source] += 1
    return dict(dist)


def delta_pct(before: float, after: float) -> float:
    """Compute percentage change from before to after."""
    if before == 0:
        return 0.0
    return round(((after - before) / before) * 100, 1)


def main() -> None:
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <baseline.jsonl> <current.jsonl>", file=sys.stderr)
        sys.exit(1)

    baseline_path, current_path = sys.argv[1], sys.argv[2]

    for p in (baseline_path, current_path):
        if not Path(p).exists():
            print(f"Error: file not found: {p}", file=sys.stderr)
            sys.exit(1)

    baseline = parse_jsonl(baseline_path)
    current = parse_jsonl(current_path)

    before_stats = compute_stats(baseline)
    after_stats = compute_stats(current)

    # Build combined span diff
    all_names = sorted(set(before_stats.keys()) | set(after_stats.keys()))
    spans_diff: dict[str, Any] = {}
    for name in all_names:
        entry: dict[str, Any] = {}
        if name in before_stats:
            entry["before"] = before_stats[name]
        if name in after_stats:
            entry["after"] = after_stats[name]
        if "before" in entry and "after" in entry:
            delta: dict[str, float] = {}
            for key in ("p50_ms", "p95_ms"):
                b = entry["before"].get(key, 0)
                a = entry["after"].get(key, 0)
                if b > 0:
                    delta[f"{key[:-3]}_pct"] = delta_pct(b, a)
            if delta:
                entry["delta"] = delta
        spans_diff[name] = entry

    # Lock contention
    before_lock = lock_contention(baseline)
    after_lock = lock_contention(current)
    lock_diff: dict[str, Any] = {}
    for key in sorted(set(before_lock.keys()) | set(after_lock.keys())):
        lock_diff[key] = {"before": before_lock.get(key, 0), "after": after_lock.get(key, 0)}

    # Candidate resolution
    before_candidates = candidate_distribution(baseline)
    after_candidates = candidate_distribution(current)

    output = {
        "spans": spans_diff,
        "lock_contention": lock_diff,
        "candidate_resolution": {
            "source_distribution": {
                "before": before_candidates,
                "after": after_candidates,
            },
        },
    }

    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
