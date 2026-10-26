#!/usr/bin/env python3
"""Run the complete source gate with stable resources and no candidate authority."""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from enum import Enum
import json
from pathlib import Path
import re
import subprocess
import time

from validation_scope import PRODUCT_MODULES, ROOT

VERSION = "0.0.0"
COMMAND = (
    "./gradlew", "--max-workers=2", "-Dorg.gradle.jvmargs=-Xmx5g",
    f"-Pversion={VERSION}", f"-PcontrolVersion={VERSION}", f"-PhostedPluginVersion={VERSION}",
    "sourceValidationGate", "--console=plain", "--profile",
)
SOURCE_REQUIRED = frozenset({
    ":sourceValidationGate", ":check", ":build-logic:check", ":verifyKastArchitecture", ":verifyJsonContracts",
    ":verifyConfigurationIngress", ":verifyKnowledgeBase", ":hostObservationTest", ":componentReleaseTest",
    ":verifyDocumentationGlossary", ":verifyDocumentationMarkdown", ":documentationToolingTest",
    ":verifyKotlinSemanticInputs", ":verifyKotlinSemanticCoverage", ":verifyKotlinSemanticInventory",
    ":relation:intellij:semanticCoverageFixtureTest", ":runtime:hosted:semanticCoverageFixtureTest",
    ":symbol:intellij:semanticCoverageFixtureTest", ":relation:intellij:dependencyFixtureTest",
    ":runtime:hosted:dependencyFixtureTest", ":cli:nativeTest", ":cli:queryDeliveryContractTest",
    ":cli:queryDeliverySchemaTest", ":cli:verifyMintlifyCallableReference", ":cli:queryPresentationTest",
    ":app-server:verifyPublicQueryGeneration", ":productGateVersionTest", ":installerFixtureTest",
    ":installationLifecycleTest", ":priorInstallationCleanupTest", ":installationRecoveryTest",
    ":installationSystemPythonTest", ":localInstallationTest", ":installerRemovalTest",
    ":testCheckoutInstaller", ":installerEntrypointTest",
}) | frozenset(f":{module.replace('/', ':')}:{task}" for module in PRODUCT_MODULES for task in ("scopedCheck", "check", "test"))
FORBIDDEN = frozenset({
    "stageKastControlProduct", "installedProductTest", "assembleRelease", "assembleKastControlDist",
    "verifyReleaseRuntimeAdmission", "hostedPlugin", "generateHostReleaseRecord",
})


class Outcome(str, Enum):
    SOURCE_VALIDATED = "SOURCE_VALIDATED"
    GRAPH_REJECTED = "GRAPH_REJECTED"
    PROCESS_FAILED = "PROCESS_FAILED"
    SOURCE_CHANGED = "SOURCE_CHANGED"


@dataclass(frozen=True)
class Graph:
    tasks: tuple[str, ...]
    missing: tuple[str, ...]
    forbidden: tuple[str, ...]

    @property
    def accepted(self) -> bool:
        return bool(self.tasks) and not self.missing and not self.forbidden


@dataclass(frozen=True)
class Evidence:
    schemaVersion: int
    outcome: Outcome
    sourceRevision: str
    sourceTree: str
    validationVersion: str
    elapsedMilliseconds: int
    exitCode: int
    graph: Graph
    compilerInvocations: int


def inspect_graph(output: str) -> Graph:
    tasks = frozenset(re.findall(r"^(:\S+) SKIPPED$", output, re.MULTILINE))
    forbidden = tuple(sorted(task for task in tasks if forbidden_task(task)))
    return Graph(tuple(sorted(tasks)), tuple(sorted(SOURCE_REQUIRED - tasks)), forbidden)


def forbidden_task(task: str) -> bool:
    name = task.rsplit(":", 1)[-1]
    return name in FORBIDDEN or (name.lower().startswith("native") and "compile" in name.lower())


def source_identity(root: Path) -> tuple[str, str]:
    revision, tree = subprocess.check_output(["git", "rev-parse", "HEAD", "HEAD^{tree}"], cwd=root, text=True).splitlines()
    subprocess.run([
        "bash", ".github/scripts/release/admit-source.sh", "--repository-root", str(root),
        "--expected-source-revision", revision,
    ], cwd=root, check=True, stdout=subprocess.DEVNULL)
    return revision, tree


def run(root: Path, destination: Path) -> Evidence:
    started = time.monotonic()
    revision, tree = source_identity(root)
    destination.mkdir(parents=True, exist_ok=True)
    planned = subprocess.run([*COMMAND, "--dry-run"], cwd=root, text=True, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, check=False)
    (destination / "task-graph.log").write_text(planned.stdout)
    graph = inspect_graph(planned.stdout)
    code = planned.returncode
    outcome = Outcome.GRAPH_REJECTED
    invocations = 0
    if code == 0 and graph.accepted:
        with (destination / "gradle.log").open("w") as log:
            process = subprocess.Popen(COMMAND, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            if process.stdout is None:
                raise RuntimeError("Source process has no output pipe")
            for line in process.stdout:
                log.write(line)
                print(line, end="", flush=True)
                invocations += int(line.startswith("Dependency fixture compiler invocation:"))
            code = process.wait()
        outcome = Outcome.SOURCE_VALIDATED if code == 0 else Outcome.PROCESS_FAILED
        try:
            if source_identity(root) != (revision, tree):
                outcome, code = Outcome.SOURCE_CHANGED, 1
        except (OSError, subprocess.CalledProcessError, ValueError):
            outcome, code = Outcome.SOURCE_CHANGED, 1
    else:
        code = code or 1
    evidence = Evidence(1, outcome, revision, tree, VERSION, round((time.monotonic() - started) * 1000), code, graph, invocations)
    (destination / "source-execution.json").write_text(json.dumps(asdict(evidence), indent=2) + "\n")
    return evidence


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-directory", type=Path, default=ROOT / "build/reports/ci/source")
    args = parser.parse_args()
    try:
        evidence = run(ROOT, args.report_directory)
    except (OSError, subprocess.CalledProcessError, ValueError) as failure:
        raise SystemExit(f"source-validation: {failure}") from None
    print(f"source-validation: {evidence.outcome.value}", flush=True)
    raise SystemExit(evidence.exitCode)


if __name__ == "__main__":
    main()
