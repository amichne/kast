#!/usr/bin/env python3
"""Routine CI: build, unit/contract tests, architecture, and packaging smoke."""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "distribution/release"))
import release_gate as gate


def main():
    environment = os.environ.copy()
    environment["JAVA_HOME"] = environment["KAST_RELEASE_JDK_25"]
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    gate.admit_source(ROOT, sha)
    gate.run(
        ["./gradlew", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx5g", "productBuildGate"],
        ROOT,
        environment,
    )
    gate.admit_source(ROOT, sha)
    print("ci-checks: passed", flush=True)


if __name__ == "__main__":
    main()
