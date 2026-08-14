#!/usr/bin/env python3
"""Focused behavior proof for the IntelliJ substrate source/performance ledger."""

from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LEDGER = Path(__file__).with_name("kast-intellij-substrate-ledger.json")
VALIDATOR = Path(__file__).with_name("validate-kast-intellij-substrate-ledger.py")


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def validate(path: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(VALIDATOR), "--ledger", str(path)],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )


def require_rejected(
    scratch: Path,
    name: str,
    value: dict[str, object],
    expected_code: str,
) -> None:
    candidate = scratch / f"{name}.json"
    candidate.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    result = validate(candidate)
    if result.returncode == 0:
        fail(f"validator accepted {name}")
    if f"invalid: {expected_code}:" not in result.stderr:
        fail(f"{name} returned the wrong failure: {result.stderr.strip()}")


def main() -> int:
    green = validate(LEDGER)
    if green.returncode != 0:
        fail(f"complete ledger rejected: {green.stderr.strip()}")

    ledger = json.loads(LEDGER.read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory(prefix="kast-intellij-ledger-test-") as temporary:
        scratch = Path(temporary)

        missing_source = copy.deepcopy(ledger)
        del missing_source["sources"]["kastArchitectureBaseline"]["commitSha"]
        require_rejected(scratch, "missing-source", missing_source, "SOURCE")

        missing_machine = copy.deepcopy(ledger)
        del missing_machine["captureEnvironment"]["machine"]["memoryBytes"]
        require_rejected(scratch, "missing-machine", missing_machine, "ENVIRONMENT")

        missing_stage = copy.deepcopy(ledger)
        missing_stage["measurementContract"]["stageTimings"].remove("nativeQueryNanos")
        require_rejected(scratch, "missing-stage", missing_stage, "MEASUREMENTS")

        unknown_operation = copy.deepcopy(ledger)
        unknown_operation["ticketPerformanceBaselines"]["KIP-018"].append("unknown-operation")
        require_rejected(scratch, "unknown-operation", unknown_operation, "TICKET_BASELINE")

        rewritten_history = copy.deepcopy(ledger)
        rewritten_history["historicalEvidence"][0]["medianNanos"] = 640_000_000
        require_rejected(scratch, "rewritten-history", rewritten_history, "HISTORICAL")

        unsupported_reference_claim = copy.deepcopy(ledger)
        unsupported_reference_claim["referenceProjectLatencyClaims"].append(
            {"operationId": "symbol-discovery", "medianNanos": 1}
        )
        require_rejected(
            scratch,
            "unsupported-reference-claim",
            unsupported_reference_claim,
            "REFERENCE_CLAIM",
        )

        incomparable_claim = copy.deepcopy(ledger)
        incomparable_claim["comparisonClaims"].append(
            {
                "baselineEvidenceId": "pr604-warm-symbol-discovery",
                "candidateEvidenceId": "pr604-warm-symbol-discovery",
            }
        )
        require_rejected(
            scratch,
            "incomparable-claim",
            incomparable_claim,
            "COMPARISON",
        )

        missing_increment_stage = copy.deepcopy(ledger)
        del missing_increment_stage["performanceIncrements"][0]["stageObservations"]["resourceAdmissionNanos"]
        require_rejected(
            scratch,
            "missing-increment-stage",
            missing_increment_stage,
            "PERFORMANCE_INCREMENT",
        )

        forbidden_increment_work = copy.deepcopy(ledger)
        forbidden_increment_work["performanceIncrements"][0]["workCounters"]["sqliteWriteCount"] = 1
        require_rejected(
            scratch,
            "forbidden-increment-work",
            forbidden_increment_work,
            "PERFORMANCE_INCREMENT",
        )

    print("IntelliJ substrate source/performance ledger: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
