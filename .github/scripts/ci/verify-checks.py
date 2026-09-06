#!/usr/bin/env python3
"""Routine CI: cheap contracts, one build, one installed semantic journey."""
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
    # Reject receipt/driver regressions before downloading IDEA or compiling Kotlin.
    for directory in ("distribution/release", "integration-tests"):
        gate.run([sys.executable, "-m", "unittest", "discover", "-s", directory,
                  "-p", "test_*.py"], ROOT, environment)
    gate.prepare_idea(ROOT, environment)
    gate.run(["./gradlew", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx5g",
              "productBuildGate", "enterpriseAcceptance"], ROOT, environment)
    gate.admit_source(ROOT, sha)
    print("ci-checks: passed; release qualification requires manual CI dispatch", flush=True)


if __name__ == "__main__":
    main()
