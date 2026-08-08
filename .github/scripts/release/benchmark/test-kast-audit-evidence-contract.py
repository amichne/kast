#!/usr/bin/env python3
"""Focused contract test for reproducible Kast performance audit evidence."""

from __future__ import annotations

import copy
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[4]
VALIDATOR = Path(__file__).with_name("validate-kast-audit-evidence.py")
SCHEMA = (
    REPOSITORY_ROOT
    / "cli-rs/protocol/benchmarks/audit/kast-audit-evidence.schema.json"
)
FIXTURE = REPOSITORY_ROOT / "cli-rs/protocol/benchmarks/audit/complete.fixture.json"
KPA_REQUIRED_BINDINGS = (
    "environment.artifactRoot",
    "environment.cacheRoot",
    "environment.homeRoot",
    "environment.installationRoot",
    "environment.locale",
    "environment.runtimeRoot",
    "environment.socketPath",
    "environment.temporaryRoot",
    "environment.timezone",
    "environment.variableDigestSha256",
    "host.architecture",
    "host.cpuModel",
    "host.ide.buildNumber",
    "host.ide.product",
    "host.ide.version",
    "host.logicalCpuCount",
    "host.memoryBytes",
    "host.operatingSystem",
    "host.operatingSystemVersion",
    "installation.binarySha256",
    "installation.binaryVersion",
    "installation.bundleSha256",
    "installation.bundleSizeBytes",
    "installation.runtimeArtifactSha256",
    "installation.runtimeBuild",
    "installation.sealedInstallationSha256",
    "instrumentation.artifacts.manifestPath",
    "instrumentation.artifacts.manifestSha256",
    "instrumentation.artifacts.profilePath",
    "instrumentation.artifacts.profileSha256",
    "instrumentation.artifacts.tracePath",
    "instrumentation.artifacts.traceSha256",
    "instrumentation.profiler.configurationSha256",
    "instrumentation.profiler.kind",
    "instrumentation.profiler.samplePeriodMicros",
    "instrumentation.profiler.settings",
    "instrumentation.telemetryMode",
    "instrumentation.tracingMode",
    "metrics.allocationBytes",
    "metrics.cpuNanos",
    "metrics.gcPauseNanos",
    "metrics.latencyNanos",
    "metrics.lockWaitNanos",
    "metrics.outputBytes",
    "metrics.traceCorrelation.correlatedPhaseCount",
    "metrics.traceCorrelation.requestId",
    "metrics.traceCorrelation.traceId",
    "runState.cacheState",
    "runState.coldOrWarm",
    "runState.indexingState",
    "runState.relationshipsEnabled",
    "runState.sourceIndexGeneration",
    "source.commitSha",
    "source.repository",
    "source.treeSha",
    "source.worktreeState",
    "teardown.completedAt",
    "teardown.evidenceSha256",
    "teardown.processClosure",
    "teardown.runtimeState",
    "teardown.socketState",
    "teardown.temporaryRootState",
    "teardown.workspaceState",
    "workload.arguments",
    "workload.command",
    "workload.concurrency",
    "workload.inputSha256",
    "workload.invocationCount",
    "workload.iterationCount",
    "workload.operation",
    "workspace.buildCount",
    "workspace.fileCount",
    "workspace.gradleProjectCount",
    "workspace.kotlinFileCount",
    "workspace.shapeSha256",
    "workspace.sourceBytes",
)


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def canonical_json(payload: object) -> str:
    return json.dumps(payload, indent=2, sort_keys=True) + "\n"


def required_property_paths(payload: dict[str, object]) -> list[tuple[str, ...]]:
    paths: list[tuple[str, ...]] = []

    def visit(value: object, prefix: tuple[str, ...]) -> None:
        if not isinstance(value, dict):
            return
        for key in sorted(value):
            path = (*prefix, key)
            paths.append(path)
            visit(value[key], path)

    visit(payload, ())
    source = ("source",)
    return [source, *(path for path in paths if path != source)]


def without_path(
    payload: dict[str, object], path: tuple[str, ...]
) -> dict[str, object]:
    candidate = copy.deepcopy(payload)
    owner: dict[str, object] = candidate
    for component in path[:-1]:
        nested = owner[component]
        if not isinstance(nested, dict):
            fail(f"test path is not an object binding: {'.'.join(path)}")
        owner = nested
    del owner[path[-1]]
    return candidate


def require_named_bindings(payload: dict[str, object]) -> None:
    for binding in KPA_REQUIRED_BINDINGS:
        value: object = payload
        for component in binding.split("."):
            if not isinstance(value, dict) or component not in value:
                fail(f"complete fixture is missing KPA-001 binding: {binding}")
            value = value[component]


def hermetic_environment(root: Path) -> dict[str, str]:
    roots = {
        "HOME": root / "home",
        "KAST_ARTIFACT_ROOT": root / "artifacts",
        "KAST_CACHE_HOME": root / "cache",
        "KAST_HOME": root / "installation",
        "KAST_RUNTIME_DIR": root / "runtime",
        "KAST_SOCKET_PATH": root / "socket/kast.sock",
        "TMPDIR": root / "temporary",
    }
    for name, path in roots.items():
        directory = path.parent if name == "KAST_SOCKET_PATH" else path
        directory.mkdir(parents=True, exist_ok=True)
    return {
        **{name: str(path) for name, path in roots.items()},
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "PYTHONDONTWRITEBYTECODE": "1",
    }


def run_validator(evidence: Path, environment: dict[str, str]) -> subprocess.CompletedProcess[str]:
    if not VALIDATOR.is_file():
        # No validation boundary means malformed evidence is currently accepted.
        return subprocess.CompletedProcess([], 0, "", "")
    return subprocess.run(
        [sys.executable, str(VALIDATOR), "--evidence", str(evidence)],
        cwd=REPOSITORY_ROOT,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )


def load_fixture() -> tuple[str, dict[str, object]]:
    try:
        raw = FIXTURE.read_text(encoding="utf-8")
        payload = json.loads(raw)
    except (OSError, json.JSONDecodeError) as error:
        fail(f"complete fixture is unreadable: {error}")
    if not isinstance(payload, dict):
        fail("complete fixture root must be an object")
    return raw, payload


def main() -> int:
    fixture_raw, complete = load_fixture()
    require_named_bindings(complete)
    scratch_path: Path | None = None
    with tempfile.TemporaryDirectory(prefix="kast-audit-contract-") as scratch:
        scratch_path = Path(scratch)
        environment = hermetic_environment(scratch_path)
        evidence = scratch_path / "artifacts/evidence.json"

        first_failure: subprocess.CompletedProcess[str] | None = None
        for path in required_property_paths(complete):
            evidence.write_text(canonical_json(without_path(complete, path)), encoding="utf-8")
            result = run_validator(evidence, environment)
            if result.returncode == 0:
                fail(f"{'.'.join(path)}: validator accepted a missing required binding")
            if first_failure is None:
                first_failure = result

        evidence.write_text(fixture_raw, encoding="utf-8")
        complete_result = run_validator(evidence, environment)
        if complete_result.returncode != 0:
            fail(f"complete fixture was rejected: {complete_result.stderr.strip()}")

        extra = copy.deepcopy(complete)
        extra["unboundObservation"] = "not-governed"
        evidence.write_text(canonical_json(extra), encoding="utf-8")
        if run_validator(evidence, environment).returncode == 0:
            fail("validator accepted an undeclared observation")

        reversed_root = dict(reversed(list(complete.items())))
        evidence.write_text(json.dumps(reversed_root, indent=2) + "\n", encoding="utf-8")
        if run_validator(evidence, environment).returncode == 0:
            fail("validator accepted non-canonical key ordering")

        if fixture_raw != canonical_json(complete):
            fail("complete fixture is not canonical JSON")
        try:
            schema_raw = SCHEMA.read_text(encoding="utf-8")
            schema = json.loads(schema_raw)
        except (OSError, json.JSONDecodeError) as error:
            fail(f"audit schema is unreadable: {error}")
        if schema_raw != canonical_json(schema):
            fail("audit schema is not canonical JSON")
        if schema.get("examples") != [complete]:
            fail("schema complete example and canonical fixture have drifted")

        evidence.write_text(canonical_json(without_path(complete, ("source",))), encoding="utf-8")
        repeat = run_validator(evidence, environment)
        if first_failure is None or repeat.stderr != first_failure.stderr:
            fail("validator failure output is not deterministic")

    if scratch_path is None or scratch_path.exists():
        fail("hermetic test roots were not cleanly removed")
    print("audit evidence contract: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
