#!/usr/bin/env python3
"""Capture a lean, rerunnable pinned public-tooling performance baseline."""

from __future__ import annotations

import argparse
import atexit
import concurrent.futures
import hashlib
import json
import math
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, NoReturn


ROOT = Path(__file__).resolve().parents[4]
AUDITED_SHA = "60dcf69d0431d38d8a2bb5476ee349a9f796829b"
DEFAULT_DELIVERY_BASE = "20fbd5bad5aebec10996494d49b5c29d8d97ee03"
AUDITED_BUNDLE = "kast-macos-arm64-0.22.0-18-g60dcf69d0.tar.gz"
AUDITED_BUNDLE_SHA = "034ebe7469e15b98efd7d622a1bb447d325f9fe7b15084591f1f6b54a8e3c3ef"
BASELINE_RUNS = ("minimal", "jfr")
PROFILE_NAME = "profile-clean-steady.jfr"
JFR_MAX_BYTES = 80 * 1024 * 1024
MIN_FREE_BYTES = 12 * 1024 * 1024 * 1024
JAVA_21_HOME = Path("/Users/amichne/.sdkman/candidates/java/current")
SAFE_PARENT_ENVIRONMENT = (
    "__CF_USER_TEXT_ENCODING",
    "COMMAND_MODE",
    "LOGNAME",
    "SHELL",
    "TERM",
    "USER",
)
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
OUTPUT_FILES = (
    "host-environment.json",
    "manifest.json",
    "raw-samples.jsonl",
    "runs.jsonl",
    "workload.json",
)
SENSITIVE_ENVIRONMENT_NAME = re.compile(
    r"(?:^|_)(?:ACCESS_KEY|AUTH|AUTHORIZATION|COOKIE|CREDENTIALS?|PASSWORD|PASSWD|PRIVATE_KEY|SECRET|TOKEN)(?:$|_)",
    re.IGNORECASE,
)
ACTIVE_CAPSULE: Path | None = None


def fail(code: str, message: str) -> NoReturn:
    print(f"error: {code}: {message}", file=sys.stderr)
    raise SystemExit(1)


def canonical(value: object) -> str:
    return json.dumps(value, indent=2, sort_keys=True) + "\n"


def compact(value: object) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def bytes_sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256(path: Path) -> str:
    return bytes_sha256(path.read_bytes())


def utc_now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def run(
    command: list[str],
    *,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
    timeout: float = 120,
    check: bool = True,
) -> subprocess.CompletedProcess[bytes]:
    completed = subprocess.run(
        command,
        cwd=cwd,
        env=env,
        timeout=timeout,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and completed.returncode != 0:
        detail = (completed.stderr or completed.stdout)[-2000:].decode("utf-8", "replace")
        fail("COMMAND_FAILED", f"{command!r}: {completed.returncode}: {detail}")
    return completed


def text(command: list[str], **kwargs: Any) -> str:
    return run(command, **kwargs).stdout.decode("utf-8", "replace").strip()


def write_json(path: Path, value: object) -> None:
    path.write_text(canonical(value), encoding="utf-8")


def write_jsonl(path: Path, values: list[dict[str, object]]) -> None:
    path.write_text("".join(compact(value) + "\n" for value in values), encoding="utf-8")


def percentile(values: list[int], fraction: float) -> int:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def canonical_workload() -> dict[str, object]:
    requests: list[dict[str, object]] = []
    sequence = 0
    for stage, concurrency, count in STAGES:
        for ordinal in range(count):
            requests.append(
                {
                    "concurrency": concurrency,
                    "operation": OPERATIONS[ordinal % len(OPERATIONS)],
                    "ordinal": ordinal,
                    "sequence": sequence,
                    "stage": stage,
                }
            )
            sequence += 1
    return {
        "auditedSourceCommit": AUDITED_SHA,
        "operationOrder": list(OPERATIONS),
        "requestCount": len(requests),
        "requests": requests,
        "schemaVersion": 1,
        "stages": [
            {"concurrency": concurrency, "count": count, "id": stage}
            for stage, concurrency, count in STAGES
        ],
        "type": "KAST_PUBLIC_MIXED_PERFORMANCE_WORKLOAD",
    }


def latency_summary(samples: list[dict[str, object]]) -> dict[str, object]:
    durations = [int(sample["durationNanos"]) for sample in samples]
    return {
        "meanNanos": round(sum(durations) / len(durations)),
        "p50Nanos": percentile(durations, 0.50),
        "p95Nanos": percentile(durations, 0.95),
        "p99Nanos": percentile(durations, 0.99),
    }


def group_summary(samples: list[dict[str, object]]) -> dict[str, object]:
    return {
        "classifications": dict(sorted(Counter(str(sample["classification"]) for sample in samples).items())),
        "latency": latency_summary(samples),
        "outputBytes": sum(int(sample["outputBytes"]) for sample in samples),
        "sampleCount": len(samples),
    }


def summarize_samples(samples: list[dict[str, object]], wall_duration_nanos: int) -> dict[str, object]:
    by_operation: dict[str, list[dict[str, object]]] = defaultdict(list)
    by_stage: dict[str, list[dict[str, object]]] = defaultdict(list)
    for sample in samples:
        by_operation[str(sample["operation"])].append(sample)
        by_stage[str(sample["stage"])].append(sample)
    return {
        **group_summary(samples),
        "byOperation": {key: group_summary(by_operation[key]) for key in sorted(by_operation)},
        "byStage": {key: group_summary(by_stage[key]) for key in sorted(by_stage)},
        "requestsPerSecond": round(len(samples) / (wall_duration_nanos / 1_000_000_000), 3),
        "wallDurationNanos": wall_duration_nanos,
    }


def percent_change(before: float, after: float) -> float:
    return round(((after - before) / before) * 100.0, 3) if before else 0.0


def observer_overhead(minimal: dict[str, object], jfr: dict[str, object]) -> dict[str, object]:
    minimal_latency = minimal["latency"]
    jfr_latency = jfr["latency"]
    assert isinstance(minimal_latency, dict) and isinstance(jfr_latency, dict)
    return {
        "p50Percent": percent_change(float(minimal_latency["p50Nanos"]), float(jfr_latency["p50Nanos"])),
        "p95Percent": percent_change(float(minimal_latency["p95Nanos"]), float(jfr_latency["p95Nanos"])),
        "p99Percent": percent_change(float(minimal_latency["p99Nanos"]), float(jfr_latency["p99Nanos"])),
        "throughputPercent": percent_change(float(minimal["requestsPerSecond"]), float(jfr["requestsPerSecond"])),
        "wallDurationPercent": percent_change(float(minimal["wallDurationNanos"]), float(jfr["wallDurationNanos"])),
    }


def host_snapshot(capsule: Path) -> dict[str, object]:
    free = shutil.disk_usage(capsule).free
    if free < MIN_FREE_BYTES:
        fail("DISK_CAPACITY", f"only {free} bytes free")
    return {
        "freeKiB": free // 1024,
        "loadAverages": [round(value, 3) for value in os.getloadavg()],
        "observedAtUtc": utc_now(),
    }


def task_processes(capsule: Path) -> dict[int, str]:
    capsule_text = str(capsule)
    output = text(["ps", "-axo", "pid=,command="])
    found: dict[int, str] = {}
    for line in output.splitlines():
        fields = line.strip().split(None, 1)
        if len(fields) != 2 or capsule_text not in fields[1]:
            continue
        try:
            pid = int(fields[0])
        except ValueError:
            continue
        if pid != os.getpid():
            found[pid] = fields[1]
    return found


def contain_task_processes(capsule: Path) -> None:
    remaining = task_processes(capsule)
    for pid in remaining:
        try:
            os.kill(pid, 15)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + 8
    while remaining and time.monotonic() < deadline:
        time.sleep(0.2)
        remaining = task_processes(capsule)
    for pid in remaining:
        try:
            os.kill(pid, 9)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + 5
    while task_processes(capsule) and time.monotonic() < deadline:
        time.sleep(0.2)
    if task_processes(capsule):
        fail("TASK_CLEANUP", "capsule-owned processes survived")


def cleanup_capsule() -> None:
    global ACTIVE_CAPSULE
    if ACTIVE_CAPSULE is None:
        return
    try:
        contain_task_processes(ACTIVE_CAPSULE)
        if ACTIVE_CAPSULE.exists():
            shutil.rmtree(ACTIVE_CAPSULE)
    finally:
        ACTIVE_CAPSULE = None


atexit.register(cleanup_capsule)


def base_environment(capsule: Path, java_home: Path) -> dict[str, str]:
    roots = {
        "HOME": capsule / "home",
        "KAST_HOME": capsule / "installation",
        "KAST_CONFIG_HOME": capsule / "config",
        "KAST_CACHE_HOME": capsule / "cache",
        "KAST_RUNTIME_DIR": capsule / "runtime",
        "KAST_SOCKET_PATH": capsule / "socket/kast.sock",
        "KAST_ARTIFACT_ROOT": capsule / "artifacts",
        "GRADLE_USER_HOME": capsule / "gradle",
        "CARGO_HOME": capsule / "cargo",
        "CARGO_TARGET_DIR": capsule / "target",
        "TMPDIR": capsule / "tmp",
        "XDG_CACHE_HOME": capsule / "xdg/cache",
        "XDG_CONFIG_HOME": capsule / "xdg/config",
        "XDG_DATA_HOME": capsule / "xdg/data",
    }
    for name, path in roots.items():
        (path.parent if name == "KAST_SOCKET_PATH" else path).mkdir(parents=True, exist_ok=True)
    (roots["GRADLE_USER_HOME"] / "gradle.properties").write_text(
        f"org.gradle.java.installations.paths={JAVA_21_HOME}\n",
        encoding="utf-8",
    )
    java_user_home = capsule / "java/home"
    java_tmp = capsule / "java/tmp"
    java_user_home.mkdir(parents=True)
    java_tmp.mkdir(parents=True)
    environment = {
        **{name: os.environ[name] for name in SAFE_PARENT_ENVIRONMENT if name in os.environ},
        **{name: str(path) for name, path in roots.items()},
        "JAVA_HOME": str(java_home),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "PATH": os.pathsep.join(
            (
                str(java_home / "bin"),
                "/opt/homebrew/bin",
                "/usr/local/bin",
                "/usr/bin",
                "/bin",
                "/usr/sbin",
                "/sbin",
            )
        ),
        "TZ": "UTC",
        "PYTHONDONTWRITEBYTECODE": "1",
        "_JAVA_OPTIONS": f"-Duser.home={java_user_home} -Djava.io.tmpdir={java_tmp}",
    }
    return environment


def install_bundle(bundle: Path, capsule: Path, environment: dict[str, str]) -> dict[str, object]:
    extracted = capsule / "bundle"
    extracted.mkdir()
    with tarfile.open(bundle, "r:gz") as archive:
        archive.extractall(extracted, filter="data")
    roots = [path for path in extracted.iterdir() if path.is_dir()]
    if len(roots) != 1:
        fail("BUNDLE_SHAPE", "expected one bundle root")
    run([str(roots[0] / "install.sh"), "--source", str(roots[0]), "--harness", "none"], cwd=roots[0], env=environment, timeout=300)
    kast = Path(environment["KAST_HOME"]) / "current/bin/kast"
    kastctl = Path(environment["KAST_HOME"]) / "current/libexec/kastctl"
    if not kast.is_file() or not kastctl.is_file():
        fail("INSTALLATION", "audited CLI pair is missing")
    return {
        "bundleSha256": sha256(bundle),
        "kast": kast,
        "kastSha256": sha256(kast),
        "kastctl": kastctl,
        "version": text([str(kast), "--version"], env=environment),
    }


def prepare_source(capsule: Path) -> tuple[Path, dict[str, str]]:
    source = capsule / "source"
    run(["git", "clone", "--no-hardlinks", str(ROOT), str(source)], timeout=300)
    run(["git", "checkout", "--detach", AUDITED_SHA], cwd=source)
    return source, source_identity(source)


def source_identity(source: Path) -> dict[str, str]:
    commit = text(["git", "rev-parse", "HEAD"], cwd=source)
    tree = text(["git", "rev-parse", "HEAD^{tree}"], cwd=source)
    status = text(["git", "status", "--porcelain=v1", "--untracked-files=all"], cwd=source)
    if commit != AUDITED_SHA or status:
        fail("SOURCE_IDENTITY", f"commit={commit} dirty={status!r}")
    return {"commitSha": commit, "treeSha": tree}


def reset_runtime_state(environment: dict[str, str]) -> None:
    state = Path(environment["KAST_HOME"]) / "state"
    if state.exists():
        shutil.rmtree(state)
    state.mkdir(parents=True)


def run_environment(base: dict[str, str], capsule: Path, run_id: str) -> tuple[dict[str, str], Path | None]:
    root = capsule / "runs" / run_id
    for child in ("home", "config", "cache", "runtime", "tmp", "xdg/cache", "xdg/config", "xdg/data", "java/home", "java/tmp", "artifacts"):
        (root / child).mkdir(parents=True, exist_ok=True)
    environment = {
        **base,
        "HOME": str(root / "home"),
        "KAST_CONFIG_HOME": str(root / "config"),
        "KAST_CACHE_HOME": str(root / "cache"),
        "KAST_RUNTIME_DIR": str(root / "runtime"),
        "KAST_SOCKET_PATH": str(root / "runtime/kast.sock"),
        "KAST_ARTIFACT_ROOT": str(root / "artifacts"),
        "TMPDIR": str(root / "tmp"),
        "XDG_CACHE_HOME": str(root / "xdg/cache"),
        "XDG_CONFIG_HOME": str(root / "xdg/config"),
        "XDG_DATA_HOME": str(root / "xdg/data"),
        "KAST_WORKSPACE_ID": bytes_sha256(run_id.encode())[:8],
        "_JAVA_OPTIONS": f"-Duser.home={root / 'java/home'} -Djava.io.tmpdir={root / 'java/tmp'}",
    }
    profile: Path | None = None
    if run_id == "jfr":
        profile = root / "artifacts/profile-%p.jfr"
        environment["JAVA_TOOL_OPTIONS"] = (
            f"-XX:StartFlightRecording=filename={profile},settings=profile,dumponexit=true,maxsize={JFR_MAX_BYTES},disk=true "
            "-XX:FlightRecorderOptions=stackdepth=256"
        )
    else:
        environment.pop("JAVA_TOOL_OPTIONS", None)
    return environment, profile


def configure_runtime(kastctl: Path, source: Path, environment: dict[str, str], host_app: Path) -> None:
    run(
        [str(kastctl), "--output", "json", "config", "set", "indexer.hostCommand", str(host_app), "--workspace-root", str(source)],
        cwd=source,
        env=environment,
        timeout=60,
    )


def public_json(kast: Path, source: Path, environment: dict[str, str], arguments: list[str], timeout: float = 120) -> dict[str, object]:
    completed = run([str(kast), "--output", "json", *arguments], cwd=source, env=environment, timeout=timeout, check=False)
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout)[:1200].decode("utf-8", "replace")
        fail("PUBLIC_OPERATION", f"{arguments!r}: {completed.returncode}: {detail}")
    try:
        value = json.loads(completed.stdout)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        fail("PUBLIC_OUTPUT", f"{arguments!r}: {error}")
    if not isinstance(value, dict):
        fail("PUBLIC_OUTPUT", f"{arguments!r}: non-object response")
    return value


def wait_ready(kast: Path, source: Path, environment: dict[str, str], timeout: float = 600) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    last: dict[str, object] = {}
    while time.monotonic() < deadline:
        last = public_json(kast, source, environment, [])
        result = last.get("result")
        if isinstance(result, dict) and result.get("ready") is True and result.get("referenceIndexReady") is True:
            return result
        time.sleep(2)
    fail("BOOTSTRAP_TIMEOUT", compact(last))


def runtime_pid(source: Path, environment: dict[str, str]) -> int:
    registry = Path(environment["KAST_CACHE_HOME"]) / "workspaces" / environment["KAST_WORKSPACE_ID"] / "daemons.json"
    try:
        entries = json.loads(registry.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail("RUNTIME_IDENTITY", str(error))
    matches = [
        entry
        for entry in entries
        if isinstance(entry, dict)
        and entry.get("backendName") == "indexer"
        and entry.get("workspaceRoot") == str(source.resolve())
    ]
    if len(matches) != 1 or isinstance(matches[0].get("pid"), bool) or not isinstance(matches[0].get("pid"), int):
        fail("RUNTIME_IDENTITY", f"unexpected descriptors: {matches}")
    return int(matches[0]["pid"])


def bootstrap(kast: Path, source: Path, environment: dict[str, str]) -> tuple[dict[str, str], int, int]:
    started = time.monotonic_ns()
    public_json(kast, source, environment, ["workspace", "ensure"], 600)
    wait_ready(kast, source, environment)
    class_result = public_json(kast, source, environment, ["symbol", "resolve", "--query", "io.github.amichne.kast.api.client.WorkspaceDirectoryResolver"], 300)
    function_result = public_json(kast, source, environment, ["symbol", "resolve", "--query", "io.github.amichne.kast.server.skill.selectorOperationFamilies"], 300)
    nodes_result = public_json(kast, source, environment, ["graph", "nodes"], 300)
    try:
        selectors = {
            "CLASS_SELECTOR": str(class_result["result"]["selector"]),
            "FUNCTION_SELECTOR": str(function_result["result"]["selector"]),
            "NODE_SELECTOR": str(nodes_result["result"]["nodes"][0]["nodeSelector"]),
        }
    except (KeyError, IndexError, TypeError) as error:
        fail("SELECTORS", str(error))
    return selectors, runtime_pid(source, environment), time.monotonic_ns() - started


def operation_arguments(name: str, selectors: dict[str, str]) -> list[str]:
    return {
        "home": [],
        "file-list": ["file", "list", "--match", "**/*.kt"],
        "symbol-search": ["symbol", "search", "--query", "WorkspaceDirectoryResolver"],
        "symbol-show": ["symbol", "show", "--selector", selectors["FUNCTION_SELECTOR"]],
        "references": ["relation", "references", "--selector", selectors["CLASS_SELECTOR"]],
        "graph-summary": ["graph", "summary"],
        "graph-nodes": ["graph", "nodes"],
        "graph-neighbors": ["graph", "neighbors", "--node-selector", selectors["NODE_SELECTOR"]],
        "graph-impact": ["graph", "impact", "--selector", selectors["CLASS_SELECTOR"]],
        "diagnostic": ["diagnostic", "check", "--file", "analysis-server/src/main/kotlin/io/github/amichne/kast/server/skill/SelectorAuthority.kt"],
    }[name]


def invoke_sample(
    kast: Path,
    source: Path,
    environment: dict[str, str],
    run_id: str,
    request: dict[str, object],
    selectors: dict[str, str],
) -> dict[str, object]:
    started = time.monotonic_ns()
    try:
        completed = run(
            [str(kast), "--output", "json", *operation_arguments(str(request["operation"]), selectors)],
            cwd=source,
            env=environment,
            timeout=60,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return {**request, "classification": "timeout", "durationNanos": time.monotonic_ns() - started, "exitCode": -1, "outputBytes": 0, "resultType": "timeout", "runId": run_id, "timedOut": True}
    payload = completed.stdout if completed.stdout else completed.stderr
    parsed: dict[str, object] = {}
    try:
        candidate = json.loads(payload)
        if isinstance(candidate, dict):
            parsed = candidate
    except (json.JSONDecodeError, UnicodeDecodeError):
        pass
    result = parsed.get("result")
    return {
        **request,
        "classification": parsed.get("status") or "unparsed",
        "durationNanos": time.monotonic_ns() - started,
        "exitCode": completed.returncode,
        "outputBytes": len(completed.stdout) + len(completed.stderr),
        "resultType": result.get("type") if isinstance(result, dict) and result.get("type") else "unparsed",
        "runId": run_id,
        "timedOut": False,
    }


def execute_workload(
    kast: Path,
    source: Path,
    environment: dict[str, str],
    run_id: str,
    workload: dict[str, object],
    selectors: dict[str, str],
) -> tuple[list[dict[str, object]], int]:
    requests = workload["requests"]
    assert isinstance(requests, list)
    grouped: dict[str, list[dict[str, object]]] = defaultdict(list)
    for request in requests:
        assert isinstance(request, dict)
        grouped[str(request["stage"])].append(request)
    samples: list[dict[str, object]] = []
    started = time.monotonic_ns()
    for stage, concurrency, _ in STAGES:
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
            futures = [pool.submit(invoke_sample, kast, source, environment, run_id, request, selectors) for request in grouped[stage]]
            samples.extend(future.result() for future in futures)
    wall_duration = time.monotonic_ns() - started
    samples.sort(key=lambda sample: int(sample["sequence"]))
    if any(sample["timedOut"] or sample["resultType"] == "unparsed" for sample in samples):
        fail("WORKLOAD", f"{run_id} produced timeout or unparsed results")
    return samples, wall_duration


def stop_runtime(kastctl: Path, source: Path, environment: dict[str, str], capsule: Path) -> None:
    run(
        [str(kastctl), "--output", "json", "developer", "runtime", "stop", "--workspace-root", str(source)],
        cwd=source,
        env=environment,
        timeout=120,
        check=False,
    )
    contain_task_processes(capsule)


def profile_path_for_pid(pattern: Path, pid: int) -> Path:
    return pattern.with_name(pattern.name.replace("%p", str(pid)))


def profile_environment_names(events: str) -> list[str]:
    names = sorted(set(re.findall(r'^\s*key = "([^"]+)"\s*$', events, re.MULTILINE)))
    sensitive = [name for name in names if SENSITIVE_ENVIRONMENT_NAME.search(name)]
    if sensitive:
        fail("JFR_ENVIRONMENT", f"credential-like environment variables recorded: {sensitive}")
    return names


def profile_details(jfr: Path, profile: Path, pid: int) -> dict[str, object]:
    summary = run([str(jfr), "summary", str(profile)], timeout=120, check=False)
    if summary.returncode != 0:
        fail("JFR", "recording is unreadable")
    views: dict[str, str] = {}
    for view in ("hot-methods", "allocation-by-site", "gc", "contention-by-site"):
        completed = run([str(jfr), "view", "--width", "160", "--cell-height", "5", view, str(profile)], timeout=180, check=False)
        if completed.returncode != 0:
            fail("JFR", f"view failed: {view}")
        views[view] = completed.stdout.decode("utf-8", "replace")[:20_000]
    environment = run(
        [str(jfr), "print", "--events", "jdk.InitialEnvironmentVariable", str(profile)],
        timeout=120,
        check=False,
    )
    if environment.returncode != 0:
        fail("JFR", "environment scan failed")
    return {
        "bytes": profile.stat().st_size,
        "profiledProcessId": pid,
        "recordedEnvironmentNames": profile_environment_names(environment.stdout.decode("utf-8", "replace")),
        "retained": False,
        "sha256": sha256(profile),
        "summary": summary.stdout.decode("utf-8", "replace")[:20_000],
        "views": views,
    }


def capture_run(
    *,
    base: dict[str, str],
    capsule: Path,
    host_app: Path,
    installation: dict[str, object],
    jfr: Path,
    run_id: str,
    source: Path,
    source_value: dict[str, str],
    workload: dict[str, object],
    workload_sha: str,
) -> tuple[dict[str, object], list[dict[str, object]], dict[str, object] | None]:
    reset_runtime_state(base)
    environment, profile_pattern = run_environment(base, capsule, run_id)
    kast = installation["kast"]
    kastctl = installation["kastctl"]
    assert isinstance(kast, Path) and isinstance(kastctl, Path)
    configure_runtime(kastctl, source, environment, host_app)
    host_before = host_snapshot(capsule)
    selectors, pid, bootstrap_duration = bootstrap(kast, source, environment)
    samples, wall_duration = execute_workload(kast, source, environment, run_id, workload, selectors)
    stop_runtime(kastctl, source, environment, capsule)
    host_after = host_snapshot(capsule)
    source_identity(source)
    profile: dict[str, object] | None = None
    if run_id == "jfr":
        assert profile_pattern is not None
        emitted = profile_path_for_pid(profile_pattern, pid)
        deadline = time.monotonic() + 10
        while (not emitted.is_file() or emitted.stat().st_size == 0) and time.monotonic() < deadline:
            time.sleep(0.2)
        if not emitted.is_file() or emitted.stat().st_size == 0:
            fail("JFR", f"profile was not emitted for pid {pid}")
        profile = profile_details(jfr, emitted, pid)
        emitted.unlink()
    record: dict[str, object] = {
        "bootstrapDurationNanos": bootstrap_duration,
        "hostAfter": host_after,
        "hostBefore": host_before,
        "metrics": summarize_samples(samples, wall_duration),
        "profiledProcessId": pid if run_id == "jfr" else None,
        "runId": run_id,
        "sampleCount": len(samples),
        "sourceCommit": source_value["commitSha"],
        "sourceTree": source_value["treeSha"],
        "workloadSha256": workload_sha,
    }
    return record, samples, profile


def host_environment(java_home: Path, installation: dict[str, object]) -> dict[str, object]:
    java_version = run([str(java_home / "bin/java"), "-version"], check=False).stderr.decode("utf-8", "replace").splitlines()[0]
    return {
        "capturedAtUtc": utc_now(),
        "java": java_version,
        "kastVersion": installation["version"],
        "logicalCpuCount": os.cpu_count(),
        "machine": platform.machine(),
        "macOS": platform.mac_ver()[0],
        "platform": platform.platform(),
        "python": platform.python_version(),
    }


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--delivery-base", default=DEFAULT_DELIVERY_BASE)
    parser.add_argument("--prior-audit-root", type=Path, default=Path("/private/tmp/kast-pr569-audit.LmHuzC"))
    return parser.parse_args()


def main() -> int:
    global ACTIVE_CAPSULE
    args = arguments()
    if text(["git", "rev-parse", "HEAD"], cwd=ROOT) != args.delivery_base:
        fail("DELIVERY_BASE", "branch head changed before capture")
    bundle = args.prior_audit_root / AUDITED_BUNDLE
    if not bundle.is_file() or sha256(bundle) != AUDITED_BUNDLE_SHA:
        fail("BUNDLE_IDENTITY", str(bundle))
    output_root = ROOT / "cli-rs/protocol/benchmarks/audit/kpa-002-pinned-pre-fix"
    output_root.mkdir(parents=True, exist_ok=True)
    for name in (*OUTPUT_FILES, PROFILE_NAME):
        path = output_root / name
        if path.exists():
            path.unlink()

    host_app = Path("/Users/amichne/Applications/IntelliJ IDEA.app")
    java_home = host_app / "Contents/jbr/Contents/Home"
    jfr = java_home / "bin/jfr"
    if not jfr.is_file():
        fail("JFR", str(jfr))
    ACTIVE_CAPSULE = Path(tempfile.mkdtemp(prefix="kast-572-", dir="/private/tmp"))
    capsule = ACTIVE_CAPSULE
    base = base_environment(capsule, java_home)
    host_snapshot(capsule)
    installation = install_bundle(bundle, capsule, base)
    source, source_value = prepare_source(capsule)
    workload = canonical_workload()
    workload_bytes = canonical(workload).encode()
    workload_sha = bytes_sha256(workload_bytes)
    records: list[dict[str, object]] = []
    all_samples: list[dict[str, object]] = []
    profile: dict[str, object] | None = None
    for run_id in BASELINE_RUNS:
        record, samples, run_profile = capture_run(
            base=base,
            capsule=capsule,
            host_app=host_app,
            installation=installation,
            jfr=jfr,
            run_id=run_id,
            source=source,
            source_value=source_value,
            workload=workload,
            workload_sha=workload_sha,
        )
        records.append(record)
        all_samples.extend(samples)
        if run_profile is not None:
            profile = run_profile
    minimal_classifications = [(sample["classification"], sample["resultType"]) for sample in all_samples if sample["runId"] == "minimal"]
    jfr_classifications = [(sample["classification"], sample["resultType"]) for sample in all_samples if sample["runId"] == "jfr"]
    if minimal_classifications != jfr_classifications:
        fail("CLASSIFICATION_PARITY", "JFR changed workload results")
    if profile is None:
        fail("JFR", "profile details are missing")

    workload_path = output_root / "workload.json"
    raw_path = output_root / "raw-samples.jsonl"
    runs_path = output_root / "runs.jsonl"
    host_path = output_root / "host-environment.json"
    workload_path.write_bytes(workload_bytes)
    write_jsonl(raw_path, all_samples)
    write_jsonl(runs_path, records)
    write_json(host_path, host_environment(java_home, installation))
    contain_task_processes(capsule)
    if list(capsule.rglob("*.sock")):
        fail("TASK_CLEANUP", "task sockets survived runtime stop")
    shutil.rmtree(capsule)
    ACTIVE_CAPSULE = None

    artifacts = {
        path.name: {"bytes": path.stat().st_size, "sha256": sha256(path)}
        for path in (host_path, raw_path, runs_path, workload_path)
    }
    minimal_metrics = records[0]["metrics"]
    jfr_metrics = records[1]["metrics"]
    assert isinstance(minimal_metrics, dict) and isinstance(jfr_metrics, dict)
    manifest = {
        "artifacts": artifacts,
        "auditedSource": source_value,
        "binary": {
            "bundleSha256": installation["bundleSha256"],
            "kastSha256": installation["kastSha256"],
            "version": installation["version"],
        },
        "capsuleRemoved": True,
        "capturedAtUtc": utc_now(),
        "deliveryBase": args.delivery_base,
        "issueUrl": "https://github.com/amichne/kast/issues/572",
        "observerOverhead": observer_overhead(minimal_metrics, jfr_metrics),
        "profile": profile,
        "runIds": list(BASELINE_RUNS),
        "runSummaries": {str(record["runId"]): record["metrics"] for record in records},
        "schemaVersion": 1,
        "type": "KAST_PINNED_PERFORMANCE_BASELINE",
        "workloadSha256": workload_sha,
    }
    write_json(output_root / "manifest.json", manifest)
    print(
        compact(
            {
                "manifest": str(output_root / "manifest.json"),
                "minimalRequestsPerSecond": minimal_metrics["requestsPerSecond"],
                "minimalP95Nanos": minimal_metrics["latency"]["p95Nanos"],
                "profileBytes": profile["bytes"],
            }
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
