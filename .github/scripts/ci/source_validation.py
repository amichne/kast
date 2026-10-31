#!/usr/bin/env python3
"""Run the complete source gate with stable resources and no candidate authority."""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import subprocess
import time
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path

import verification_plan
from validation_scope import PRODUCT_MODULES, ROOT, select

VERSION = "0.0.0"
COMMAND = (
    "./gradlew",
    "--max-workers=2",
    "-Dorg.gradle.jvmargs=-Xmx5g",
    f"-Pversion={VERSION}",
    f"-PcontrolVersion={VERSION}",
    f"-PhostedPluginVersion={VERSION}",
    "sourceValidationGate",
    "--console=plain",
    "--profile",
    "--info",
)
PREFLIGHT = (
    "python3",
    ".github/scripts/ci/verify-checks.py",
    "--preflight-only",
    "--gate-graph",
    "skip",
)
SOURCE_REQUIRED = frozenset(
    {
        ":sourceValidationGate",
        ":check",
        ":build-logic:check",
        ":verifyKastArchitecture",
        ":verifyJsonContracts",
        ":verifyVerificationGraph",
        ":verifyConfigurationIngress",
        ":verifyKnowledgeBase",
        ":hostObservationTest",
        ":componentReleaseTest",
        ":managedInstallationTest",
        ":developerPublicationTest",
        ":verifyDocumentationGlossary",
        ":verifyDocumentationMarkdown",
        ":documentationToolingTest",
        ":verifyKotlinSemanticInputs",
        ":verifyKotlinSemanticCoverage",
        ":verifyKotlinSemanticInventory",
        ":relation:intellij:semanticCoverageFixtureTest",
        ":runtime:hosted:semanticCoverageFixtureTest",
        ":symbol:intellij:semanticCoverageFixtureTest",
        ":relation:intellij:dependencyFixtureTest",
        ":runtime:hosted:dependencyFixtureTest",
        ":cli:udsTest",
        ":cli:queryDeliveryContractTest",
        ":cli:queryDeliverySchemaTest",
        ":cli:verifyMintlifyCallableReference",
        ":cli:queryPresentationTest",
        ":app-server:verifyPublicQueryGeneration",
        ":productGateVersionTest",
        ":installerFixtureTest",
        ":installationLifecycleTest",
        ":priorInstallationCleanupTest",
        ":installationRecoveryTest",
        ":installationSystemPythonTest",
        ":localInstallationTest",
        ":installerRemovalTest",
        ":testCheckoutInstaller",
        ":installerEntrypointTest",
    }
) | frozenset(
    f":{module.replace('/', ':')}:{task}"
    for module in PRODUCT_MODULES
    for task in ("scopedCheck", "check", "test")
)
FORBIDDEN = frozenset(
    {
        "stageKastControlProduct",
        "installedProductTest",
        "assembleRelease",
        "assembleKastControlDist",
        "verifyReleaseRuntimeAdmission",
        "hostedPlugin",
        "generateHostReleaseRecord",
    }
)


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
    selection: verification_plan.Plan | None
    skippedTasks: tuple[str, ...]


def skipped_semantic_tasks(root: Path) -> tuple[str, ...]:
    value = os.environ.get("KAST_SEMANTIC_FIXTURES", "false")
    if value == "true":
        return ()
    if value != "false":
        raise ValueError("KAST_SEMANTIC_FIXTURES must be true or false")
    tasks = tuple((root / "config/ci/semantic-fixture-tasks.txt").read_text().splitlines())
    if not tasks or len(set(tasks)) != len(tasks) or not all(
        re.fullmatch(r":[A-Za-z][A-Za-z0-9:]*", task) for task in tasks
    ):
        raise ValueError("Invalid semantic fixture task inventory")
    return tasks


def inspect_graph(
    output: str, required: frozenset[str] = SOURCE_REQUIRED, skipped: tuple[str, ...] = ()
) -> Graph:
    tasks = frozenset(re.findall(r"^(:\S+) SKIPPED$", output, re.MULTILINE))
    forbidden = tuple(sorted(task for task in tasks if forbidden_task(task) or task in skipped))
    return Graph(tuple(sorted(tasks)), tuple(sorted(required - tasks)), forbidden)


def obligations(plan: verification_plan.Plan) -> frozenset[str]:
    owners = tuple(owner + ":" for owner in plan.consumers)
    required = set(plan.tasks) | {task for task in SOURCE_REQUIRED if task.startswith(owners)}
    return frozenset(required)


def selected_command(
    root: Path, destination: Path, plan: verification_plan.Plan
) -> tuple[str, ...]:
    properties: tuple[str, ...] = ()
    if plan.caseIds:
        manifest = root / verification_plan.MANIFEST
        if verification_plan.digest(manifest.read_bytes()) != plan.manifestDigest:
            raise ValueError(verification_plan.Failure.IDENTITY_MISMATCH.value)
        with manifest.open() as source:
            reader = csv.DictReader(source, delimiter="\t")
            fields = reader.fieldnames
            rows = [row for row in reader if row["case_id"] in plan.caseIds]
        if (
            fields is None
            or tuple(sorted(row["case_id"] for row in rows)) != plan.caseIds
            or tuple(sorted(row["test_result_id"] for row in rows)) != plan.testResultIds
        ):
            raise ValueError(verification_plan.Failure.INVALID_MANIFEST.value)
        selected = destination / "selected-cases.tsv"
        with selected.open("w") as output:
            writer = csv.DictWriter(output, fields, delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
        owners = sorted({row["verification_owner"] for row in rows})
        properties = (
            f"-PkastSemanticSelection={selected.resolve()}",
            f"-PkastSemanticOwners={','.join(owners)}",
        )
    return (*COMMAND[:6], *properties, *plan.tasks, *COMMAND[7:])


def forbidden_task(task: str) -> bool:
    name = task.rsplit(":", 1)[-1]
    return name in FORBIDDEN or (name.lower().startswith("native") and "compile" in name.lower())


def source_identity(root: Path) -> tuple[str, str]:
    revision, tree = subprocess.check_output(
        ["git", "rev-parse", "HEAD", "HEAD^{tree}"], cwd=root, text=True
    ).splitlines()
    subprocess.run(
        [
            "bash",
            ".github/scripts/release/admit-source.sh",
            "--repository-root",
            str(root),
            "--expected-source-revision",
            revision,
        ],
        cwd=root,
        check=True,
        stdout=subprocess.DEVNULL,
    )
    return revision, tree


def run(
    root: Path,
    destination: Path,
    plan: verification_plan.Plan | None = None,
    gradle_arguments: tuple[str, ...] = (),
) -> Evidence:
    started = time.monotonic()
    revision, tree = source_identity(root)
    if plan is not None and (plan.sourceRevision, plan.sourceTree) != (revision, tree):
        raise ValueError(verification_plan.Failure.IDENTITY_MISMATCH.value)
    destination.mkdir(parents=True, exist_ok=True)
    command = (
        *(COMMAND if plan is None else selected_command(root, destination, plan)),
        *gradle_arguments,
    )
    skipped = skipped_semantic_tasks(root)
    required = (SOURCE_REQUIRED if plan is None else obligations(plan)) - frozenset(skipped)
    for log_name in ("task-graph.log", "gradle.log"):
        (destination / log_name).unlink(missing_ok=True)
    environment = os.environ.copy()
    environment["KAST_RELEASE_JDK_25"] = (
        environment.get("KAST_RELEASE_JDK_25") or environment["JAVA_HOME"]
    )
    preflight = subprocess.run(
        PREFLIGHT,
        cwd=root,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    (destination / "preflight.log").write_text(preflight.stdout)
    graph = Graph((), tuple(sorted(required)), ())
    code = preflight.returncode
    outcome = Outcome.PROCESS_FAILED
    invocations = 0
    if code == 0:
        planned = subprocess.run(
            [*command, "--dry-run"],
            cwd=root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        (destination / "task-graph.log").write_text(planned.stdout)
        graph = inspect_graph(planned.stdout, required, skipped)
        code = planned.returncode
        outcome = Outcome.GRAPH_REJECTED
    if code == 0 and graph.accepted:
        with (destination / "gradle.log").open("w") as log:
            process = subprocess.Popen(
                command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
            )
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
    evidence = Evidence(
        2,
        outcome,
        revision,
        tree,
        VERSION,
        round((time.monotonic() - started) * 1000),
        code,
        graph,
        invocations,
        plan,
        skipped,
    )
    (destination / "source-execution.json").write_text(
        json.dumps(asdict(evidence), indent=2) + "\n"
    )
    return evidence


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-directory", type=Path, default=ROOT / "build/reports/ci/source")
    parser.add_argument("--affected", action="store_true")
    parser.add_argument("--base", default="")
    parser.add_argument(
        "--event",
        choices=("pull_request", "push", "merge_group", "workflow_dispatch"),
        default="pull_request",
    )
    args = parser.parse_args()
    try:
        plan = None
        if args.affected:
            revision, _ = source_identity(ROOT)
            selection = select(args.event, args.base, revision, affected=True)
            plan = selection.plan
            supplied = os.environ.get("CI_VERIFICATION_PLAN")
            if supplied:
                document: object = json.loads(
                    supplied, object_pairs_hook=verification_plan.unique_object
                )
                if plan is None or isinstance(
                    verification_plan.admit(document, plan), verification_plan.Failure
                ):
                    raise ValueError(verification_plan.Failure.IDENTITY_MISMATCH.value)
        evidence = run(ROOT, args.report_directory, plan)
    except (OSError, subprocess.CalledProcessError, ValueError, KeyError) as failure:
        raise SystemExit(f"source-validation: {failure}") from None
    print(f"source-validation: {evidence.outcome.value}", flush=True)
    raise SystemExit(evidence.exitCode)


if __name__ == "__main__":
    main()
