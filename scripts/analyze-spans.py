#!/usr/bin/env python3
"""Analyze kast JSONL telemetry spans and print profiling reports.

Usage:
    python scripts/analyze-spans.py path/to/standalone-spans.jsonl
    python scripts/analyze-spans.py path/to/standalone-spans.jsonl --json
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path


def _percentile(sorted_values: list[float], p: float) -> float:
    if not sorted_values:
        return 0.0
    k = (len(sorted_values) - 1) * p
    f = int(k)
    c = f + 1 if f + 1 < len(sorted_values) else f
    return sorted_values[f] + (k - f) * (sorted_values[c] - sorted_values[f])


def _ns_to_ms(ns: float) -> float:
    return ns / 1_000_000


def _format_ms(ms: float) -> str:
    if ms < 1:
        return f"{ms:.3f}"
    return f"{ms:.1f}"


def load_spans(path: Path) -> list[dict]:
    spans: list[dict] = []
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


# ---------------------------------------------------------------------------
# Latency report
# ---------------------------------------------------------------------------

def latency_report(spans: list[dict]) -> list[dict]:
    durations_by_name: dict[str, list[float]] = defaultdict(list)
    for span in spans:
        name = span.get("name", "")
        dur = span.get("durationNanos", 0)
        durations_by_name[name].append(float(dur))

    rows = []
    for name, durations in durations_by_name.items():
        durations.sort()
        rows.append({
            "name": name,
            "count": len(durations),
            "p50_ms": _ns_to_ms(_percentile(durations, 0.50)),
            "p95_ms": _ns_to_ms(_percentile(durations, 0.95)),
            "p99_ms": _ns_to_ms(_percentile(durations, 0.99)),
            "total_ms": _ns_to_ms(sum(durations)),
        })
    rows.sort(key=lambda r: r["p95_ms"], reverse=True)
    return rows


def print_latency_report(rows: list[dict]) -> None:
    print("\n=== Latency Report (sorted by p95 desc) ===")
    print(f"{'Span Name':<50} {'Count':>6} {'p50 ms':>10} {'p95 ms':>10} {'p99 ms':>10} {'Total ms':>12}")
    print("-" * 100)
    for r in rows:
        print(
            f"{r['name']:<50} {r['count']:>6} "
            f"{_format_ms(r['p50_ms']):>10} {_format_ms(r['p95_ms']):>10} "
            f"{_format_ms(r['p99_ms']):>10} {_format_ms(r['total_ms']):>12}"
        )


# ---------------------------------------------------------------------------
# Lock contention report
# ---------------------------------------------------------------------------

def lock_contention_report(spans: list[dict]) -> dict:
    report: dict[str, dict] = {}
    for span in spans:
        name = span.get("name", "")
        if not name.startswith("kast.lock."):
            continue
        attrs = span.get("attributes", {})
        wait = float(attrs.get("kast.lock.waitNanos", 0))
        hold = float(attrs.get("kast.lock.holdNanos", 0))
        lock_type = name.split(".")[-1]  # "read" or "write"
        if lock_type not in report:
            report[lock_type] = {
                "count": 0,
                "total_wait_ms": 0.0,
                "max_wait_ms": 0.0,
                "total_hold_ms": 0.0,
                "max_hold_ms": 0.0,
            }
        entry = report[lock_type]
        entry["count"] += 1
        entry["total_wait_ms"] += _ns_to_ms(wait)
        entry["max_wait_ms"] = max(entry["max_wait_ms"], _ns_to_ms(wait))
        entry["total_hold_ms"] += _ns_to_ms(hold)
        entry["max_hold_ms"] = max(entry["max_hold_ms"], _ns_to_ms(hold))

    for entry in report.values():
        total_hold = entry["total_hold_ms"]
        entry["contention_ratio"] = (
            entry["total_wait_ms"] / total_hold if total_hold > 0 else 0.0
        )
    return report


def print_lock_contention_report(report: dict) -> None:
    if not report:
        return
    print("\n=== Lock Contention Report ===")
    print(
        f"{'Lock Type':<10} {'Count':>6} {'Total Wait ms':>14} {'Max Wait ms':>12} "
        f"{'Total Hold ms':>14} {'Max Hold ms':>12} {'Wait/Hold':>10}"
    )
    print("-" * 80)
    for lock_type, entry in report.items():
        print(
            f"{lock_type:<10} {entry['count']:>6} "
            f"{_format_ms(entry['total_wait_ms']):>14} {_format_ms(entry['max_wait_ms']):>12} "
            f"{_format_ms(entry['total_hold_ms']):>14} {_format_ms(entry['max_hold_ms']):>12} "
            f"{entry['contention_ratio']:>10.2f}"
        )


# ---------------------------------------------------------------------------
# Memory trend report
# ---------------------------------------------------------------------------

def memory_trend_report(spans: list[dict]) -> list[dict]:
    points = []
    for span in spans:
        if span.get("name") != "kast.session.memorySnapshot":
            continue
        attrs = span.get("attributes", {})
        points.append({
            "timestamp_ns": span.get("startEpochNanos", 0),
            "heap_used_mb": float(attrs.get("kast.memory.heap.usedBytes", 0)) / (1024 * 1024),
            "heap_committed_mb": float(attrs.get("kast.memory.heap.committedBytes", 0)) / (1024 * 1024),
            "heap_max_mb": float(attrs.get("kast.memory.heap.maxBytes", 0)) / (1024 * 1024),
            "runtime_total_mb": float(attrs.get("kast.memory.runtime.totalBytes", 0)) / (1024 * 1024),
        })
    points.sort(key=lambda p: p["timestamp_ns"])
    return points


def print_memory_trend_report(points: list[dict]) -> None:
    if not points:
        return
    print("\n=== Memory Trend ===")
    print(f"{'#':>4} {'Heap Used MB':>14} {'Heap Committed MB':>18} {'Heap Max MB':>12} {'Runtime Total MB':>18}")
    print("-" * 70)
    for i, p in enumerate(points, 1):
        print(
            f"{i:>4} {p['heap_used_mb']:>14.1f} {p['heap_committed_mb']:>18.1f} "
            f"{p['heap_max_mb']:>12.1f} {p['runtime_total_mb']:>18.1f}"
        )


# ---------------------------------------------------------------------------
# I/O report
# ---------------------------------------------------------------------------

def io_report(spans: list[dict]) -> dict:
    total_reads = 0
    total_bytes = 0
    slowest: list[dict] = []
    for span in spans:
        if span.get("name") != "kast.io.readSourceFile":
            continue
        attrs = span.get("attributes", {})
        total_reads += 1
        total_bytes += int(attrs.get("kast.io.bytesRead", 0))
        slowest.append({
            "file": attrs.get("kast.io.filePath", ""),
            "bytes": int(attrs.get("kast.io.bytesRead", 0)),
            "duration_ms": _ns_to_ms(float(attrs.get("kast.io.durationNanos", 0))),
        })
    slowest.sort(key=lambda x: x["duration_ms"], reverse=True)
    return {
        "total_reads": total_reads,
        "total_bytes": total_bytes,
        "slowest_files": slowest[:20],
    }


def print_io_report(report: dict) -> None:
    if report["total_reads"] == 0:
        return
    print("\n=== I/O Report ===")
    print(f"Total file reads: {report['total_reads']}")
    print(f"Total bytes read: {report['total_bytes']:,}")
    if report["slowest_files"]:
        print(f"\n{'File':<70} {'Bytes':>10} {'Duration ms':>12}")
        print("-" * 94)
        for entry in report["slowest_files"]:
            name = entry["file"]
            if len(name) > 68:
                name = "..." + name[-65:]
            print(f"{name:<70} {entry['bytes']:>10} {_format_ms(entry['duration_ms']):>12}")


# ---------------------------------------------------------------------------
# Hotspot summary
# ---------------------------------------------------------------------------

def hotspot_summary(spans: list[dict], top_n: int = 10) -> list[dict]:
    cumulative: dict[str, float] = defaultdict(float)
    for span in spans:
        name = span.get("name", "")
        cumulative[name] += float(span.get("durationNanos", 0))
    rows = [
        {"name": name, "cumulative_ms": _ns_to_ms(ns)}
        for name, ns in cumulative.items()
    ]
    rows.sort(key=lambda r: r["cumulative_ms"], reverse=True)
    return rows[:top_n]


def print_hotspot_summary(rows: list[dict]) -> None:
    print("\n=== Hotspot Summary (top 10 by cumulative duration) ===")
    print(f"{'Span Name':<60} {'Cumulative ms':>14}")
    print("-" * 76)
    for r in rows:
        print(f"{r['name']:<60} {_format_ms(r['cumulative_ms']):>14}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze kast JSONL telemetry spans.")
    parser.add_argument("file", type=Path, help="Path to the JSONL telemetry file")
    parser.add_argument("--json", action="store_true", help="Output machine-readable JSON instead of tables")
    args = parser.parse_args()

    if not args.file.is_file():
        print(f"Error: file not found: {args.file}", file=sys.stderr)
        sys.exit(1)

    spans = load_spans(args.file)
    if not spans:
        print("No spans found in the input file.", file=sys.stderr)
        sys.exit(1)

    latency = latency_report(spans)
    lock = lock_contention_report(spans)
    memory = memory_trend_report(spans)
    io = io_report(spans)
    hotspots = hotspot_summary(spans)

    if args.json:
        json.dump(
            {
                "latency": latency,
                "lock_contention": lock,
                "memory_trend": memory,
                "io": io,
                "hotspots": hotspots,
            },
            sys.stdout,
            indent=2,
        )
        print()
    else:
        print_latency_report(latency)
        print_lock_contention_report(lock)
        print_memory_trend_report(memory)
        print_io_report(io)
        print_hotspot_summary(hotspots)


if __name__ == "__main__":
    main()
