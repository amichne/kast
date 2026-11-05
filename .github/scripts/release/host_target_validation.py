"""Run target builds serially and retain nonempty SDK test evidence before staging."""

from __future__ import annotations

import json
import re
import xml.etree.ElementTree as XML
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Protocol

import dual_candidate as candidate
import host_evidence as evidence
import host_staging
import host_targets as hosts

MODULES = (
    "workspace:intellij-read",
    "symbol:intellij",
    "source:intellij",
    "relation:intellij",
    "diagnostic:intellij",
    "topology:intellij",
    "change:intellij",
    "runtime:hosted",
)
NATIVE_MODULES = ("symbol:intellij", "relation:intellij", "runtime:hosted")
DEPENDENCY_MODULES = ("relation:intellij", "runtime:hosted")


class Failure(StrEnum):
    PROFILE_INVALID = "PROFILE_INVALID"
    MISSING_TEST_RESULTS = "MISSING_TEST_RESULTS"
    INVALID_TEST_RESULTS = "INVALID_TEST_RESULTS"
    FAILED_OR_SKIPPED_TESTS = "FAILED_OR_SKIPPED_TESTS"
    TARGET_ARTIFACT_INVALID = "TARGET_ARTIFACT_INVALID"
    REQUIRED_TASK_NOT_EXECUTED = "REQUIRED_TASK_NOT_EXECUTED"


class RunGradle(Protocol):
    def __call__(self, *arguments: str) -> Path: ...


@dataclass(frozen=True, slots=True)
class TestReport:
    path: str
    sha256: str
    tests: int
    failures: int
    errors: int
    skipped: int


@dataclass(frozen=True, slots=True)
class TargetQualification:
    schemaVersion: int
    target: hosts.SdkTarget
    source: evidence.BuildIdentity
    tasks: tuple[str, ...]
    reports: tuple[TestReport, ...]
    executionLogSha256: str


def task_arguments(target: hosts.SdkTarget, version: str) -> tuple[str, ...]:
    return (
        f"-PintellijTarget={target.intellijReleaseLine}",
        f"-PhostedPluginVersion={version}",
        *(f":{module}:scopedCheck" for module in MODULES),
        *(f":{module}:nativeFixtureTest" for module in NATIVE_MODULES),
        ":runtime:hosted:hostedPlugin",
        "generateHostReleaseRecord",
        "generateHostTargetReceipt",
    )


def inspect_reports(root: Path, line: str) -> tuple[TestReport, ...] | Failure:
    groups = (
        *((module, "test") for module in MODULES),
        *((module, "dependencyFixtureTest") for module in DEPENDENCY_MODULES),
        *((module, "nativeFixtureTest") for module in NATIVE_MODULES),
    )
    reports: list[TestReport] = []
    for module, task in groups:
        directory = (
            root / module.replace(":", "/") / "build/ide-targets" / line / "test-results" / task
        )
        files = sorted(directory.glob("TEST-*.xml"))
        if not files:
            return Failure.MISSING_TEST_RESULTS
        count = 0
        for path in files:
            try:
                if path.is_symlink() or path.stat().st_size > 16 * 1024 * 1024:
                    return Failure.INVALID_TEST_RESULTS
                suite = XML.parse(path).getroot()
                if suite.tag != "testsuite":
                    return Failure.INVALID_TEST_RESULTS
                cases = suite.findall("testcase")
                totals = tuple(
                    int(suite.attrib[name]) for name in ("tests", "failures", "errors", "skipped")
                )
                observed = (
                    len(cases),
                    sum(len(case.findall("failure")) for case in cases),
                    sum(len(case.findall("error")) for case in cases),
                    sum(len(case.findall("skipped")) for case in cases),
                )
                if totals != observed or any(total < 0 for total in totals):
                    return Failure.INVALID_TEST_RESULTS
                reports.append(
                    TestReport(
                        path.relative_to(root).as_posix(),
                        "sha256:" + candidate.digest(path),
                        *totals,
                    )
                )
                count += totals[0]
            except (OSError, ValueError, KeyError, XML.ParseError):
                return Failure.INVALID_TEST_RESULTS
        if count == 0:
            return Failure.MISSING_TEST_RESULTS
    return tuple(reports)


def required_tasks() -> frozenset[str]:
    return frozenset(
        (
            *(f":{module}:test" for module in MODULES),
            *(f":{module}:dependencyFixtureTest" for module in DEPENDENCY_MODULES),
            *(f":{module}:nativeFixtureTest" for module in NATIVE_MODULES),
        )
    )


def executed_tasks(log: Path) -> bool:
    try:
        observed = dict(
            re.findall(
                r"^> Task (:\S+?)(?: (UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE|FAILED))?\s*$",
                log.read_text(errors="replace"),
                re.MULTILINE,
            )
        )
    except OSError:
        return False
    return all(
        task in observed and observed[task] in ("", "UP-TO-DATE", "FROM-CACHE")
        for task in required_tasks()
    )


def qualify(
    root: Path,
    destination: Path,
    identity: evidence.BuildIdentity,
    run_gradle: RunGradle,
) -> tuple[TargetQualification, ...] | Failure:
    profile = hosts.decode_profile(candidate.read_json(root / "config/intellij-targets.json"))
    if isinstance(profile, hosts.Failure):
        return Failure.PROFILE_INVALID
    # These summaries belong to this driver. Preserve cacheable XML, but never upload
    # a previous target's success after an earlier target rejects the new attempt.
    for target in profile.targets:
        output = root / "build/reports/host-targets" / target.intellijReleaseLine
        if output.is_symlink():
            return Failure.INVALID_TEST_RESULTS
        output.mkdir(parents=True, exist_ok=True)
        for name in ("qualification.json", "target-receipt.json"):
            (output / name).unlink(missing_ok=True)
    qualifications: list[TargetQualification] = []
    for target in profile.targets:
        arguments = task_arguments(target, identity.version)
        print(f"host-target: {target.intellijReleaseLine} BUILD_AND_TEST_STARTED", flush=True)
        log = run_gradle(*arguments)
        if not executed_tasks(log):
            return Failure.REQUIRED_TASK_NOT_EXECUTED
        reports = inspect_reports(root, target.intellijReleaseLine)
        if isinstance(reports, Failure):
            return reports
        qualification = TargetQualification(
            1, target, identity, arguments, reports, "sha256:" + candidate.digest(log)
        )
        output = root / "build/reports/host-targets" / target.intellijReleaseLine
        output.mkdir(parents=True, exist_ok=True)
        _ = (output / "qualification.json").write_text(
            json.dumps(asdict(qualification), indent=2) + "\n"
        )
        if any(report.failures or report.errors or report.skipped for report in reports):
            return Failure.FAILED_OR_SKIPPED_TESTS
        qualifications.append(qualification)
        print(
            f"host-target: {target.intellijReleaseLine} TEST_RESULTS_ADMITTED "
            f"reports={len(reports)}",
            flush=True,
        )
    staged = host_staging.stage(root, destination, identity)
    if isinstance(staged, candidate.Failure):
        print(f"host-target: staging rejected: {staged.value}", flush=True)
        return Failure.TARGET_ARTIFACT_INVALID
    return tuple(qualifications)
