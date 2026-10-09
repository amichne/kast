#!/usr/bin/env python3
"""Retain bounded build outcome evidence on success and process rejection."""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from enum import Enum
import json
from pathlib import Path
import subprocess
import time


class MetricRetention(str, Enum):
    RETAINED = "RETAINED"
    UNAVAILABLE = "UNAVAILABLE"


class Outcome(str, Enum):
    SUCCESS = "SUCCESS"
    PROCESS_FAILED = "PROCESS_FAILED"
    EXECUTION_FAILED = "EXECUTION_FAILED"
    NATIVE_METRICS_MISSING = "NATIVE_METRICS_MISSING"


@dataclass(frozen=True)
class Evidence:
    schemaVersion: int
    outcome: Outcome
    elapsedMilliseconds: int
    exitCode: int
    nativeMetrics: MetricRetention


def run(command: list[str], report: Path, native_report: Path, execute=subprocess.run, clock=time.monotonic) -> Evidence:
    started = clock()
    try:
        result = execute(command, check=False)
        code = result.returncode
        outcome = Outcome.SUCCESS if code == 0 else Outcome.PROCESS_FAILED
    except OSError:
        code = 1
        outcome = Outcome.EXECUTION_FAILED
    retained = MetricRetention.UNAVAILABLE
    if outcome is Outcome.SUCCESS and native_report.is_file() and not native_report.is_symlink() and native_report.stat().st_size <= 1024 * 1024:
        try:
            if isinstance(json.loads(native_report.read_text()), dict):
                retained = MetricRetention.RETAINED
        except (OSError, ValueError):
            pass
    if outcome is Outcome.SUCCESS and retained is MetricRetention.UNAVAILABLE:
        outcome = Outcome.NATIVE_METRICS_MISSING
        code = 1
    evidence = Evidence(1, outcome, max(0, round((clock() - started) * 1000)), code, retained)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(asdict(evidence), indent=2) + "\n")
    return evidence


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--native-report", type=Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not args.command or args.command[0] != "--" or len(args.command) < 2:
        parser.error("one build command after -- is required")
    evidence = run(args.command[1:], args.report, args.native_report)
    print(f"build-execution: {evidence.outcome.value}", flush=True)
    raise SystemExit(evidence.exitCode)


if __name__ == "__main__":
    main()
