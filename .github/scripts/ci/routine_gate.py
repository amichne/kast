#!/usr/bin/env python3
"""Verify the resolved Gradle gate retains its required proofs."""

from __future__ import annotations

import argparse
import json
import os
import re
import signal
import subprocess
import tempfile
from collections.abc import Callable
from dataclasses import asdict, dataclass, replace
from enum import StrEnum
from pathlib import Path

import gradle_graph
from validation_scope import NATIVE_INPUT_MODULES, PRODUCT_MODULES

ROOT = Path(__file__).resolve().parents[3]
PRODUCT_GATE_COMMAND = ["python3", "distribution/release/run_product_gate.py"]
REQUIRED = frozenset(
    {
        ":productBuildGate",
        ":productGateVersionTest",
        ":cli:test",
        ":cli:udsTest",
        ":app-server:test",
        ":runtime:hosted:test",
        ":workspace:intellij-read:test",
        ":query:protocol:test",
        ":evidence:sqlite:test",
        ":topology:contract:test",
        ":topology:build:test",
        ":topology:service:test",
        ":topology:intellij:test",
        ":evidence:topology-sqlite:test",
        ":verifyKastArchitecture",
        ":verifyConfigurationIngress",
        ":installedProductTest",
        ":testCheckoutInstaller",
        ":installationLifecycleTest",
        ":installerRemovalTest",
        ":localInstallationTest",
        ":installerFixtureTest",
        ":documentationToolingTest",
        ":verifyDocumentationGlossary",
        ":verifyDocumentationMarkdown",
        ":sourceValidationGate",
    }
) | frozenset(
    f":{module.replace('/', ':')}:{task}"
    for module in PRODUCT_MODULES
    for task in ("check", "scopedCheck")
)


class Failure(StrEnum):
    REQUIRED_PROOF_MISSING = "REQUIRED_PROOF_MISSING"
    NATIVE_INPUT_INVENTORY_MISMATCH = "NATIVE_INPUT_INVENTORY_MISMATCH"


class Status(StrEnum):
    COMPLETE = "complete"
    REJECTED = "rejected"


class Outcome(StrEnum):
    STARTED = "STARTED"
    COMPLETE = "COMPLETE"
    PROOF_REJECTED = "PROOF_REJECTED"
    PROCESS_FAILED = "PROCESS_FAILED"
    START_FAILED = "START_FAILED"
    TIMED_OUT = "TIMED_OUT"
    CANCELLED = "CANCELLED"
    EVIDENCE_UNAVAILABLE = "EVIDENCE_UNAVAILABLE"
    SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE"


class Phase(StrEnum):
    PRODUCT = "product"
    NATIVE_INPUTS = "native-inputs"


@dataclass(frozen=True)
class RequiredProofMissing:
    task: str
    condition: Failure = Failure.REQUIRED_PROOF_MISSING


@dataclass(frozen=True)
class NativeInputMismatch:
    missing: tuple[str, ...]
    stale: tuple[str, ...]
    condition: Failure = Failure.NATIVE_INPUT_INVENTORY_MISMATCH


Finding = RequiredProofMissing | NativeInputMismatch


@dataclass(frozen=True)
class Inspection:
    status: Status
    tasks: tuple[str, ...]
    findings: tuple[Finding, ...]


def membership(output: str) -> Inspection:
    tasks = frozenset(re.findall(r"^(:\S+) SKIPPED$", output, re.MULTILINE))
    findings = tuple(RequiredProofMissing(task) for task in sorted(REQUIRED - tasks))
    return Inspection(
        Status.REJECTED if findings else Status.COMPLETE, tuple(sorted(tasks)), findings
    )


def native_inventory(output: str) -> tuple[NativeInputMismatch, ...]:
    owners = frozenset(
        path.replace(":", "/")
        for path in re.findall(r"^:([^ ]+):compileKotlin SKIPPED$", output, re.MULTILINE)
    )
    if owners == NATIVE_INPUT_MODULES:
        return ()
    return (
        NativeInputMismatch(
            tuple(sorted(owners - NATIVE_INPUT_MODULES)),
            tuple(sorted(NATIVE_INPUT_MODULES - owners)),
        ),
    )


def inspect(output: str) -> dict[str, object]:
    # Retain the existing parser's public mapping contract.
    result = asdict(membership(output))
    result["tasks"] = list(result["tasks"])
    result["findings"] = list(result["findings"])
    return result


def inspect_native_inputs(output: str) -> list[dict[str, str | list[str]]]:
    return [
        dict(condition=item.condition, missing=list(item.missing), stale=list(item.stale))
        for item in native_inventory(output)
    ]


NATIVE_COMMAND = (
    "./gradlew",
    "--max-workers=2",
    "-Dorg.gradle.jvmargs=-Xmx5g",
    "-Pversion=0.0.0",
    "-PcontrolVersion=0.0.0",
    "-PhostedPluginVersion=0.0.0",
    ":cli:nativeCompile",
    "--dry-run",
    "--console=plain",
    "--info",
    "--no-daemon",
)


@dataclass(frozen=True)
class PhaseExecution:
    phase: Phase
    result: gradle_graph.Result


@dataclass(frozen=True)
class PhaseProgress:
    phase: Phase
    deadlineSeconds: int


@dataclass(frozen=True)
class Summary:
    status: Status
    outcome: Outcome
    phase: Phase | None
    exitCode: int
    findings: tuple[Finding, ...]


@dataclass(frozen=True)
class Record:
    command: tuple[str, ...]
    nativeInputCommand: tuple[str, ...]
    status: Status = Status.REJECTED
    outcome: Outcome = Outcome.STARTED
    exitCode: int = 1
    phase: Phase | None = None
    tasks: tuple[str, ...] = ()
    findings: tuple[Finding, ...] = ()
    phases: tuple[PhaseExecution, ...] = ()
    sourceRevision: str | None = None
    schemaVersion: int = 1


def retain(destination: Path, record: Record) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent, prefix=".routine-gate-") as temporary:
        staged = Path(temporary) / "record.json"
        _ = staged.write_text(json.dumps(asdict(record), indent=2) + "\n")
        _ = staged.replace(destination)


def cancelled(record: Record, interruption: gradle_graph.Interruption) -> Record | None:
    if interruption.reason is gradle_graph.Cancellation.NOT_REQUESTED:
        return None
    code = 130 if interruption.reason is gradle_graph.Cancellation.INTERRUPT else 143
    return replace(record, status=Status.REJECTED, outcome=Outcome.CANCELLED, exitCode=code)


Runner = Callable[[tuple[str, ...], Path, Path, gradle_graph.Budget], gradle_graph.Result]


def run(
    root: Path,
    destination: Path,
    budget: gradle_graph.Budget = gradle_graph.DEFAULT_BUDGET,
    *,
    runner: Runner = gradle_graph.run,
) -> Record:
    record = Record(tuple([*PRODUCT_GATE_COMMAND, "--dry-run"]), NATIVE_COMMAND)
    retain(destination, record)
    reports = destination.parent / "routine-gate"
    # Clear both phases before work: an early failure must not retain old native success.
    for phase in Phase:
        directory = reports / phase.value
        directory.mkdir(parents=True, exist_ok=True)
        for name in gradle_graph.REPORTS:
            (directory / name).unlink(missing_ok=True)
    interruption = gradle_graph.Interruption()
    previous = {number: signal.getsignal(number) for number in (signal.SIGINT, signal.SIGTERM)}

    def execute(current: Record) -> Record:
        for phase, command in (
            (Phase.PRODUCT, current.command),
            (Phase.NATIVE_INPUTS, current.nativeInputCommand),
        ):
            if stopped := cancelled(current, interruption):
                return stopped
            current = replace(current, phase=phase)
            retain(destination, current)
            print(
                "routine-gate: " + json.dumps(asdict(PhaseProgress(phase, budget.seconds))),
                flush=True,
            )
            if stopped := cancelled(current, interruption):
                return stopped
            try:
                result = runner(command, root, reports / phase.value, budget)
            except (OSError, ValueError):
                return replace(current, outcome=Outcome.EVIDENCE_UNAVAILABLE)
            current = replace(current, phases=(*current.phases, PhaseExecution(phase, result)))
            if stopped := cancelled(current, interruption):
                return stopped
            if result.outcome is not gradle_graph.Outcome.SUCCEEDED:
                return replace(
                    current, outcome=Outcome(result.outcome.value), exitCode=result.exitCode
                )
            try:
                output = (reports / phase.value / "task-graph.log").read_text(errors="replace")
            except OSError:
                return replace(current, outcome=Outcome.EVIDENCE_UNAVAILABLE)
            if phase is Phase.PRODUCT:
                inspected = membership(output)
                current = replace(current, tasks=inspected.tasks, findings=inspected.findings)
            else:
                current = replace(current, findings=native_inventory(output))
            if current.findings:
                return replace(current, outcome=Outcome.PROOF_REJECTED)
        # This is the observed final revision, not a continuous checkout lease.
        environment = {
            key: value for key, value in os.environ.items() if not key.startswith("GIT_")
        }
        environment.update(
            GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1", GIT_NO_REPLACE_OBJECTS="1"
        )
        try:
            observed = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=root,
                env=environment,
                text=True,
                capture_output=True,
                timeout=30,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            return replace(current, outcome=Outcome.SOURCE_UNAVAILABLE)
        revision = observed.stdout.strip()
        if observed.returncode or re.fullmatch(r"[0-9a-f]{40}", revision) is None:
            return replace(current, outcome=Outcome.SOURCE_UNAVAILABLE)
        return replace(
            current,
            status=Status.COMPLETE,
            outcome=Outcome.COMPLETE,
            exitCode=0,
            sourceRevision=revision,
        )

    try:
        for number in previous:
            _ = signal.signal(number, interruption.receive)
        record = execute(record)
        record = cancelled(record, interruption) or record
        retain(destination, record)
        summary = Summary(
            record.status, record.outcome, record.phase, record.exitCode, record.findings
        )
        print("routine-gate: " + json.dumps(asdict(summary)), flush=True)
    finally:
        for number, handler in previous.items():
            _ = signal.signal(number, handler)
    # Reconcile signals recorded during receipt/output publication or handler restoration.
    if stopped := cancelled(record, interruption):
        if stopped != record:
            record = stopped
            retain(destination, record)
            summary = Summary(
                record.status, record.outcome, record.phase, record.exitCode, record.findings
            )
            print("routine-gate: " + json.dumps(asdict(summary)), flush=True)
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    _ = parser.add_argument(
        "--graph-timeout-seconds", type=int, default=gradle_graph.DEFAULT_BUDGET.seconds
    )
    args = parser.parse_args()
    budget = gradle_graph.Budget(args.graph_timeout_seconds)
    return run(ROOT, ROOT / "build/reports/ci/routine-gate.json", budget).exitCode


if __name__ == "__main__":
    raise SystemExit(main())
