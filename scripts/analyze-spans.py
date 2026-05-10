#!/usr/bin/env python3

import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def analyze_spans(jsonl_path: Path) -> None:
    spans = []
    with jsonl_path.open() as handle:
        for line in handle:
            if line.strip():
                spans.append(json.loads(line))

    durations_by_name = defaultdict(list)
    for span in spans:
        name = span.get("name", "unknown")
        duration_ms = span.get("durationNanos", 0) / 1_000_000
        durations_by_name[name].append(duration_ms)

    print("=== Latency Report (p95 ms) ===")
    for name, durations in sorted(
        durations_by_name.items(),
        key=lambda item: p95(item[1]),
        reverse=True,
    ):
        print(f"{name}: {p95(durations):.2f}")

    lock_spans = [span for span in spans if "lock" in span.get("name", "").lower()]
    if lock_spans:
        print("\n=== Lock Contention ===")
        for span in lock_spans:
            attrs = span.get("attributes", {})
            duration_ms = span.get("durationNanos", 0) / 1_000_000
            print(
                f"{span['name']}: "
                f"type={attrs.get('kast.lock.type')}, "
                f"duration={duration_ms:.2f}ms"
            )

    io_spans = [span for span in spans if "io" in span.get("name", "").lower()]
    if io_spans:
        print("\n=== I/O Operations ===")
        for span in io_spans:
            attrs = span.get("attributes", {})
            duration_ms = span.get("durationNanos", 0) / 1_000_000
            print(
                f"{span['name']}: "
                f"file={attrs.get('kast.io.filePath')}, "
                f"bytes={attrs.get('kast.io.bytesRead')}, "
                f"duration={duration_ms:.2f}ms"
            )


def p95(values: list[float]) -> float:
    if not values:
        return 0.0
    if len(values) == 1:
        return values[0]
    return statistics.quantiles(values, n=100)[94]


if __name__ == "__main__":
    analyze_spans(Path(sys.argv[1]))
