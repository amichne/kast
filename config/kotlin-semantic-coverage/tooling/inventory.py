#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = ["pydantic==2.12.3", "pytest==8.4.2", "defusedxml==0.7.1"]
# [tool.uv]
# no-build = true
# required-version = ">=0.12.9"
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Audit pinned FIR identities and declared executable populations independently of Kast."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import re
import subprocess
import sys
import tempfile
import tomllib
import xml.etree.ElementTree as ET
from collections import Counter
from enum import StrEnum
from pathlib import Path
from typing import Annotated, Literal, Protocol
from zipfile import BadZipFile, ZipFile

from defusedxml import ElementTree as SafeET
from defusedxml.common import DefusedXmlException
from pydantic import BaseModel, ConfigDict, Field, StringConstraints, ValidationError

Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
Commit = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{40}$")]
Nonempty = Annotated[str, StringConstraints(min_length=1)]
ElementId = Annotated[str, StringConstraints(pattern=r"^Fir[A-Za-z0-9]+$")]


class Record(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class Code(StrEnum):
    INVALID_INPUT = "INVALID_INPUT"
    UNREADABLE = "UNREADABLE"
    SOURCE_CHANGED = "SOURCE_CHANGED"
    EXTRACTION_MISMATCH = "EXTRACTION_MISMATCH"
    INVENTORY_MISMATCH = "INVENTORY_MISMATCH"
    MISSING_CLASSIFICATION = "MISSING_CLASSIFICATION"
    EXTRA_CLASSIFICATION = "EXTRA_CLASSIFICATION"
    DUPLICATE_CLASSIFICATION = "DUPLICATE_CLASSIFICATION"
    MISSING_CASE_EVIDENCE = "MISSING_CASE_EVIDENCE"
    MISSING_FIXTURE = "MISSING_FIXTURE"
    DUPLICATE_FIXTURE = "DUPLICATE_FIXTURE"
    STALE_UPSTREAM = "STALE_UPSTREAM"
    COMPILER_PIN_DRIFT = "COMPILER_PIN_DRIFT"
    CORRESPONDENCE_UNPROVED = "CORRESPONDENCE_UNPROVED"
    EMPTY_REQUIRED_CASES = "EMPTY_REQUIRED_CASES"
    ZERO_RESULTS = "ZERO_RESULTS"
    DUPLICATE_CASE = "DUPLICATE_CASE"
    DUPLICATE_RESULT = "DUPLICATE_RESULT"
    MISSING_RESULT = "MISSING_RESULT"
    UNEXPECTED_RESULT = "UNEXPECTED_RESULT"
    NONPASS_RESULT = "NONPASS_RESULT"
    SUITE_ERROR = "SUITE_ERROR"
    DISTRIBUTION_CHANGED = "DISTRIBUTION_CHANGED"
    LIVE_DISTRIBUTION_REQUIRED = "LIVE_DISTRIBUTION_REQUIRED"
    BINARY_READ_FAILED = "BINARY_READ_FAILED"
    JAVAP_FAILED = "JAVAP_FAILED"
    BINARY_POPULATION_MISMATCH = "BINARY_POPULATION_MISMATCH"
    RESOURCE_MISSING = "RESOURCE_MISSING"
    RESOURCE_CHANGED = "RESOURCE_CHANGED"
    RESOURCE_DUPLICATE = "RESOURCE_DUPLICATE"
    RESOURCE_UNEXPECTED = "RESOURCE_UNEXPECTED"
    RESOURCE_IDENTITY_MISMATCH = "RESOURCE_IDENTITY_MISMATCH"
    CARRIER_MISSING = "CARRIER_MISSING"
    CARRIER_CHANGED = "CARRIER_CHANGED"
    CARRIER_DUPLICATE = "CARRIER_DUPLICATE"
    SOURCE_PROVENANCE_REJECTED = "SOURCE_PROVENANCE_REJECTED"
    SOURCE_POPULATION_MISMATCH = "SOURCE_POPULATION_MISMATCH"
    SOURCE_BINARY_MISMATCH = "SOURCE_BINARY_MISMATCH"


class Issue(Record):
    code: Code
    detail: Nonempty


class Accepted[T](Record):
    value: T


class Rejected(Record):
    issues: Annotated[tuple[Issue, ...], Field(min_length=1)]


class InventoryGate(StrEnum):
    ACCOUNTING = "ACCOUNTING"
    LIVE_ELEMENTS = "LIVE_ELEMENTS"
    EXACT_SOURCE = "EXACT_SOURCE"


class PublishedFirPin(Record):
    version: Nonempty
    binary_sha256: Sha256
    sources_sha256: Sha256
    pom_sha256: Sha256
    sbom_sha256: Sha256
    source_map_sha256: Sha256
    class_count: Annotated[int, Field(gt=0)]
    source_count: Annotated[int, Field(gt=0)]


class Pin(Record):
    schema_version: Literal[1]
    repository: Literal["https://github.com/JetBrains/kotlin"]
    upstream_tag: Nonempty
    upstream_commit: Commit
    research_commit: Commit
    source_path: Nonempty
    source_sha256: Sha256
    visitor_sha256: Sha256
    fixture_index_sha256: Sha256
    selected_fixture_manifest_sha256: Sha256
    binary_population_sha256: Sha256
    platform_build: Nonempty
    kotlin_gradle_version: Nonempty
    plugin_version: Nonempty
    plugin_sha256: Sha256
    adapter_compiler_version: Nonempty
    compiler_common_sha256: Sha256
    standalone_compiler_version: Nonempty
    analysis_api_invocation_kinds: Annotated[
        tuple[
            Literal[
                "ZERO", "AT_MOST_ONCE", "EXACTLY_ONCE", "AT_LEAST_ONCE", "MORE_THAN_ONCE", "UNKNOWN"
            ],
            ...,
        ],
        Field(min_length=1),
    ]
    correspondence: Literal["ELEMENT_POPULATION_ONLY", "EXACT_SOURCE"]
    correspondence_limit: Nonempty
    selected_fixture_sources: Annotated[tuple[Nonempty, ...], Field(min_length=1)]
    published_fir: PublishedFirPin | None = None


class Category(StrEnum):
    OTHER = "Other"
    DECLARATION = "Declaration"
    EXPRESSION = "Expression"
    DIAGNOSTICS = "Diagnostics"
    REFERENCE = "Reference"
    TYPE_REF = "TypeRefElement"
    CONTRACTS = "Contracts"


class UnobservedDistribution(Record):
    type: Literal["UNOBSERVED"] = "UNOBSERVED"


class UnprovedSourceCorrespondence(Record):
    type: Literal["UNPROVED"] = "UNPROVED"


class _MatchedFirSources(Record):
    type: Literal["PUBLISHED_FIR_SOURCES"] = "PUBLISHED_FIR_SOURCES"
    version: Nonempty
    binary_sha256: Sha256
    sources_sha256: Sha256
    upstream_commit: Commit
    source_map_sha256: Sha256
    pom_sha256: Sha256
    sbom_sha256: Sha256
    class_count: Annotated[int, Field(gt=0)]
    source_count: Annotated[int, Field(gt=0)]


type SourceCorrespondence = UnprovedSourceCorrespondence | _MatchedFirSources
_UNPROVED_SOURCE_CORRESPONDENCE = UnprovedSourceCorrespondence()


class DistributionObservation(Record):
    type: Literal["LIVE_ELEMENT_IDENTITIES"] = "LIVE_ELEMENT_IDENTITIES"
    plugin_sha256: Sha256
    compiler_common_sha256: Sha256
    plugin_version: Nonempty
    adapter_compiler_version: Nonempty
    elements: Annotated[tuple[ElementId, ...], Field(min_length=1)]
    source_correspondence: SourceCorrespondence = _UNPROVED_SOURCE_CORRESPONDENCE


type DistributionEvidence = UnobservedDistribution | DistributionObservation
_UNOBSERVED_DISTRIBUTION = UnobservedDistribution()


class Element(Record):
    element_id: ElementId
    category: Category
    property_name: Nonempty
    source_line: Annotated[int, Field(gt=0)]


class CatalogueVersions(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)
    kotlin: Nonempty
    idea_build: Nonempty = Field(alias="idea-platform-build")


class Catalogue(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)
    versions: CatalogueVersions


class Disposition(StrEnum):
    IMPLEMENTED = "implemented"
    TRAVERSAL_ONLY = "traversal-only"
    UNSUPPORTED = "unsupported"
    IRRELEVANT = "irrelevant"


class Classification(Record):
    element_id: ElementId
    disposition: Disposition
    reason: Nonempty
    evidence: Nonempty
    case_ids: str


class SourceFile(Record):
    source_path: Nonempty
    git_blob: Commit


class PublishedFirSource(SourceFile):
    archive_entry: Nonempty
    sha256: Sha256


class Fixture(SourceFile):
    local_path: Nonempty
    sha256: Sha256


class RequiredCase(Record):
    case_id: Nonempty
    test_result_id: Nonempty


class ResultStatus(StrEnum):
    PASSED = "PASS"
    FAIL = "FAIL"
    ERROR = "ERROR"
    SKIP = "SKIP"
    NOT_RUN = "NOT_RUN"


class TestResult(Record):
    test_result_id: Nonempty
    status: ResultStatus
    detail: str = ""


class CaseResult(Record):
    case_id: Nonempty
    test_result_id: Nonempty
    status: ResultStatus
    detail: str = ""


class DispositionCount(Record):
    disposition: Disposition
    count: Annotated[int, Field(ge=0)]


class AuditReport(Record):
    status: Literal["PASS", "FAIL"]
    gate: Literal["INVENTORY_ACCOUNTING", "EXECUTION_POPULATION"]
    upstream_commit: str = ""
    element_count: int = 0
    fixture_source_count: int = 0
    fixture_artifact_count: int = 0
    counts: tuple[DispositionCount, ...] = ()
    results: tuple[CaseResult, ...] = ()
    issues: tuple[Issue, ...] = ()
    correspondence: str = ""
    distribution: DistributionEvidence = _UNOBSERVED_DISTRIBUTION


class UnprovedCases(Record):
    kind: Literal["UNPROVED"] = "UNPROVED"


class _PassedCases(Record):
    kind: Literal["PASSED"] = "PASSED"
    case_ids: Annotated[frozenset[Nonempty], Field(min_length=1)]


type CaseEvidence = UnprovedCases | _PassedCases

_UNPROVED_CASES = UnprovedCases()


def case_evidence(report: AuditReport) -> CaseEvidence:
    if (
        report.gate == "EXECUTION_POPULATION"
        and report.status == "PASS"
        and not report.issues
        and report.results
        and all(row.status == ResultStatus.PASSED for row in report.results)
    ):
        return _PassedCases(case_ids=frozenset(row.case_id for row in report.results))
    return UnprovedCases()


def issue(code: Code, detail: str) -> Issue:
    return Issue(code=code, detail=detail)


def read_text(path: Path) -> Accepted[str] | Rejected:
    try:
        return Accepted(value=path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError) as error:
        return Rejected(issues=(issue(Code.UNREADABLE, f"{path}: {type(error).__name__}"),))


def parse_table[T: Record](
    raw: str, model: type[T], *, subset: bool = False
) -> Accepted[tuple[T, ...]] | Rejected:
    reader = csv.DictReader(io.StringIO(raw), delimiter="\t")
    expected = set(model.model_fields)
    actual = set(reader.fieldnames or ())
    if not expected.issubset(actual) or (not subset and expected != actual):
        return Rejected(
            issues=(
                issue(
                    Code.INVALID_INPUT,
                    f"{model.__name__}: columns {sorted(actual)}, expected {sorted(expected)}",
                ),
            )
        )
    rows: list[T] = []
    for line, raw_row in enumerate(reader, start=2):
        try:
            rows.append(model.model_validate({key: raw_row[key] for key in model.model_fields}))
        except ValidationError:
            return Rejected(
                issues=(issue(Code.INVALID_INPUT, f"{model.__name__}: malformed row {line}"),)
            )
    return Accepted(value=tuple(rows))


def table[T: Record](
    path: Path, model: type[T], *, subset: bool = False
) -> Accepted[tuple[T, ...]] | Rejected:
    match read_text(path):
        case Accepted(value=raw):
            return parse_table(raw, model, subset=subset)
        case Rejected() as rejected:
            return rejected


def parse_pin(raw: str) -> Accepted[Pin] | Rejected:
    try:
        return Accepted(value=Pin.model_validate(tomllib.loads(raw)))
    except (tomllib.TOMLDecodeError, ValidationError):
        return Rejected(
            issues=(issue(Code.INVALID_INPUT, "pin.toml: malformed or unknown fields"),)
        )


def extract_elements(source: str) -> Accepted[tuple[Element, ...]] | Rejected:
    """Read the pinned declarative registration format, not arbitrary Kotlin syntax."""
    registration = re.compile(
        r"^[ \t]*(?:override\s+)?val\s+(\w+):\s+Element\s+by\s+(?:element|sealedElement)\(([^)]*)\)",
        re.MULTILINE,
    )
    declarations = re.findall(
        r"^[ \t]*(?:override\s+)?val\s+\w+:\s+Element\s+by\b", source, re.MULTILINE
    )
    matches = tuple(registration.finditer(source))
    if not matches or len(matches) != len(declarations):
        return Rejected(
            issues=(
                issue(Code.EXTRACTION_MISMATCH, "The registration syntax or population changed"),
            )
        )
    rows: list[Element] = []
    for match in matches:
        property_name, arguments = match.groups()
        explicit = re.search(r'name\s*=\s*"([^"]+)"', arguments)
        name = explicit.group(1) if explicit else property_name[0].upper() + property_name[1:]
        try:
            rows.append(
                Element(
                    element_id=f"Fir{name}",
                    category=Category(arguments.split(",")[0].strip()),
                    property_name=property_name,
                    source_line=source[: match.start()].count("\n") + 1,
                )
            )
        except (ValidationError, ValueError):
            return Rejected(
                issues=(issue(Code.EXTRACTION_MISMATCH, f"Unknown registration: {property_name}"),)
            )
    ids = [row.element_id for row in rows]
    if len(ids) != len(set(ids)):
        return Rejected(
            issues=(issue(Code.EXTRACTION_MISMATCH, "Duplicate upstream element identity"),)
        )
    return Accepted(value=tuple(sorted(rows, key=lambda row: row.element_id)))


def visitor_elements(source: str) -> frozenset[str]:
    return frozenset(
        re.findall(r"(?:abstract|open) fun (?:<[^>]+> )?visit\w+\(\w+: (Fir\w+)", source)
    )


def assess_classifications(
    elements: tuple[Element, ...],
    classifications: tuple[Classification, ...],
    evidence: CaseEvidence = _UNPROVED_CASES,
) -> tuple[Issue, ...]:
    expected = {row.element_id for row in elements}
    counts = Counter(row.element_id for row in classifications)
    issues = [
        issue(Code.MISSING_CLASSIFICATION, identity)
        for identity in sorted(expected - counts.keys())
    ]
    issues.extend(
        issue(Code.EXTRA_CLASSIFICATION, identity) for identity in sorted(counts.keys() - expected)
    )
    for identity, count in sorted(counts.items()):
        if count != 1:
            issues.append(issue(Code.DUPLICATE_CLASSIFICATION, f"{identity}: {count}"))
    for row in classifications:
        case_ids = frozenset(value for value in row.case_ids.split("|") if value)
        if row.disposition in (Disposition.IMPLEMENTED, Disposition.TRAVERSAL_ONLY) and (
            not case_ids
            or not isinstance(evidence, _PassedCases)
            or not case_ids.issubset(evidence.case_ids)
        ):
            issues.append(issue(Code.MISSING_CASE_EVIDENCE, row.element_id))
    return tuple(issues)


def assess_fixtures(
    required_sources: tuple[str, ...],
    source_index: tuple[SourceFile, ...],
    selected: tuple[Fixture, ...],
) -> tuple[Issue, ...]:
    index = {row.source_path: row.git_blob for row in source_index}
    artifacts = Counter(row.source_path for row in selected)
    issues = [
        issue(Code.MISSING_FIXTURE, source)
        for source in required_sources
        if source not in index or artifacts[source] != 1
    ]
    for row in selected:
        if artifacts[row.source_path] != 1:
            issues.append(issue(Code.DUPLICATE_FIXTURE, row.source_path))
        if row.source_path.endswith(".kt") and index.get(row.source_path) != row.git_blob:
            issues.append(issue(Code.STALE_UPSTREAM, row.source_path))
    return tuple(issues)


def duplicate_issues(identities: tuple[str, ...], code: Code) -> tuple[Issue, ...]:
    return tuple(
        issue(code, identity) for identity, count in Counter(identities).items() if count != 1
    )


def execution_population_issues(
    required: tuple[RequiredCase, ...], observed: tuple[TestResult, ...]
) -> tuple[Issue, ...]:
    issues: list[Issue] = []
    if not required:
        issues.append(
            issue(Code.EMPTY_REQUIRED_CASES, "The required executable population is empty")
        )
    if not observed:
        issues.append(issue(Code.ZERO_RESULTS, "No executed result identity is present"))
    issues.extend(duplicate_issues(tuple(row.case_id for row in required), Code.DUPLICATE_CASE))
    issues.extend(
        duplicate_issues(tuple(row.test_result_id for row in required), Code.DUPLICATE_CASE)
    )
    issues.extend(
        duplicate_issues(tuple(row.test_result_id for row in observed), Code.DUPLICATE_RESULT)
    )
    expected = frozenset(row.test_result_id for row in required)
    actual = frozenset(row.test_result_id for row in observed)
    issues.extend(issue(Code.MISSING_RESULT, identity) for identity in sorted(expected - actual))
    issues.extend(issue(Code.UNEXPECTED_RESULT, identity) for identity in sorted(actual - expected))
    return tuple(issues)


def assess_execution(
    required: tuple[RequiredCase, ...], observed: tuple[TestResult, ...]
) -> AuditReport:
    issues = list(execution_population_issues(required, observed))
    by_id = {row.test_result_id: row for row in observed}
    results: list[CaseResult] = []
    for case in required:
        result = by_id.get(case.test_result_id)
        status = ResultStatus.NOT_RUN if result is None else result.status
        detail = "No observed result" if result is None else result.detail
        results.append(
            CaseResult(
                case_id=case.case_id,
                test_result_id=case.test_result_id,
                status=status,
                detail=detail,
            )
        )
        if status != ResultStatus.PASSED:
            issues.append(issue(Code.NONPASS_RESULT, f"{case.case_id}: {status}"))
    return AuditReport(
        status="FAIL" if issues else "PASS",
        gate="EXECUTION_POPULATION",
        results=tuple(results),
        issues=tuple(issues),
    )


def parse_testcase(case: ET.Element, origin: str) -> Accepted[TestResult] | Rejected:
    classname, name = case.get("classname"), case.get("name")
    if not classname or not name:
        return Rejected(
            issues=(issue(Code.INVALID_INPUT, f"Missing testcase identity in {origin}"),)
        )
    failure, error, skipped = case.find("failure"), case.find("error"), case.find("skipped")
    if sum(item is not None for item in (failure, error, skipped)) > 1:
        return Rejected(
            issues=(issue(Code.INVALID_INPUT, f"Conflicting testcase outcomes in {origin}"),)
        )
    if failure is not None:
        status, detail = ResultStatus.FAIL, failure.get("message", "Assertion failed")
    elif error is not None:
        status, detail = ResultStatus.ERROR, error.get("message", "Runner or uncaught error")
    elif skipped is not None:
        status, detail = ResultStatus.SKIP, skipped.get("message", "Skipped")
    else:
        status, detail = ResultStatus.PASSED, ""
    return Accepted(
        value=TestResult(test_result_id=f"{classname}#{name}", status=status, detail=detail)
    )


def suite_count_issues(suite: ET.Element, origin: str) -> tuple[Issue, ...]:
    cases = tuple(suite.iter("testcase"))
    counts = (
        ("tests", len(cases)),
        ("failures", sum(case.find("failure") is not None for case in cases)),
        ("errors", sum(case.find("error") is not None for case in cases)),
        ("skipped", sum(case.find("skipped") is not None for case in cases)),
    )
    for name, actual in counts:
        declared = suite.get(name)
        if declared is not None:
            try:
                expected = int(declared)
            except ValueError:
                return (issue(Code.INVALID_INPUT, f"Invalid suite count {name} in {origin}"),)
            if expected != actual:
                return (
                    issue(
                        Code.INVALID_INPUT,
                        f"Suite count {name} differs from actual testcase outcomes in {origin}",
                    ),
                )
    return ()


def junit_suite_issues(root: ET.Element, origin: str) -> tuple[Issue, ...]:
    issues: list[Issue] = []
    for suite in root.iter("testsuite"):
        if suite.find("error") is not None:
            issues.append(issue(Code.SUITE_ERROR, f"Setup or suite error in {origin}"))
        else:
            issues.extend(suite_count_issues(suite, origin))
    return tuple(issues)


def parse_junit(raw: str, origin: str) -> Accepted[tuple[TestResult, ...]] | Rejected:
    if "<!DOCTYPE" in raw or "<!ENTITY" in raw:
        return Rejected(
            issues=(issue(Code.INVALID_INPUT, f"Entity directives are forbidden in {origin}"),)
        )
    try:
        root = ET.fromstring(raw)  # noqa: S314 -- XML entity directives are rejected before this call
    except ET.ParseError:
        return Rejected(issues=(issue(Code.INVALID_INPUT, f"Malformed JUnit report: {origin}"),))
    if root.tag not in {"testsuite", "testsuites"}:
        return Rejected(
            issues=(issue(Code.INVALID_INPUT, f"Unknown JUnit report root: {root.tag}"),)
        )
    summary_issues = junit_suite_issues(root, origin)
    if summary_issues:
        return Rejected(issues=summary_issues)
    results: list[TestResult] = []
    for case in root.iter("testcase"):
        match parse_testcase(case, origin):
            case Rejected() as rejected:
                return rejected
            case Accepted(value=result):
                results.append(result)
    return Accepted(value=tuple(results))


def digest(path: Path) -> Accepted[str] | Rejected:
    try:
        return Accepted(value=hashlib.sha256(path.read_bytes()).hexdigest())
    except OSError as error:
        return Rejected(issues=(issue(Code.UNREADABLE, f"{path}: {type(error).__name__}"),))


def hash_issue(path: Path, expected: str) -> tuple[Issue, ...]:
    match digest(path):
        case Accepted(value=actual):
            return () if actual == expected else (issue(Code.SOURCE_CHANGED, str(path)),)
        case Rejected(issues=issues):
            return issues


def failure_report(
    gate: Literal["INVENTORY_ACCOUNTING", "EXECUTION_POPULATION"], rejected: Rejected
) -> AuditReport:
    return AuditReport(status="FAIL", gate=gate, issues=rejected.issues)


class AuditInputs(Record):
    source: str
    visitor: str
    binary: tuple[str, ...]
    inventory: tuple[Element, ...]
    classifications: tuple[Classification, ...]
    source_index: tuple[SourceFile, ...]
    selected: tuple[Fixture, ...]


def read_pin(root: Path) -> Accepted[Pin] | Rejected:
    match read_text(root / "pin.toml"):
        case Rejected() as rejected:
            return rejected
        case Accepted(value=raw):
            return parse_pin(raw)


def pin_artifact_issues(root: Path, pin: Pin) -> tuple[Issue, ...]:
    issues: list[Issue] = []
    match read_text(root / "upstream/commit.txt"):
        case Rejected(issues=commit_issues):
            issues.extend(commit_issues)
        case Accepted(value=commit_text):
            payload = commit_text.encode("utf-8")
            commit_identity = hashlib.sha1(
                b"commit " + str(len(payload)).encode("ascii") + b"\0" + payload,
                usedforsecurity=False,
            ).hexdigest()
            if commit_identity != pin.upstream_commit:
                issues.append(
                    issue(
                        Code.STALE_UPSTREAM,
                        "The pin differs from the preserved upstream Git commit object",
                    )
                )
    for relative, expected_hash in (
        ("upstream/FirTree.kt", pin.source_sha256),
        ("upstream/FirVisitor.kt", pin.visitor_sha256),
        ("upstream/resolver-source-index.tsv", pin.fixture_index_sha256),
        ("selected-fixtures.tsv", pin.selected_fixture_manifest_sha256),
        ("upstream/binary-fir-elements.txt", pin.binary_population_sha256),
    ):
        issues.extend(hash_issue(root / relative, expected_hash))
    if pin.published_fir is not None:
        for relative, expected_hash in (
            ("upstream/provenance/fir.pom", pin.published_fir.pom_sha256),
            ("upstream/provenance/fir.spdx.json", pin.published_fir.sbom_sha256),
            ("upstream/provenance/fir-source-map.tsv", pin.published_fir.source_map_sha256),
        ):
            issues.extend(hash_issue(root / relative, expected_hash))
    return tuple(issues)


def catalogue_issues(catalogue: Path, pin: Pin) -> tuple[Issue, ...]:
    match read_text(catalogue):
        case Rejected(issues=issues):
            return issues
        case Accepted(value=raw):
            try:
                versions = Catalogue.model_validate(tomllib.loads(raw)).versions
            except (tomllib.TOMLDecodeError, ValidationError):
                return (issue(Code.INVALID_INPUT, str(catalogue)),)
            if (
                versions.kotlin != pin.kotlin_gradle_version
                or versions.idea_build != pin.platform_build
            ):
                return (issue(Code.COMPILER_PIN_DRIFT, str(catalogue)),)
            return ()


def read_audit_inputs(root: Path) -> Accepted[AuditInputs] | Rejected:
    parsed = (
        read_text(root / "upstream/FirTree.kt"),
        read_text(root / "upstream/FirVisitor.kt"),
        read_text(root / "upstream/binary-fir-elements.txt"),
        table(root / "inventory.tsv", Element),
        table(root / "classifications.tsv", Classification),
        table(root / "upstream/resolver-source-index.tsv", SourceFile),
        table(root / "selected-fixtures.tsv", Fixture),
    )
    match parsed:
        case (
            Accepted(value=source),
            Accepted(value=visitor),
            Accepted(value=binary),
            Accepted(value=inventory),
            Accepted(value=classes),
            Accepted(value=index),
            Accepted(value=selected),
        ):
            return Accepted(
                value=AuditInputs(
                    source=source,
                    visitor=visitor,
                    binary=tuple(binary.splitlines()),
                    inventory=inventory,
                    classifications=classes,
                    source_index=index,
                    selected=selected,
                )
            )
        case _:
            return Rejected(
                issues=tuple(
                    item
                    for result in parsed
                    if isinstance(result, Rejected)
                    for item in result.issues
                )
            )


def inventory_content_issues(
    inputs: AuditInputs, elements: tuple[Element, ...]
) -> tuple[Issue, ...]:
    issues: list[Issue] = []
    if elements != inputs.inventory:
        issues.append(
            issue(
                Code.INVENTORY_MISMATCH,
                "Checked-in inventory differs from independent upstream extraction",
            )
        )
    expected = frozenset(row.element_id for row in elements)
    if expected != visitor_elements(inputs.visitor):
        issues.append(
            issue(
                Code.EXTRACTION_MISMATCH,
                "FirTree and upstream generated visitor populations differ",
            )
        )
    if expected != frozenset(inputs.binary):
        issues.append(
            issue(
                Code.EXTRACTION_MISMATCH, "Upstream and observed binary visitor populations differ"
            )
        )
    return tuple(issues)


def selected_file_issues(root: Path, selected: tuple[Fixture, ...]) -> tuple[Issue, ...]:
    issues: list[Issue] = []
    for fixture in selected:
        location = root / fixture.local_path
        if location.resolve().is_relative_to(root.resolve()):
            issues.extend(hash_issue(location, fixture.sha256))
        else:
            issues.append(
                issue(Code.INVALID_INPUT, f"Fixture escapes owned root: {fixture.local_path}")
            )
    return tuple(issues)


class VisitorInspector(Protocol):
    def inspect(self, plugin_snapshot: Path) -> Accepted[str] | Rejected: ...


class JavapInspector(Record):
    executable: Path

    def inspect(self, plugin_snapshot: Path) -> Accepted[str] | Rejected:
        try:
            result = subprocess.run(
                [
                    str(self.executable),
                    "-classpath",
                    str(plugin_snapshot),
                    "org.jetbrains.kotlin.fir.visitors.FirVisitor",
                ],
                capture_output=True,
                text=True,
                check=False,
                timeout=30,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            return Rejected(issues=(issue(Code.JAVAP_FAILED, type(error).__name__),))
        if result.returncode != 0 or len(result.stdout) > 1_000_000:
            return Rejected(issues=(issue(Code.JAVAP_FAILED, f"exit={result.returncode}"),))
        return Accepted(value=result.stdout)


class _PinnedJar(Record):
    payload: bytes
    sha256: Sha256


class PublishedSourcePackage(BaseModel):
    """Read only the producer's source-coordinate projection from extensible SPDX records."""

    model_config = ConfigDict(extra="ignore", frozen=True)
    name: Nonempty
    version: Nonempty = Field(alias="versionInfo")
    supplier: Nonempty
    source: Nonempty = Field(alias="sourceInfo")


class PublishedSourceSbom(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)
    packages: Annotated[tuple[PublishedSourcePackage, ...], Field(min_length=1)]


def producer_coordinate_issues(pom: bytes, sbom: bytes, version: str) -> tuple[Issue, ...]:
    try:
        document = SafeET.fromstring(pom)
        packages = PublishedSourceSbom.model_validate_json(sbom).packages
    except (ET.ParseError, ValidationError, DefusedXmlException):
        return (issue(Code.SOURCE_PROVENANCE_REJECTED, "Malformed producer provenance"),)
    namespace = "{http://maven.apache.org/POM/4.0.0}"
    artifact = "kotlin-compiler-fir-for-ide"
    if tuple(
        document.findtext(namespace + field) for field in ("groupId", "artifactId", "version")
    ) != ("org.jetbrains.kotlin", artifact, version):
        return (
            issue(Code.SOURCE_PROVENANCE_REJECTED, "The POM has a different source coordinate"),
        )
    primary = tuple(package for package in packages if package.name == artifact)
    if (
        len(primary) != 1
        or primary[0].version != version
        or primary[0].supplier != "Organization: JetBrains s.r.o."
        or primary[0].source
        != (
            f"git+https://github.com/JetBrains/kotlin.git@v{version}#{artifact}"
            f"[:prepare:ide-plugin-dependencies:{artifact}]"
        )
    ):
        return (issue(Code.SOURCE_PROVENANCE_REJECTED, "The SPDX source coordinate is unproved"),)
    return ()


def published_class_issues(
    bundled: ZipFile, published: ZipFile, pin: PublishedFirPin
) -> tuple[Issue, ...]:
    classes = tuple(name for name in published.namelist() if name.endswith(".class"))
    if len(classes) != pin.class_count:
        return (issue(Code.SOURCE_POPULATION_MISMATCH, "Published class population differs"),)
    bundled_entries = set(bundled.namelist())
    for name in classes:
        if name not in bundled_entries or published.read(name) != bundled.read(name):
            return (issue(Code.SOURCE_BINARY_MISMATCH, name),)
    return ()


def published_source_issues(
    authored: ZipFile, mappings: tuple[PublishedFirSource, ...], pin: PublishedFirPin
) -> tuple[Issue, ...]:
    actual_sources = {name for name in authored.namelist() if name.endswith(".kt")}
    required_sources = tuple(row.archive_entry for row in mappings)
    if (
        len(mappings) != pin.source_count
        or len(required_sources) != len(set(required_sources))
        or len(mappings) != len({row.source_path for row in mappings})
        or actual_sources != set(required_sources)
    ):
        return (issue(Code.SOURCE_POPULATION_MISMATCH, "Published source population differs"),)
    for row in mappings:
        if not row.source_path.startswith("compiler/fir/") or ".." in row.source_path.split("/"):
            return (issue(Code.SOURCE_PROVENANCE_REJECTED, row.source_path),)
        payload = authored.read(row.archive_entry)
        blob = hashlib.sha1(
            b"blob " + str(len(payload)).encode("ascii") + b"\0" + payload,
            usedforsecurity=False,
        ).hexdigest()
        if hashlib.sha256(payload).hexdigest() != row.sha256 or blob != row.git_blob:
            return (issue(Code.SOURCE_CHANGED, row.archive_entry),)
    return ()


def fir_archive_issues(
    plugin: bytes, binary: bytes, sources: bytes, mappings: tuple[PublishedFirSource, ...], pin: Pin
) -> tuple[Issue, ...]:
    source_pin = pin.published_fir
    if source_pin is None:
        return (issue(Code.CORRESPONDENCE_UNPROVED, "No published FIR source pin"),)
    try:
        with (
            ZipFile(io.BytesIO(plugin)) as bundled,
            ZipFile(io.BytesIO(binary)) as published,
            ZipFile(io.BytesIO(sources)) as authored,
        ):
            for archive in (bundled, published, authored):
                if len(archive.namelist()) != len(set(archive.namelist())):
                    return (issue(Code.SOURCE_POPULATION_MISMATCH, "Duplicate archive entries"),)
            issues = paired_fir_archive_issues(bundled, published, authored, mappings, source_pin)
            if issues:
                return issues
            visitor = authored.read("org/jetbrains/kotlin/fir/visitors/FirVisitor.kt")
            if hashlib.sha256(visitor).hexdigest() != pin.visitor_sha256:
                return (
                    issue(Code.SOURCE_CHANGED, "The published visitor differs from the inventory"),
                )
    except (BadZipFile, KeyError, OSError) as error:
        return (issue(Code.SOURCE_PROVENANCE_REJECTED, type(error).__name__),)
    return ()


def paired_fir_archive_issues(
    bundled: ZipFile,
    published: ZipFile,
    authored: ZipFile,
    mappings: tuple[PublishedFirSource, ...],
    pin: PublishedFirPin,
) -> tuple[Issue, ...]:
    issues = published_class_issues(bundled, published, pin)
    return issues if issues else published_source_issues(authored, mappings, pin)


def parse_source_map(payload: bytes) -> Accepted[tuple[PublishedFirSource, ...]] | Rejected:
    try:
        text = payload.decode("utf-8")
    except UnicodeDecodeError:
        return Rejected(issues=(issue(Code.INVALID_INPUT, "Source map is not UTF-8"),))
    return parse_table(text, PublishedFirSource)


def admit_published_fir_sources(
    root: Path,
    pin: Pin,
    plugin: bytes,
    binary_path: Path,
    sources_path: Path,
) -> Accepted[_MatchedFirSources] | Rejected:
    source_pin = pin.published_fir
    if source_pin is None or source_pin.version != pin.adapter_compiler_version:
        return Rejected(
            issues=(issue(Code.CORRESPONDENCE_UNPROVED, "No matching published FIR pin"),)
        )
    captures = (
        capture_jar(binary_path, source_pin.binary_sha256),
        capture_jar(sources_path, source_pin.sources_sha256),
        capture_jar(root / "upstream/provenance/fir.pom", source_pin.pom_sha256),
        capture_jar(root / "upstream/provenance/fir.spdx.json", source_pin.sbom_sha256),
        capture_jar(root / "upstream/provenance/fir-source-map.tsv", source_pin.source_map_sha256),
    )
    match captures:
        case (
            Accepted(value=binary),
            Accepted(value=sources),
            Accepted(value=pom),
            Accepted(value=sbom),
            Accepted(value=source_map),
        ):
            pass
        case _:
            return Rejected(
                issues=tuple(
                    item
                    for result in captures
                    if isinstance(result, Rejected)
                    for item in result.issues
                )
            )
    provenance_issues = producer_coordinate_issues(pom.payload, sbom.payload, source_pin.version)
    if provenance_issues:
        return Rejected(issues=provenance_issues)
    match parse_source_map(source_map.payload):
        case Rejected() as rejected:
            return rejected
        case Accepted(value=mappings):
            pass
    archive_issues = fir_archive_issues(plugin, binary.payload, sources.payload, mappings, pin)
    if archive_issues:
        return Rejected(issues=archive_issues)
    return Accepted(
        value=_MatchedFirSources(
            version=source_pin.version,
            binary_sha256=binary.sha256,
            sources_sha256=sources.sha256,
            upstream_commit=pin.upstream_commit,
            source_map_sha256=source_map.sha256,
            pom_sha256=pom.sha256,
            sbom_sha256=sbom.sha256,
            class_count=source_pin.class_count,
            source_count=source_pin.source_count,
        )
    )


def capture_jar(path: Path, expected_sha256: str) -> Accepted[_PinnedJar] | Rejected:
    try:
        payload = path.read_bytes()
    except OSError as error:
        return Rejected(
            issues=(issue(Code.BINARY_READ_FAILED, f"{path.name}: {type(error).__name__}"),)
        )
    actual = hashlib.sha256(payload).hexdigest()
    if actual != expected_sha256:
        return Rejected(
            issues=(
                issue(
                    Code.DISTRIBUTION_CHANGED,
                    f"{path.name}: expected={expected_sha256} actual={actual}",
                ),
            )
        )
    return Accepted(value=_PinnedJar(payload=payload, sha256=actual))


def parse_binary_visitor(output: str) -> Accepted[tuple[str, ...]] | Rejected:
    population = tuple(re.findall(r"\bvisit\w+\(org\.jetbrains\.kotlin\.[\w.]+\.(Fir\w+)", output))
    method_count = len(re.findall(r"\bvisit\w+\(", output))
    if not population or len(population) != method_count or len(population) != len(set(population)):
        return Rejected(
            issues=(
                issue(
                    Code.BINARY_POPULATION_MISMATCH,
                    "Missing, duplicate, or unrecognized binary visitor methods",
                ),
            )
        )
    return Accepted(value=tuple(sorted(population)))


def jar_metadata(plugin: _PinnedJar, compiler_common: _PinnedJar, pin: Pin) -> tuple[Issue, ...]:
    try:
        with ZipFile(io.BytesIO(plugin.payload)) as jar:
            plugin_xml = jar.read("META-INF/plugin.xml").decode("utf-8")
            _ = jar.getinfo("org/jetbrains/kotlin/analysis/api/KaSession.class")
            _ = jar.getinfo("org/jetbrains/kotlin/fir/visitors/FirVisitor.class")
        with ZipFile(io.BytesIO(compiler_common.payload)) as jar:
            compiler_version = jar.read("META-INF/compiler.version").decode("utf-8").strip()
    except (BadZipFile, KeyError, UnicodeDecodeError) as error:
        return (issue(Code.BINARY_READ_FAILED, type(error).__name__),)
    match = re.search(r"<version>([^<]+)</version>", plugin_xml)
    if (
        match is None
        or match.group(1) != pin.plugin_version
        or compiler_version != pin.adapter_compiler_version
    ):
        return (
            issue(
                Code.DISTRIBUTION_CHANGED, "Plugin or adapter compiler version differs from the pin"
            ),
        )
    return ()


def observe_distribution(
    pin: Pin,
    plugin_path: Path,
    compiler_common_path: Path,
    inspector: VisitorInspector,
    *,
    source_artifacts: tuple[Path, Path, Path] | None = None,
) -> Accepted[DistributionObservation] | Rejected:
    captured = (
        capture_jar(plugin_path, pin.plugin_sha256),
        capture_jar(compiler_common_path, pin.compiler_common_sha256),
    )
    match captured:
        case (Accepted(value=plugin), Accepted(value=compiler_common)):
            pass
        case _:
            return Rejected(
                issues=tuple(
                    item
                    for result in captured
                    if isinstance(result, Rejected)
                    for item in result.issues
                )
            )
    metadata_issues = jar_metadata(plugin, compiler_common, pin)
    if metadata_issues:
        return Rejected(issues=metadata_issues)
    try:
        with tempfile.TemporaryDirectory(prefix="kast-fir-inventory-") as owned:
            snapshot = Path(owned) / "kotlin-plugin.jar"
            _ = snapshot.write_bytes(plugin.payload)
            inspected = inspector.inspect(snapshot)
    except OSError as error:
        return Rejected(
            issues=(issue(Code.BINARY_READ_FAILED, f"owned snapshot: {type(error).__name__}"),)
        )
    match inspected:
        case Rejected() as rejected:
            return rejected
        case Accepted(value=raw):
            match parse_binary_visitor(raw):
                case Rejected() as rejected:
                    return rejected
                case Accepted(value=elements):
                    return complete_distribution_observation(
                        pin, plugin, compiler_common, elements, source_artifacts
                    )


def complete_distribution_observation(
    pin: Pin,
    plugin: _PinnedJar,
    compiler_common: _PinnedJar,
    elements: tuple[str, ...],
    artifacts: tuple[Path, Path, Path] | None,
) -> Accepted[DistributionObservation] | Rejected:
    source: SourceCorrespondence = _UNPROVED_SOURCE_CORRESPONDENCE
    if artifacts is not None:
        root, binary, sources = artifacts
        match admit_published_fir_sources(root, pin, plugin.payload, binary, sources):
            case Rejected() as rejected:
                return rejected
            case Accepted(value=source):
                pass
    return Accepted(
        value=DistributionObservation(
            plugin_sha256=plugin.sha256,
            compiler_common_sha256=compiler_common.sha256,
            plugin_version=pin.plugin_version,
            adapter_compiler_version=pin.adapter_compiler_version,
            elements=elements,
            source_correspondence=source,
        )
    )


def distribution_issues(
    pin: Pin, distribution: DistributionEvidence, elements: tuple[Element, ...], *, required: bool
) -> tuple[Issue, ...]:
    match distribution:
        case UnobservedDistribution():
            return (
                (
                    issue(
                        Code.LIVE_DISTRIBUTION_REQUIRED,
                        "The gate needs actual adapter jars and a live binary visitor read",
                    ),
                )
                if required
                else ()
            )
        case DistributionObservation():
            if (
                distribution.plugin_sha256 != pin.plugin_sha256
                or distribution.compiler_common_sha256 != pin.compiler_common_sha256
            ):
                return (
                    issue(
                        Code.DISTRIBUTION_CHANGED,
                        "The live artifact identity differs from the inventory pin",
                    ),
                )
            if distribution.elements != tuple(row.element_id for row in elements):
                return (
                    issue(
                        Code.BINARY_POPULATION_MISMATCH,
                        "Live adapter and upstream declared element identities differ",
                    ),
                )
            return ()


def source_correspondence_issues(pin: Pin, distribution: DistributionEvidence) -> tuple[Issue, ...]:
    if (
        pin.correspondence != "EXACT_SOURCE"
        or not isinstance(distribution, DistributionObservation)
        or not isinstance(distribution.source_correspondence, _MatchedFirSources)
    ):
        return (issue(Code.CORRESPONDENCE_UNPROVED, pin.correspondence_limit),)
    if pin.published_fir is None or (
        distribution.source_correspondence.version != pin.published_fir.version
        or distribution.source_correspondence.binary_sha256 != pin.published_fir.binary_sha256
        or distribution.source_correspondence.sources_sha256 != pin.published_fir.sources_sha256
        or distribution.source_correspondence.source_map_sha256
        != pin.published_fir.source_map_sha256
        or distribution.source_correspondence.pom_sha256 != pin.published_fir.pom_sha256
        or distribution.source_correspondence.sbom_sha256 != pin.published_fir.sbom_sha256
        or distribution.source_correspondence.upstream_commit != pin.upstream_commit
        or distribution.source_correspondence.class_count != pin.published_fir.class_count
        or distribution.source_correspondence.source_count != pin.published_fir.source_count
    ):
        return (issue(Code.SOURCE_PROVENANCE_REJECTED, "Source proof belongs to another pin"),)
    return ()


def audit(
    root: Path,
    catalogue: Path,
    *,
    gate: InventoryGate = InventoryGate.ACCOUNTING,
    distribution: DistributionEvidence = _UNOBSERVED_DISTRIBUTION,
    evidence: CaseEvidence = _UNPROVED_CASES,
) -> AuditReport:
    match read_pin(root):
        case Rejected() as rejected:
            return failure_report("INVENTORY_ACCOUNTING", rejected)
        case Accepted(value=pin):
            pass
    initial_issues = (*pin_artifact_issues(root, pin), *catalogue_issues(catalogue, pin))
    if initial_issues:
        return AuditReport(
            status="FAIL",
            gate="INVENTORY_ACCOUNTING",
            upstream_commit=pin.upstream_commit,
            issues=initial_issues,
        )
    match read_audit_inputs(root):
        case Rejected() as rejected:
            return failure_report("INVENTORY_ACCOUNTING", rejected)
        case Accepted(value=inputs):
            pass
    match extract_elements(inputs.source):
        case Rejected() as rejected:
            return failure_report("INVENTORY_ACCOUNTING", rejected)
        case Accepted(value=elements):
            pass
    issues = [
        *inventory_content_issues(inputs, elements),
        *assess_classifications(elements, inputs.classifications, evidence),
        *assess_fixtures(pin.selected_fixture_sources, inputs.source_index, inputs.selected),
        *selected_file_issues(root, inputs.selected),
    ]
    if gate == InventoryGate.EXACT_SOURCE:
        issues.extend(source_correspondence_issues(pin, distribution))
    issues.extend(
        distribution_issues(pin, distribution, elements, required=gate != InventoryGate.ACCOUNTING)
    )
    counts = Counter(row.disposition for row in inputs.classifications)
    return AuditReport(
        status="FAIL" if issues else "PASS",
        gate="INVENTORY_ACCOUNTING",
        upstream_commit=pin.upstream_commit,
        element_count=len(elements),
        fixture_source_count=len(inputs.source_index),
        fixture_artifact_count=len(inputs.selected),
        counts=tuple(
            DispositionCount(disposition=kind, count=counts[kind]) for kind in Disposition
        ),
        issues=tuple(issues),
        correspondence=pin.correspondence,
        distribution=distribution,
    )


def execution(required_path: Path, junit_paths: tuple[Path, ...]) -> AuditReport:
    match table(required_path, RequiredCase, subset=True):
        case Rejected() as rejected:
            return failure_report("EXECUTION_POPULATION", rejected)
        case Accepted(value=required):
            pass
    observed: list[TestResult] = []
    issues: list[Issue] = []
    for path in junit_paths:
        files = tuple(sorted(path.rglob("*.xml"))) if path.is_dir() else (path,)
        for report in files:
            match read_text(report):
                case Rejected(issues=read_issues):
                    issues.extend(read_issues)
                case Accepted(value=raw):
                    match parse_junit(raw, str(report)):
                        case Rejected(issues=parse_issues):
                            issues.extend(parse_issues)
                        case Accepted(value=rows):
                            observed.extend(rows)
    report = assess_execution(required, tuple(observed))
    return (
        report.model_copy(update={"status": "FAIL", "issues": (*report.issues, *issues)})
        if issues
        else report
    )


R1_ADAPTER_RESOURCES = "relation/intellij/src/nativeTest/resources/semantic-effects"
R1_PUBLIC_RESOURCES = "runtime/hosted/src/nativeTest/resources/semantic-effects"
R2_PUBLIC_RESOURCES = "runtime/hosted/src/nativeTest/resources/semantic-owners"
R1_SHARED_INPUTS = (
    f"{R1_ADAPTER_RESOURCES}/CallerCombinations.kt",
    f"{R1_ADAPTER_RESOURCES}/DependencyEffects.kt",
    f"{R1_ADAPTER_RESOURCES}/Targets.kt",
    f"{R1_ADAPTER_RESOURCES}/language-negative/DuplicateEffects.kt",
    f"{R1_PUBLIC_RESOURCES}/DependencyEffects.kt",
    f"{R1_PUBLIC_RESOURCES}/Targets.kt",
)


class ResourceIdentity(Record):
    artifact: Annotated[
        str,
        StringConstraints(
            pattern=r"^(relation/intellij|runtime/hosted)/src/nativeTest/resources/semantic-(effects|owners)/[A-Za-z0-9_/-]+\.kt$"
        ),
    ]
    sha256: Sha256


class CarrierIdentity(Record):
    artifact: Annotated[
        str, StringConstraints(pattern=r"^(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*$")
    ]
    sha256: Sha256


class NoCarrierReview(Record):
    type: Literal["NO_CARRIER_REVIEW"] = "NO_CARRIER_REVIEW"


class ReviewedCarriers(Record):
    type: Literal["REVIEWED_CARRIERS"] = "REVIEWED_CARRIERS"
    identities: Annotated[tuple[CarrierIdentity, ...], Field(min_length=1)]


type CarrierReview = NoCarrierReview | ReviewedCarriers
_NO_CARRIER_REVIEW = NoCarrierReview()


class R1ResourceCase(Record):
    case_id: Annotated[str, StringConstraints(pattern=r"^r1-[a-z0-9_-]+$")]
    public_source_sha256: Sha256


class R2ResourceCase(Record):
    case_id: Nonempty
    resource: Annotated[str, StringConstraints(pattern=r"^semantic-owners/[A-Za-z0-9_-]+\.kt$")]
    sha256: Sha256


class ResourceInputReport(Record):
    gate: Literal["SEMANTIC_RESOURCE_INPUTS"] = "SEMANTIC_RESOURCE_INPUTS"
    status: Literal["PASS", "FAIL"]
    required_resource_count: int = 0
    observed_resource_count: int = 0
    reviewed_carrier_count: int = 0
    issues: tuple[Issue, ...] = ()


class ResourceDeclarations(Record):
    r1_identities: tuple[ResourceIdentity, ...]
    r1_cases: tuple[R1ResourceCase, ...]
    r1_controls: tuple[R1ResourceCase, ...]
    r2_cases: tuple[R2ResourceCase, ...]
    r4_identities: tuple[ResourceIdentity, ...]


def read_resource_declarations(repository: Path) -> Accepted[ResourceDeclarations] | Rejected:
    parsed = (
        table(repository / R1_ADAPTER_RESOURCES / "input-identities.tsv", ResourceIdentity),
        table(repository / R1_PUBLIC_RESOURCES / "case-manifest.tsv", R1ResourceCase, subset=True),
        table(
            repository / R1_PUBLIC_RESOURCES / "control-manifest.tsv", R1ResourceCase, subset=True
        ),
        table(repository / R2_PUBLIC_RESOURCES / "manifest.tsv", R2ResourceCase, subset=True),
        table(
            repository / "config/kotlin-semantic-coverage/r4-resource-identities.tsv",
            ResourceIdentity,
        ),
    )
    match parsed:
        case (
            Accepted(value=identities),
            Accepted(value=cases),
            Accepted(value=controls),
            Accepted(value=owners),
            Accepted(value=r4),
        ):
            return Accepted(
                value=ResourceDeclarations(
                    r1_identities=identities,
                    r1_cases=cases,
                    r1_controls=controls,
                    r2_cases=owners,
                    r4_identities=r4,
                )
            )
        case _:
            return Rejected(
                issues=tuple(
                    item
                    for result in parsed
                    if isinstance(result, Rejected)
                    for item in result.issues
                )
            )


def required_resource_identities(
    declarations: ResourceDeclarations,
) -> Accepted[tuple[ResourceIdentity, ...]] | Rejected:
    if not declarations.r1_cases or not declarations.r1_controls or not declarations.r2_cases:
        return Rejected(
            issues=(
                issue(
                    Code.EMPTY_REQUIRED_CASES, "A required semantic resource case manifest is empty"
                ),
            )
        )
    case_ids = tuple(
        case.case_id
        for case in (*declarations.r1_cases, *declarations.r1_controls, *declarations.r2_cases)
    )
    issues = list(duplicate_issues(case_ids, Code.DUPLICATE_CASE))
    expected = [
        ResourceIdentity(
            artifact=f"{R1_PUBLIC_RESOURCES}/{case.case_id}.kt", sha256=case.public_source_sha256
        )
        for case in (*declarations.r1_cases, *declarations.r1_controls)
    ]
    shared = {row.artifact: row for row in declarations.r1_identities}
    for artifact in R1_SHARED_INPUTS:
        if artifact not in shared:
            issues.append(issue(Code.RESOURCE_MISSING, artifact))
        else:
            expected.append(shared[artifact])
    r4_required: frozenset[str] = frozenset(
        (
            f"{R2_PUBLIC_RESOURCES}/R4_bare_constructor_reference.kt",
            f"{R2_PUBLIC_RESOURCES}/R4_uninvoked_outer_lambda.kt",
        )
    )
    r4_actual = frozenset(row.artifact for row in declarations.r4_identities)
    issues.extend(
        issue(Code.RESOURCE_MISSING, artifact) for artifact in sorted(r4_required - r4_actual)
    )
    issues.extend(
        issue(Code.RESOURCE_UNEXPECTED, artifact) for artifact in sorted(r4_actual - r4_required)
    )
    expected.extend(declarations.r4_identities)
    expected.extend(
        ResourceIdentity(
            artifact=f"runtime/hosted/src/nativeTest/resources/{case.resource}", sha256=case.sha256
        )
        for case in declarations.r2_cases
    )
    issues.extend(
        duplicate_issues(tuple(row.artifact for row in expected), Code.RESOURCE_DUPLICATE)
    )
    return (
        Rejected(issues=tuple(issues))
        if issues
        else Accepted(value=tuple(sorted(expected, key=lambda row: row.artifact)))
    )


def assess_resource_mapping(
    expected: tuple[ResourceIdentity, ...], identities: tuple[ResourceIdentity, ...]
) -> tuple[Issue, ...]:
    required = {
        row.artifact: row
        for row in expected
        if row.artifact.startswith((R1_ADAPTER_RESOURCES + "/", R1_PUBLIC_RESOURCES + "/"))
    }
    observed = {row.artifact: row for row in identities}
    issues = list(
        duplicate_issues(tuple(row.artifact for row in identities), Code.RESOURCE_DUPLICATE)
    )
    issues.extend(
        issue(Code.RESOURCE_MISSING, artifact)
        for artifact in sorted(required.keys() - observed.keys())
    )
    issues.extend(
        issue(Code.RESOURCE_UNEXPECTED, artifact)
        for artifact in sorted(observed.keys() - required.keys())
    )
    issues.extend(
        issue(Code.RESOURCE_IDENTITY_MISMATCH, artifact)
        for artifact in sorted(required.keys() & observed.keys())
        if required[artifact].sha256 != observed[artifact].sha256
    )
    return tuple(issues)


def assess_resource_population(
    expected: tuple[ResourceIdentity, ...], observed: tuple[str, ...]
) -> tuple[Issue, ...]:
    required = {row.artifact for row in expected}
    actual = frozenset(observed)
    return (
        *tuple(issue(Code.RESOURCE_MISSING, artifact) for artifact in sorted(required - actual)),
        *tuple(issue(Code.RESOURCE_UNEXPECTED, artifact) for artifact in sorted(actual - required)),
    )


def resource_file_issues(
    repository: Path, identities: tuple[ResourceIdentity | CarrierIdentity, ...]
) -> tuple[Issue, ...]:
    issues: list[Issue] = []
    for row in identities:
        path = repository / row.artifact
        if not path.resolve().is_relative_to(repository.resolve()):
            issues.append(
                issue(Code.INVALID_INPUT, f"Resource escapes the repository: {row.artifact}")
            )
            continue
        missing = (
            Code.CARRIER_MISSING if isinstance(row, CarrierIdentity) else Code.RESOURCE_MISSING
        )
        changed = (
            Code.CARRIER_CHANGED if isinstance(row, CarrierIdentity) else Code.RESOURCE_CHANGED
        )
        match digest(path):
            case Rejected():
                issues.append(issue(missing, row.artifact))
            case Accepted(value=actual):
                if actual != row.sha256:
                    issues.append(issue(changed, row.artifact))
    return tuple(issues)


def read_carrier_review(path: Path) -> Accepted[ReviewedCarriers] | Rejected:
    match table(path, CarrierIdentity):
        case Rejected() as rejected:
            return rejected
        case Accepted(value=rows):
            if not rows:
                return Rejected(
                    issues=(
                        issue(
                            Code.EMPTY_REQUIRED_CASES, "The reviewed carrier population is empty"
                        ),
                    )
                )
            duplicates = duplicate_issues(
                tuple(row.artifact for row in rows), Code.CARRIER_DUPLICATE
            )
            return (
                Rejected(issues=duplicates)
                if duplicates
                else Accepted(value=ReviewedCarriers(identities=rows))
            )


def resource_input_guard(
    repository: Path, carriers: CarrierReview = _NO_CARRIER_REVIEW
) -> ResourceInputReport:
    match read_resource_declarations(repository):
        case Rejected(issues=issues):
            return ResourceInputReport(status="FAIL", issues=issues)
        case Accepted(value=declarations):
            pass
    match required_resource_identities(declarations):
        case Rejected(issues=issues):
            return ResourceInputReport(status="FAIL", issues=issues)
        case Accepted(value=expected):
            pass
    try:
        actual = tuple(
            sorted(
                str(path.relative_to(repository))
                for directory in (R1_ADAPTER_RESOURCES, R1_PUBLIC_RESOURCES, R2_PUBLIC_RESOURCES)
                for path in (repository / directory).rglob("*.kt")
            )
        )
    except OSError as error:
        return ResourceInputReport(
            status="FAIL", issues=(issue(Code.UNREADABLE, type(error).__name__),)
        )
    issues = (
        *assess_resource_mapping(expected, declarations.r1_identities),
        *assess_resource_population(expected, actual),
        *resource_file_issues(repository, expected),
    )
    carrier_count = 0
    if isinstance(carriers, ReviewedCarriers):
        carrier_count = len(carriers.identities)
        issues = (
            *issues,
            *duplicate_issues(
                tuple(row.artifact for row in carriers.identities), Code.CARRIER_DUPLICATE
            ),
            *resource_file_issues(repository, carriers.identities),
        )
    return ResourceInputReport(
        status="FAIL" if issues else "PASS",
        reviewed_carrier_count=carrier_count,
        required_resource_count=len(expected),
        observed_resource_count=len(actual),
        issues=issues,
    )


def resource_command(repository: Path, review_path: Path | None) -> int:
    carrier_review: CarrierReview = _NO_CARRIER_REVIEW
    if review_path is not None:
        match read_carrier_review(review_path):
            case Rejected(issues=issues):
                print(ResourceInputReport(status="FAIL", issues=issues).model_dump_json())
                return 1
            case Accepted(value=carrier_review):
                pass
    report = resource_input_guard(repository, carrier_review)
    print(report.model_dump_json())
    return 0 if report.status == "PASS" else 1


def inventory_command(args: argparse.Namespace) -> int:
    source_pair = (args.fir_binary_jar is not None, args.fir_sources_jar is not None)
    if source_pair[0] != source_pair[1] or (
        source_pair[0] and (args.plugin_jar is None or args.compiler_common_jar is None)
    ):
        rejected = Rejected(
            issues=(
                issue(
                    Code.INVALID_INPUT,
                    "Published source admission needs both peer archives and live adapter jars",
                ),
            )
        )
        print(failure_report("INVENTORY_ACCOUNTING", rejected).model_dump_json())
        return 1
    evidence: CaseEvidence = UnprovedCases()
    distribution: DistributionEvidence = _UNOBSERVED_DISTRIBUTION
    if args.required_cases is not None:
        evidence = case_evidence(execution(args.required_cases, tuple(args.junit or ())))
    if args.plugin_jar is not None and args.compiler_common_jar is not None:
        match read_pin(args.root):
            case Rejected() as rejected:
                print(failure_report("INVENTORY_ACCOUNTING", rejected).model_dump_json())
                return 1
            case Accepted(value=pin):
                match observe_distribution(
                    pin,
                    args.plugin_jar,
                    args.compiler_common_jar,
                    JavapInspector(executable=args.javap),
                    source_artifacts=(args.root, args.fir_binary_jar, args.fir_sources_jar)
                    if args.fir_binary_jar is not None and args.fir_sources_jar is not None
                    else None,
                ):
                    case Rejected() as rejected:
                        print(failure_report("INVENTORY_ACCOUNTING", rejected).model_dump_json())
                        return 1
                    case Accepted(value=distribution):
                        pass
    gate = (
        InventoryGate.EXACT_SOURCE
        if args.require_correspondence
        else InventoryGate.LIVE_ELEMENTS
        if args.require_element_correspondence
        else InventoryGate.ACCOUNTING
    )
    report = audit(
        args.root,
        args.catalogue,
        gate=gate,
        distribution=distribution,
        evidence=evidence,
    )
    print(report.model_dump_json())
    return 0 if report.status == "PASS" else 1


def main(arguments: list[str]) -> int:
    if arguments == ["--self-test"]:
        import pytest  # noqa: PLC0415 -- the standalone test mode has a declared dependency

        return int(
            pytest.main(
                [
                    str(Path(__file__).with_name("test_inventory.py")),
                    str(Path(__file__).with_name("test_source_correspondence.py")),
                    "-q",
                    "-o",
                    "addopts=",
                ]
            )
        )
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    inventory_parser = commands.add_parser("audit")
    _ = inventory_parser.add_argument(
        "--root", type=Path, default=Path(__file__).resolve().parents[1]
    )
    _ = inventory_parser.add_argument(
        "--catalogue",
        type=Path,
        default=Path(__file__).resolve().parents[3] / "gradle/libs.versions.toml",
    )
    gate_options = inventory_parser.add_mutually_exclusive_group()
    _ = gate_options.add_argument("--require-correspondence", action="store_true")
    _ = gate_options.add_argument("--require-element-correspondence", action="store_true")
    _ = inventory_parser.add_argument("--plugin-jar", type=Path)
    _ = inventory_parser.add_argument("--compiler-common-jar", type=Path)
    _ = inventory_parser.add_argument("--fir-binary-jar", type=Path)
    _ = inventory_parser.add_argument("--fir-sources-jar", type=Path)
    _ = inventory_parser.add_argument("--javap", type=Path, default=Path("javap"))
    _ = inventory_parser.add_argument("--required-cases", type=Path)
    _ = inventory_parser.add_argument("--junit", type=Path, action="append")
    resource_parser = commands.add_parser("inputs")
    _ = resource_parser.add_argument(
        "--repository-root", type=Path, default=Path(__file__).resolve().parents[3]
    )
    _ = resource_parser.add_argument("--reviewed-carriers", type=Path)
    result_parser = commands.add_parser("execution")
    _ = result_parser.add_argument("--required-cases", type=Path, required=True)
    _ = result_parser.add_argument("--junit", type=Path, action="append", required=True)
    args = parser.parse_args(arguments)
    if args.command == "inputs":
        return resource_command(args.repository_root, args.reviewed_carriers)
    if args.command == "audit":
        return inventory_command(args)
    report = execution(args.required_cases, tuple(args.junit))
    print(report.model_dump_json())
    return 0 if report.status == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
