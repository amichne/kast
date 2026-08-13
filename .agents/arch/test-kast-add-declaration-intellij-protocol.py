#!/usr/bin/env python3
"""Focused mutation proof for the KIP-030 physical protocol ledger."""

from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LEDGER = Path(__file__).with_name("kast-add-declaration-intellij-protocol.json")
VALIDATOR = Path(__file__).with_name("validate-kast-add-declaration-intellij-protocol.py")


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
    with tempfile.TemporaryDirectory(prefix="kast-add-declaration-protocol-") as temporary:
        scratch = Path(temporary)

        missing_api = copy.deepcopy(ledger)
        missing_api["selectedExecutor"]["publicApis"].pop()
        require_rejected(scratch, "missing-api", missing_api, "EXECUTOR_API")

        widened_runtime = copy.deepcopy(ledger)
        widened_runtime["runtimeBuild"] = "261.99999.1"
        require_rejected(scratch, "widened-runtime", widened_runtime, "RUNTIME")

        missing_plan_input = copy.deepcopy(ledger)
        missing_plan_input["planInputs"].remove("targetPreimageSha256")
        require_rejected(scratch, "missing-plan-input", missing_plan_input, "PLAN_INPUT")

        shortened_inside_command = copy.deepcopy(ledger)
        shortened_inside_command["observedBehavior"]["referenceShortening"] = "PERFORMED"
        require_rejected(scratch, "shortening-claim", shortened_inside_command, "BEHAVIOR")

        missing_limitation = copy.deepcopy(ledger)
        missing_limitation["closedLimitations"].pop()
        require_rejected(scratch, "missing-limitation", missing_limitation, "LIMITATION")

        extra_write_work = copy.deepcopy(ledger)
        extra_write_work["selectedExecutor"]["insideWriteCommand"].append("INDEX_OR_SEARCH")
        require_rejected(scratch, "extra-write-work", extra_write_work, "WRITE_BOUNDARY")

    print("KIP-030 add-declaration IntelliJ protocol: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
