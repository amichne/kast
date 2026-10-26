#!/usr/bin/env python3
"""Verify the resolved Gradle gate retains its required proofs."""
from enum import Enum
from pathlib import Path
import json
import re
import subprocess
from validation_scope import NATIVE_INPUT_MODULES

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
    ":documentationToolingTest", ":verifyDocumentationGlossary", ":verifyDocumentationMarkdown",
})


class Failure(str, Enum):
    REQUIRED_PROOF_MISSING = "REQUIRED_PROOF_MISSING"
    NATIVE_INPUT_INVENTORY_MISMATCH = "NATIVE_INPUT_INVENTORY_MISMATCH"


def inspect_native_inputs(output):
    owners = frozenset(path.replace(":", "/") for path in re.findall(
        r"^:([^ ]+):compileKotlin SKIPPED$", output, re.MULTILINE))
    return [dict(condition=Failure.NATIVE_INPUT_INVENTORY_MISMATCH.value,
                 missing=sorted(owners - NATIVE_INPUT_MODULES), stale=sorted(NATIVE_INPUT_MODULES - owners))] \
        if owners != NATIVE_INPUT_MODULES else []


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
    native_command = ["./gradlew", "-Pversion=0.0.0", ":distribution:cli:nativeCompile", "--dry-run", "--console=plain"]
    native = subprocess.run(native_command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=False)
    if native.returncode:
        print(native.stdout)
        return native.returncode
    report["findings"].extend(inspect_native_inputs(native.stdout))
    report["status"] = "rejected" if report["findings"] else "complete"
    report["nativeInputCommand"] = native_command
    report["command"] = command
    report["sourceRevision"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    destination = ROOT / "build/reports/ci/routine-gate.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "findings": report["findings"]}))
    return 1 if report["findings"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
