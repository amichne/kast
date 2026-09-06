#!/usr/bin/env python3
"""Routine CI: build, unit/contract tests, architecture, and packaging smoke."""
from pathlib import Path
import os
import subprocess

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
    run([
        "./gradlew", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx5g",
        "productBuildGate",
    ], environment)
    run([
        "bash", ".github/scripts/release/admit-source.sh",
        "--repository-root", str(ROOT),
        "--expected-source-revision", sha,
    ], environment)
    print("ci-checks: passed", flush=True)


if __name__ == "__main__":
    main()
