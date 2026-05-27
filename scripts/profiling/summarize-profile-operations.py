#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Summarize kast standalone profiling operations from a result directory.",
    )
    parser.add_argument("results_dir", type=Path)
    parser.add_argument("--top-spans", type=int, default=20)
    return parser.parse_args()


def read_json(path: Path) -> Any | None:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    values: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                value = json.loads(line)
                if isinstance(value, dict):
                    values.append(value)
    return values


def percentile(values: list[float], percent: int) -> float | None:
    if not values:
        return None
    if len(values) == 1:
        return values[0]
    ordered = sorted(values)
    index = round((percent / 100) * (len(ordered) - 1))
    return ordered[index]


def duration_summary(values: list[float]) -> dict[str, float | int | None]:
    return {
        "count": len(values),
        "minMillis": min(values) if values else None,
        "medianMillis": statistics.median(values) if values else None,
        "p95Millis": percentile(values, 95),
        "maxMillis": max(values) if values else None,
        "totalMillis": sum(values),
    }


def profile_key(value: dict[str, Any]) -> str:
    mode = value.get("profileMode", "unknown")
    index = value.get("profileRunIndex", "unknown")
    return f"{index}:{mode}"


def summarize_rpc(events: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        operation = str(event.get("operation", "unknown"))
        grouped[operation].append(event)

    summary: dict[str, Any] = {}
    for operation, operation_events in sorted(grouped.items()):
        durations = [
            float(event["durationMillis"])
            for event in operation_events
            if isinstance(event.get("durationMillis"), (int, float))
        ]
        ok_count = sum(1 for event in operation_events if event.get("ok") is True)
        summary[operation] = {
            **duration_summary(durations),
            "profiles": sorted({profile_key(event) for event in operation_events}),
            "okCount": ok_count,
            "errorCount": len(operation_events) - ok_count,
            "lastSummary": operation_events[-1].get("summary"),
            "lastError": operation_events[-1].get("error"),
        }
    return summary


def span_profile_key(path: Path) -> str:
    stem = path.stem
    prefix = "standalone-spans-"
    if not stem.startswith(prefix):
        return "unknown"
    return stem.removeprefix(prefix)


def read_spans(telemetry_dir: Path) -> list[dict[str, Any]]:
    spans: list[dict[str, Any]] = []
    for path in sorted(telemetry_dir.glob("standalone-spans-*.jsonl")):
        key = span_profile_key(path)
        for span in read_jsonl(path):
            span["profile"] = key
            spans.append(span)
    return spans


def summarize_spans(spans: list[dict[str, Any]], top_spans: int) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for span in spans:
        grouped[str(span.get("name", "unknown"))].append(span)

    all_spans = []
    discovery_spans = []
    for name, name_spans in grouped.items():
        durations = [
            float(span.get("durationNanos", 0)) / 1_000_000
            for span in name_spans
            if isinstance(span.get("durationNanos"), (int, float))
        ]
        item = {
            "name": name,
            **duration_summary(durations),
            "profiles": sorted({str(span.get("profile", "unknown")) for span in name_spans}),
            "sampleAttributes": name_spans[-1].get("attributes", {}),
        }
        all_spans.append(item)
        if name.startswith("kast.workspaceDiscovery"):
            discovery_spans.append(item)

    all_spans.sort(key=lambda item: (item["p95Millis"] or 0), reverse=True)
    discovery_spans.sort(key=lambda item: (item["p95Millis"] or 0), reverse=True)
    return {
        "top": all_spans[:top_spans],
        "workspaceDiscovery": discovery_spans,
    }


def summarize_startup(summary: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(summary, dict):
        return {}
    profile_runs = summary.get("profileRuns")
    if not isinstance(profile_runs, list):
        profile_runs = []
    return {
        "targetLabel": summary.get("targetLabel"),
        "counts": summary.get("counts"),
        "finalStartup": summary.get("startup"),
        "profileRuns": [
            {
                "profileMode": run.get("profileMode"),
                "profileRunIndex": run.get("profileRunIndex"),
                "startup": run.get("startup"),
            }
            for run in profile_runs
            if isinstance(run, dict)
        ],
    }


def main() -> int:
    args = parse_args()
    results_dir = args.results_dir.resolve()
    summary = read_json(results_dir / "summary.json")
    rpc_events = read_jsonl(results_dir / "rpc-latencies.jsonl")
    spans = read_spans(results_dir / "telemetry")
    output = {
        "resultsDir": str(results_dir),
        "startup": summarize_startup(summary),
        "rpcOperations": summarize_rpc(rpc_events),
        "spans": summarize_spans(spans, top_spans=args.top_spans),
    }
    print(json.dumps(output, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
