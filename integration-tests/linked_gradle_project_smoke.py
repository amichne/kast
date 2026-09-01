#!/usr/bin/env python3
from __future__ import annotations

import argparse
import atexit
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path


KNOWN_BLOCKER_EXIT = 42
HARNESS_FAILURE_EXIT = 2
START_TIMEOUT_SECONDS = 600
DARWIN_UNIX_DOMAIN_PATH_MAXIMUM_BYTES = 103

LINKED_GRADLE_SETTINGS = """<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="GradleSettings">
    <option name="linkedExternalProjectsSettings">
      <GradleProjectSettings>
        <option name="externalProjectPath" value="$PROJECT_DIR$" />
        <option name="gradleJvm" value="#JAVA_HOME" />
      </GradleProjectSettings>
    </option>
  </component>
</project>
"""


@dataclass(frozen=True)
class CommandResult:
    arguments: tuple[str, ...]
    returncode: int
    stdout: str
    stderr: str


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Smoke test an installed Kast sidecar against an already-linked Gradle fixture."
    )
    parser.add_argument("--kast", type=Path, required=True)
    parser.add_argument("--idea-home", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--state-root", type=Path, required=True)
    parser.add_argument("--enable-launchd", action="store_true")
    return parser.parse_args()


def run_command(
    executable: Path,
    command: str,
    workspace: Path,
    environment: dict[str, str],
    *extra: str,
) -> CommandResult:
    invocation = (str(executable), command, *extra)
    completed = subprocess.run(
        invocation,
        cwd=workspace,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=START_TIMEOUT_SECONDS,
        check=False,
    )
    return CommandResult(invocation, completed.returncode, completed.stdout, completed.stderr)


def record_result(state_root: Path, name: str, result: CommandResult) -> None:
    (state_root / f"{name}.stdout").write_text(result.stdout, encoding="utf-8")
    (state_root / f"{name}.stderr").write_text(result.stderr, encoding="utf-8")
    print(f"linked-gradle-smoke: {name} exit={result.returncode}")
    if result.stdout.strip():
        print(f"linked-gradle-smoke: {name} stdout={result.stdout.strip()}")
    if result.stderr.strip():
        print(f"linked-gradle-smoke: {name} stderr={result.stderr.strip()}")


def complete_json(result: CommandResult) -> bool:
    if result.returncode != 0:
        return False
    try:
        document = json.loads(result.stdout)
    except json.JSONDecodeError:
        return False
    return isinstance(document, dict) and document.get("status") == "complete"


def ready_json(result: CommandResult) -> bool:
    if result.returncode != 0:
        return False
    try:
        document = json.loads(result.stdout)
    except json.JSONDecodeError:
        return False
    return (
        isinstance(document, dict)
        and document.get("status") == "complete"
        and document.get("state") == "ready"
    )


def idea_failure_evidence(cache_root: Path) -> list[str]:
    evidence: list[str] = []
    for idea_log in sorted(cache_root.rglob("idea.log")):
        for line in idea_log.read_text(encoding="utf-8", errors="replace").splitlines():
            if "GRADLE_IMPORT_FAILED" in line:
                evidence.append(f"{idea_log}: {line.strip()}")
    return evidence


def live_workspace_processes(workspace: Path) -> list[str]:
    completed = subprocess.run(
        ["/bin/ps", "-ax", "-o", "command="],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        return [f"process table failed: {completed.stderr.strip()}"]
    marker = f"--workspace-root={workspace}"
    return [line.strip() for line in completed.stdout.splitlines() if marker in line]


def kast_launchd_labels() -> list[str] | None:
    launchctl = shutil.which("launchctl")
    if launchctl is None:
        return None
    completed = subprocess.run(
        [launchctl, "list"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        return None
    labels: list[str] = []
    for line in completed.stdout.splitlines():
        fields = line.split()
        if len(fields) == 3 and fields[2].startswith("io.github.amichne.kast.indexer."):
            labels.append(fields[2])
    return labels


def main() -> int:
    args = arguments()
    kast = args.kast.expanduser().resolve()
    idea_home = args.idea_home.expanduser().resolve()
    fixture = args.fixture.expanduser().resolve()
    state_root = args.state_root.expanduser().resolve()

    if not kast.is_file() or not os.access(kast, os.X_OK):
        print(f"linked-gradle-smoke: installed command is unavailable: {kast}", file=sys.stderr)
        return HARNESS_FAILURE_EXIT
    product_metadata = (
        idea_home / "product-info.json",
        idea_home / "Resources" / "product-info.json",
    )
    if not any(candidate.is_file() for candidate in product_metadata):
        print(f"linked-gradle-smoke: IDEA home is invalid: {idea_home}", file=sys.stderr)
        return HARNESS_FAILURE_EXIT
    if not fixture.is_dir():
        print(f"linked-gradle-smoke: fixture is unavailable: {fixture}", file=sys.stderr)
        return HARNESS_FAILURE_EXIT
    if state_root.exists():
        print(f"linked-gradle-smoke: state root must not exist: {state_root}", file=sys.stderr)
        return HARNESS_FAILURE_EXIT

    state_root.mkdir(parents=True)
    workspace = state_root / "workspace"
    shutil.copytree(
        fixture,
        workspace,
        ignore=shutil.ignore_patterns(".gradle", ".idea", "build"),
    )
    idea_directory = workspace / ".idea"
    idea_directory.mkdir()
    (idea_directory / "gradle.xml").write_text(LINKED_GRADLE_SETTINGS, encoding="utf-8")

    logical_namespace_root = Path(tempfile.mkdtemp(prefix="kast-lgs-", dir="/tmp"))
    atexit.register(shutil.rmtree, logical_namespace_root, ignore_errors=True)
    endpoint_directory = logical_namespace_root.joinpath(
        *(f"long-logical-runtime-segment-{index:02d}" for index in range(12))
    )
    logical_namespace_bytes = len(os.fsencode(str(endpoint_directory)))
    if logical_namespace_bytes <= DARWIN_UNIX_DOMAIN_PATH_MAXIMUM_BYTES:
        print("linked-gradle-smoke: logical namespace is not long enough", file=sys.stderr)
        return HARNESS_FAILURE_EXIT
    socket_namespace = hashlib.sha256(str(endpoint_directory).encode("utf-8")).hexdigest()[:24]
    runtime_socket_directory = Path("/tmp") / f"kast-runtime-{socket_namespace}"
    atexit.register(shutil.rmtree, runtime_socket_directory, ignore_errors=True)
    print(
        "linked-gradle-smoke: "
        f"logical-runtime-directory={endpoint_directory} "
        f"bytes={logical_namespace_bytes}"
    )
    print(f"linked-gradle-smoke: physical-runtime-directory={runtime_socket_directory}")

    environment = os.environ.copy()
    if args.enable_launchd:
        environment["KAST_ENABLE_LAUNCHD"] = "1"
    else:
        environment.pop("KAST_ENABLE_LAUNCHD", None)
    environment["KAST_RUNTIME_DIRECTORY"] = str(endpoint_directory)
    environment["KAST_RUNTIME_STORE"] = str(state_root / "runtime-store")
    environment["KAST_CACHE_ROOT"] = str(state_root / "intellij-cache")

    baseline_launchd_labels = kast_launchd_labels()
    if baseline_launchd_labels is not None:
        print(f"linked-gradle-smoke: baseline-launchd-labels={baseline_launchd_labels}")

    try:
        started = run_command(kast, "start", workspace, environment, f"--idea-home={idea_home}")
    except subprocess.TimeoutExpired:
        print("linked-gradle-smoke: installed start timed out", file=sys.stderr)
        return HARNESS_FAILURE_EXIT
    record_result(state_root, "start", started)

    active_socket_paths = tuple(sorted(runtime_socket_directory.glob("*.sock")))
    for socket_path in active_socket_paths:
        print(
            "linked-gradle-smoke: "
            f"active-socket={socket_path} bytes={len(os.fsencode(str(socket_path)))}"
        )

    observed_launchd_labels = kast_launchd_labels()
    active_launchd_labels = (
        None
        if baseline_launchd_labels is None or observed_launchd_labels is None
        else sorted(set(observed_launchd_labels) - set(baseline_launchd_labels))
    )
    if active_launchd_labels is not None:
        print(f"linked-gradle-smoke: added-launchd-labels={active_launchd_labels}")

    stop = run_command(kast, "stop", workspace, environment)
    record_result(state_root, "stop", stop)

    observed_retired_launchd_labels = kast_launchd_labels()
    retired_launchd_labels = (
        None
        if baseline_launchd_labels is None or observed_retired_launchd_labels is None
        else sorted(set(observed_retired_launchd_labels) - set(baseline_launchd_labels))
    )
    if retired_launchd_labels is not None:
        print(f"linked-gradle-smoke: remaining-added-launchd-labels={retired_launchd_labels}")

    live_processes = live_workspace_processes(workspace)
    if live_processes:
        for process in live_processes:
            print(f"linked-gradle-smoke: leaked process={process}", file=sys.stderr)
        return HARNESS_FAILURE_EXIT

    if active_launchd_labels is None or retired_launchd_labels is None:
        print("linked-gradle-smoke: launchd state could not be observed", file=sys.stderr)
        return HARNESS_FAILURE_EXIT
    if retired_launchd_labels:
        print("linked-gradle-smoke: launchd job remained after stop", file=sys.stderr)
        return HARNESS_FAILURE_EXIT

    if ready_json(started):
        if len(active_socket_paths) != 1:
            print(
                "linked-gradle-smoke: ready runtime did not expose exactly one mapped socket",
                file=sys.stderr,
            )
            return HARNESS_FAILURE_EXIT
        if len(os.fsencode(str(active_socket_paths[0]))) > DARWIN_UNIX_DOMAIN_PATH_MAXIMUM_BYTES:
            print("linked-gradle-smoke: mapped socket exceeded the path bound", file=sys.stderr)
            return HARNESS_FAILURE_EXIT
        if args.enable_launchd and len(active_launchd_labels) != 1:
            print(
                "linked-gradle-smoke: launchd opt-in did not publish exactly one fixture job",
                file=sys.stderr,
            )
            return HARNESS_FAILURE_EXIT
        if not args.enable_launchd and active_launchd_labels:
            print("linked-gradle-smoke: direct default published a launchd job", file=sys.stderr)
            return HARNESS_FAILURE_EXIT
        if not complete_json(stop):
            print("linked-gradle-smoke: successful start was not cleanly stopped", file=sys.stderr)
            return HARNESS_FAILURE_EXIT
        print("linked-gradle-smoke: PASS already-linked Gradle workspace reached readiness")
        return 0

    failure_evidence = idea_failure_evidence(Path(environment["KAST_CACHE_ROOT"]))
    for evidence in failure_evidence:
        print(f"linked-gradle-smoke: idea-evidence={evidence}")
    if (
        started.returncode == 4
        and '"reason":"session-ended-before-ready"' in started.stderr
        and failure_evidence
    ):
        print(
            "linked-gradle-smoke: BLOCKER duplicate linked-project import ended before readiness"
        )
        return KNOWN_BLOCKER_EXIT

    print("linked-gradle-smoke: start failed outside the known blocker contract", file=sys.stderr)
    return HARNESS_FAILURE_EXIT


if __name__ == "__main__":
    raise SystemExit(main())
