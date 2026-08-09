#!/usr/bin/env python3
"""Focused behavior test for the lean pinned performance baseline."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
from contextlib import redirect_stderr
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
CAPTURE = Path(__file__).with_name("capture-kast-pinned-baseline.py")
VALIDATOR = Path(__file__).with_name("validate-kast-pinned-baseline.py")
AUDITED_SHA = "60dcf69d0431d38d8a2bb5476ee349a9f796829b"


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def load_capture():
    spec = importlib.util.spec_from_file_location("kast_pinned_performance", CAPTURE)
    if spec is None or spec.loader is None:
        fail(f"cannot import {CAPTURE}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def canonical(value: object) -> str:
    return json.dumps(value, indent=2, sort_keys=True) + "\n"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(canonical(value), encoding="utf-8")


def write_jsonl(path: Path, values: list[dict[str, object]]) -> None:
    path.write_text(
        "".join(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n" for value in values),
        encoding="utf-8",
    )


def sample(run_id: str, request: dict[str, object], duration: int) -> dict[str, object]:
    return {
        "classification": "complete",
        "concurrency": request["concurrency"],
        "durationNanos": duration,
        "exitCode": 0,
        "operation": request["operation"],
        "outputBytes": 100,
        "resultType": "synthetic",
        "runId": run_id,
        "sequence": request["sequence"],
        "stage": request["stage"],
        "timedOut": False,
    }


def synthetic_evidence(capture, root: Path) -> Path:
    root.mkdir()
    workload = capture.canonical_workload()
    workload_path = root / "workload.json"
    write_json(workload_path, workload)
    workload_hash = sha256(workload_path)
    samples: list[dict[str, object]] = []
    runs: list[dict[str, object]] = []
    for run_id, duration, wall in (("minimal", 1_000_000, 300_000_000), ("jfr", 1_100_000, 330_000_000)):
        run_samples = [sample(run_id, request, duration) for request in workload["requests"]]
        samples.extend(run_samples)
        runs.append(
            {
                "bootstrapDurationNanos": 1_000_000_000,
                "hostAfter": {"freeKiB": 40_000_000, "loadAverages": [1.0, 1.0, 1.0]},
                "hostBefore": {"freeKiB": 40_000_000, "loadAverages": [1.0, 1.0, 1.0]},
                "metrics": capture.summarize_samples(run_samples, wall),
                "profiledProcessId": 1234 if run_id == "jfr" else None,
                "runId": run_id,
                "sampleCount": 244,
                "sourceCommit": AUDITED_SHA,
                "sourceTree": "1" * 40,
                "workloadSha256": workload_hash,
            }
        )
    raw_path = root / "raw-samples.jsonl"
    runs_path = root / "runs.jsonl"
    host_path = root / "host-environment.json"
    write_jsonl(raw_path, samples)
    write_jsonl(runs_path, runs)
    write_json(host_path, {"logicalCpuCount": 10, "platform": "synthetic", "python": sys.version.split()[0]})
    artifacts = {
        path.name: {"bytes": path.stat().st_size, "sha256": sha256(path)}
        for path in (host_path, raw_path, runs_path, workload_path)
    }
    manifest = {
        "artifacts": artifacts,
        "auditedSource": {"commitSha": AUDITED_SHA, "treeSha": "1" * 40},
        "capsuleRemoved": True,
        "issueUrl": "https://github.com/amichne/kast/issues/572",
        "observerOverhead": capture.observer_overhead(runs[0]["metrics"], runs[1]["metrics"]),
        "profile": {
            "bytes": 13,
            "profiledProcessId": 1234,
            "recordedEnvironmentNames": ["HOME", "PATH"],
            "retained": False,
            "sha256": "a" * 64,
            "summary": "synthetic",
            "views": {"allocation-by-site": "synthetic", "contention-by-site": "synthetic", "gc": "synthetic", "hot-methods": "synthetic"},
        },
        "runIds": ["minimal", "jfr"],
        "schemaVersion": 1,
        "type": "KAST_PINNED_PERFORMANCE_BASELINE",
        "workloadSha256": workload_hash,
    }
    manifest_path = root / "manifest.json"
    write_json(manifest_path, manifest)
    return manifest_path


def main() -> int:
    capture = load_capture()
    inherited = {
        "CODEX_GITHUB_PERSONAL_ACCESS_TOKEN": "test-only-codex-token",
        "GITHUB_PERSONAL_ACCESS_TOKEN": "test-only-github-token",
        "UNRELATED_PARENT_STATE": "test-only-parent-state",
    }
    previous = {name: os.environ.get(name) for name in inherited}
    os.environ.update(inherited)
    try:
        with tempfile.TemporaryDirectory(prefix="kast-pinned-environment-test-") as temporary:
            environment = capture.base_environment(Path(temporary) / "capsule", Path("/safe/java"))
            gradle_properties = Path(environment["GRADLE_USER_HOME"]) / "gradle.properties"
            toolchain_properties = gradle_properties.read_text(encoding="utf-8") if gradle_properties.is_file() else None
    finally:
        for name, value in previous.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
    leaked = sorted(set(inherited) & set(environment))
    if leaked:
        fail(f"capture environment inherited caller state: {leaked}")
    expected_toolchain = f"org.gradle.java.installations.paths={capture.JAVA_21_HOME}\n"
    if toolchain_properties != expected_toolchain:
        fail("capture environment did not bind the required Java 21 toolchain")
    if capture.BASELINE_RUNS != ("minimal", "jfr"):
        fail(f"unexpected run matrix: {capture.BASELINE_RUNS}")
    workload = capture.canonical_workload()
    if workload["requestCount"] != 244 or [stage["concurrency"] for stage in workload["stages"]] != [1, 1, 4, 8, 16]:
        fail("fixed workload shape changed")
    synthetic = [sample("minimal", request, (int(request["sequence"]) + 1) * 1_000) for request in workload["requests"]]
    metrics = capture.summarize_samples(synthetic, 1_000_000_000)
    if metrics["sampleCount"] != 244 or metrics["requestsPerSecond"] != 244.0 or set(metrics["byOperation"]) != set(capture.OPERATIONS):
        fail(f"performance summary is incomplete: {metrics}")
    safe_names = capture.profile_environment_names(
        'jdk.InitialEnvironmentVariable {\n  key = "PATH"\n  value = "/usr/bin:/bin"\n}\n'
    )
    if safe_names != ["PATH"]:
        fail(f"profile environment names were not extracted: {safe_names}")
    try:
        with redirect_stderr(io.StringIO()):
            capture.profile_environment_names(
                'jdk.InitialEnvironmentVariable {\n  key = "GITHUB_PERSONAL_ACCESS_TOKEN"\n  value = "redacted"\n}\n'
            )
    except SystemExit:
        pass
    else:
        fail("collector accepted a credential-bearing profile")

    with tempfile.TemporaryDirectory(prefix="kast-pinned-performance-test-") as temporary:
        scratch = Path(temporary)
        manifest = synthetic_evidence(capture, scratch / "evidence")
        command = [sys.executable, str(VALIDATOR), "--manifest", str(manifest)]
        green = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=False)
        if green.returncode != 0:
            fail(f"complete lean baseline rejected: {green.stderr.strip()}")
        payload = json.loads(manifest.read_text(encoding="utf-8"))
        payload["profile"]["recordedEnvironmentNames"] = ["GITHUB_PERSONAL_ACCESS_TOKEN"]
        write_json(manifest, payload)
        credential_red = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=False)
        if credential_red.returncode == 0:
            fail("validator accepted credential-bearing profile facts")
        payload["profile"]["recordedEnvironmentNames"] = ["HOME", "PATH"]
        payload["runIds"] = ["minimal"]
        write_json(manifest, payload)
        red = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=False)
        if red.returncode == 0:
            fail("validator accepted a missing JFR comparison run")
    print("lean pinned performance baseline: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
