#!/usr/bin/env python3
"""Routine CI: build, deterministic tests, architecture and packaging; runtime qualification is separate."""
from pathlib import Path
import os
import subprocess
from routine_gate import GRADLE

ROOT = Path(__file__).resolve().parents[3]


def run(command: list[str], environment: dict[str, str]) -> None:
    result = subprocess.run(command, cwd=ROOT, env=environment, check=False)
    if result.returncode:
        raise SystemExit(result.returncode)


def main() -> None:
    environment = os.environ.copy()
    environment["JAVA_HOME"] = environment["KAST_RELEASE_JDK_25"]
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    run([
        "bash", ".github/scripts/release/admit-source.sh",
        "--repository-root", str(ROOT),
        "--expected-source-revision", sha,
    ], environment)
    run(["python3", "distribution/release/test_resolve_version.py"], environment)
    run(["python3", "distribution/release/test_verify_environment.py"], environment)
    run(["python3", ".github/scripts/ci/test_routine_gate.py"], environment)
    run(["python3", ".github/scripts/ci/routine_gate.py"], environment)
    run(GRADLE + ["productBuildGate"], environment)
    run([
        "bash", ".github/scripts/release/admit-source.sh",
        "--repository-root", str(ROOT),
        "--expected-source-revision", sha,
    ], environment)
    print("ci-checks: passed", flush=True)


if __name__ == "__main__":
    main()
