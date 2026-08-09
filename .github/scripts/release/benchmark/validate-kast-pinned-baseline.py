#!/usr/bin/env python3
"""Validate the lean pinned public-tooling performance baseline."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import NoReturn


AUDITED_SHA = "60dcf69d0431d38d8a2bb5476ee349a9f796829b"
RUN_IDS = ("minimal", "jfr")
OPERATIONS = (
    "home",
    "file-list",
    "symbol-search",
    "symbol-show",
    "references",
    "graph-summary",
    "graph-nodes",
    "graph-neighbors",
    "graph-impact",
    "diagnostic",
)
STAGES = (("serial", 1, 20), ("c1", 1, 24), ("c4", 4, 40), ("c8", 8, 64), ("c16", 16, 96))
ARTIFACTS = {
    "host-environment.json",
    "raw-samples.jsonl",
    "runs.jsonl",
    "workload.json",
}
SENSITIVE_ENVIRONMENT_NAME = re.compile(
    r"(?:^|_)(?:ACCESS_KEY|AUTH|AUTHORIZATION|COOKIE|CREDENTIALS?|PASSWORD|PASSWD|PRIVATE_KEY|SECRET|TOKEN)(?:$|_)",
    re.IGNORECASE,
)


def reject(code: str, message: str) -> NoReturn:
    print(f"invalid: {code}: {message}", file=sys.stderr)
    raise SystemExit(1)


def require(condition: bool, code: str, message: str) -> None:
    if not condition:
        reject(code, message)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_json(path: Path, label: str) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        reject("READ", f"{label}: {error}")
    require(isinstance(value, dict), "SHAPE", f"{label} is not an object")
    return value


def load_jsonl(path: Path, label: str) -> list[dict[str, object]]:
    values: list[dict[str, object]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        reject("READ", f"{label}: {error}")
    for index, line in enumerate(lines, 1):
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            reject("READ", f"{label}:{index}: {error}")
        require(isinstance(value, dict), "SHAPE", f"{label}:{index} is not an object")
        values.append(value)
    return values


def validate_artifacts(root: Path, manifest: dict[str, object]) -> dict[str, Path]:
    declared = manifest.get("artifacts")
    require(isinstance(declared, dict) and set(declared) == ARTIFACTS, "ARTIFACTS", "artifact set is not lean and exact")
    paths: dict[str, Path] = {}
    for name in sorted(ARTIFACTS):
        fact = declared[name]
        require(isinstance(fact, dict), "ARTIFACTS", f"missing fact for {name}")
        path = root / name
        require(path.is_file(), "ARTIFACTS", f"missing {name}")
        require(fact.get("bytes") == path.stat().st_size, "ARTIFACTS", f"size mismatch: {name}")
        require(fact.get("sha256") == sha256(path), "ARTIFACTS", f"hash mismatch: {name}")
        paths[name] = path
    return paths


def validate_workload(path: Path, expected_hash: object) -> tuple[dict[int, dict[str, object]], str]:
    workload = load_json(path, "workload")
    workload_hash = sha256(path)
    require(expected_hash == workload_hash, "WORKLOAD", "manifest workload hash mismatch")
    require(workload.get("auditedSourceCommit") == AUDITED_SHA, "SOURCE", "workload source mismatch")
    require(workload.get("requestCount") == 244 and workload.get("operationOrder") == list(OPERATIONS), "WORKLOAD", "request shape mismatch")
    stages = workload.get("stages")
    require(
        stages == [{"concurrency": concurrency, "count": count, "id": stage} for stage, concurrency, count in STAGES],
        "WORKLOAD",
        "stage matrix mismatch",
    )
    requests = workload.get("requests")
    require(isinstance(requests, list) and len(requests) == 244, "WORKLOAD", "request cardinality mismatch")
    expected: dict[int, dict[str, object]] = {}
    for sequence, request in enumerate(requests):
        require(isinstance(request, dict) and request.get("sequence") == sequence, "WORKLOAD", f"sequence mismatch: {sequence}")
        expected[sequence] = request
    return expected, workload_hash


def validate_samples(path: Path, expected: dict[int, dict[str, object]]) -> dict[str, list[dict[str, object]]]:
    records = load_jsonl(path, "raw samples")
    require(len(records) == 488, "SAMPLES", f"expected 488 samples, found {len(records)}")
    grouped: dict[str, list[dict[str, object]]] = defaultdict(list)
    for record in records:
        run_id = record.get("runId")
        sequence = record.get("sequence")
        require(run_id in RUN_IDS and isinstance(sequence, int) and sequence in expected, "SAMPLES", "unknown run or sequence")
        request = expected[sequence]
        for key in ("operation", "stage", "concurrency"):
            require(record.get(key) == request.get(key), "SAMPLES", f"{run_id}/{sequence}: {key} mismatch")
        require(record.get("timedOut") is False and record.get("resultType") not in {None, "unparsed", "timeout"}, "SAMPLES", f"unusable result: {run_id}/{sequence}")
        require(isinstance(record.get("durationNanos"), int) and int(record["durationNanos"]) > 0, "SAMPLES", f"duration missing: {run_id}/{sequence}")
        require(isinstance(record.get("outputBytes"), int) and int(record["outputBytes"]) >= 0, "SAMPLES", f"output missing: {run_id}/{sequence}")
        grouped[str(run_id)].append(record)
    require(set(grouped) == set(RUN_IDS), "SAMPLES", "two-run coverage mismatch")
    for run_id in RUN_IDS:
        grouped[run_id].sort(key=lambda value: int(value["sequence"]))
        require([record["sequence"] for record in grouped[run_id]] == list(range(244)), "SAMPLES", f"sequence coverage mismatch: {run_id}")
    minimal = [(record.get("classification"), record.get("resultType")) for record in grouped["minimal"]]
    jfr = [(record.get("classification"), record.get("resultType")) for record in grouped["jfr"]]
    require(minimal == jfr, "SAMPLES", "JFR classification parity mismatch")
    return grouped


def validate_runs(path: Path, workload_hash: str, samples: dict[str, list[dict[str, object]]]) -> dict[str, dict[str, object]]:
    values = load_jsonl(path, "runs")
    require(len(values) == 2, "RUNS", "exactly two runs are required")
    runs = {str(value.get("runId")): value for value in values}
    require(tuple(runs) == RUN_IDS, "RUNS", f"run order mismatch: {tuple(runs)}")
    for run_id, run in runs.items():
        require(run.get("sourceCommit") == AUDITED_SHA and run.get("workloadSha256") == workload_hash, "RUNS", f"identity mismatch: {run_id}")
        require(run.get("sampleCount") == 244, "RUNS", f"sample count mismatch: {run_id}")
        metrics = run.get("metrics")
        require(isinstance(metrics, dict), "RUNS", f"metrics missing: {run_id}")
        require(metrics.get("sampleCount") == 244 and isinstance(metrics.get("requestsPerSecond"), (int, float)) and float(metrics["requestsPerSecond"]) > 0, "RUNS", f"throughput missing: {run_id}")
        require(isinstance(metrics.get("wallDurationNanos"), int) and int(metrics["wallDurationNanos"]) > 0, "RUNS", f"wall duration missing: {run_id}")
        latency = metrics.get("latency")
        require(isinstance(latency, dict) and all(isinstance(latency.get(key), int) and int(latency[key]) > 0 for key in ("meanNanos", "p50Nanos", "p95Nanos", "p99Nanos")), "RUNS", f"latency summary missing: {run_id}")
        by_operation = metrics.get("byOperation")
        require(isinstance(by_operation, dict) and set(by_operation) == set(OPERATIONS), "RUNS", f"operation summary mismatch: {run_id}")
        require(sum(int(value["outputBytes"]) for value in samples[run_id]) == metrics.get("outputBytes"), "RUNS", f"output total mismatch: {run_id}")
    require(runs["minimal"].get("profiledProcessId") is None, "RUNS", "minimal run is unexpectedly profiled")
    require(isinstance(runs["jfr"].get("profiledProcessId"), int), "RUNS", "JFR process identity missing")
    return runs


def validate_profile(manifest: dict[str, object]) -> None:
    profile = manifest.get("profile")
    require(isinstance(profile, dict), "JFR", "profile facts missing")
    require(profile.get("retained") is False, "JFR", "raw profile must not be retained")
    require(isinstance(profile.get("bytes"), int) and int(profile["bytes"]) > 0, "JFR", "profile byte count missing")
    require(isinstance(profile.get("sha256"), str) and re.fullmatch(r"[0-9a-f]{64}", str(profile["sha256"])) is not None, "JFR", "profile hash missing")
    require(isinstance(profile.get("profiledProcessId"), int), "JFR", "profile process identity missing")
    require(isinstance(profile.get("summary"), str) and str(profile["summary"]).strip(), "JFR", "profile summary missing")
    views = profile.get("views")
    require(isinstance(views, dict) and set(views) == {"hot-methods", "allocation-by-site", "gc", "contention-by-site"}, "JFR", "profile views missing")
    require(all(isinstance(value, str) and value.strip() for value in views.values()), "JFR", "empty profile view")
    names = profile.get("recordedEnvironmentNames")
    require(
        isinstance(names, list)
        and bool(names)
        and all(isinstance(name, str) and name for name in names)
        and names == sorted(set(names)),
        "JFR_ENVIRONMENT",
        "recorded environment-variable names are missing or non-canonical",
    )
    sensitive = [name for name in names if SENSITIVE_ENVIRONMENT_NAME.search(name)]
    require(not sensitive, "JFR_ENVIRONMENT", f"credential-like environment variables recorded: {sensitive}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    manifest_path = args.manifest.resolve()
    manifest = load_json(manifest_path, "manifest")
    require(manifest.get("type") == "KAST_PINNED_PERFORMANCE_BASELINE" and manifest.get("schemaVersion") == 1, "MANIFEST", "wrong baseline type")
    source = manifest.get("auditedSource")
    require(isinstance(source, dict) and source.get("commitSha") == AUDITED_SHA, "SOURCE", "audited source mismatch")
    require(manifest.get("runIds") == list(RUN_IDS), "RUNS", "manifest run matrix mismatch")
    require(manifest.get("capsuleRemoved") is True, "CLEANUP", "capsule cleanup is not proven")
    paths = validate_artifacts(manifest_path.parent, manifest)
    expected, workload_hash = validate_workload(paths["workload.json"], manifest.get("workloadSha256"))
    samples = validate_samples(paths["raw-samples.jsonl"], expected)
    validate_runs(paths["runs.jsonl"], workload_hash, samples)
    validate_profile(manifest)
    overhead = manifest.get("observerOverhead")
    require(isinstance(overhead, dict) and set(overhead) == {"p50Percent", "p95Percent", "p99Percent", "throughputPercent", "wallDurationPercent"}, "OVERHEAD", "observer overhead missing")
    print("lean pinned performance baseline: valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
