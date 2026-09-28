#!/usr/bin/env python3
"""Verify the resolved Gradle gate retains its required proofs."""
from enum import Enum
from pathlib import Path
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
PRODUCT_GATE_COMMAND = ["python3", "distribution/release/run_product_gate.py"]
REQUIRED = frozenset({
    ":productBuildGate", ":productGateVersionTest", ":cli:test", ":cli:nativeTest", ":app-server:test",
    ":runtime:hosted:test", ":workspace:intellij-read:test", ":query:protocol:test", ":evidence:sqlite:test",
    ":topology:contract:test", ":topology:build:test", ":topology:service:test", ":topology:intellij:test",
    ":evidence:topology-sqlite:test",
    ":verifyKastArchitecture", ":verifyConfigurationIngress", ":installedProductTest",
    ":testCheckoutInstaller", ":installationLifecycleTest", ":installerRemovalTest",
    ":localInstallationTest", ":installerFixtureTest",
})


class Failure(str, Enum):
    REQUIRED_PROOF_MISSING = "REQUIRED_PROOF_MISSING"


def inspect(output):
    tasks = frozenset(re.findall(r"^(:\S+) SKIPPED$", output, re.MULTILINE))
    findings = [dict(condition=Failure.REQUIRED_PROOF_MISSING.value, task=task)
                for task in sorted(REQUIRED - tasks)]
    return dict(status="rejected" if findings else "complete", tasks=sorted(tasks), findings=findings)


def main():
    command = PRODUCT_GATE_COMMAND + ["--dry-run"]
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=False)
    if result.returncode:
        print(result.stdout)
        return result.returncode
    report = inspect(result.stdout)
    report["command"] = command
    report["sourceRevision"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    destination = ROOT / "build/reports/ci/routine-gate.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "findings": report["findings"]}))
    return 1 if report["findings"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
