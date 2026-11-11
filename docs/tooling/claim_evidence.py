"""Qualify source declaration citations, not semantic support for a Claim.

Python AST, Kotlin PSI, and the TypeScript parser establish syntax only, not semantic support.
Native currentness is a separate gate.
"""

from __future__ import annotations

import ast
import json
import os
import re
import shutil
import subprocess
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path, PurePosixPath
from typing import TypeGuard


class Reason(StrEnum):
    INVALID_INPUT = "invalid-input"
    MISSING_CLAIM = "missing-claim"
    DUPLICATE_CLAIM = "duplicate-claim"
    NO_WITNESS = "no-witness"
    INVALID_RESOURCE = "invalid-resource"
    MISSING_SOURCE = "missing-source"
    UNSUPPORTED_LANGUAGE = "unsupported-language"
    INVALID_SOURCE = "invalid-source"
    INVALID_RANGE = "invalid-range"
    NO_DECLARATION = "no-declaration"
    PARSER_UNAVAILABLE = "parser-unavailable"


class WitnessKind(StrEnum):
    TEST = "test"
    DECLARATION = "source-declaration"


@dataclass(frozen=True, slots=True)
class Witness:
    page: str
    claim_id: str
    resource: str
    symbol: str
    kind: WitnessKind


@dataclass(frozen=True, slots=True)
class Failure:
    page: str
    claim_id: str
    reason: Reason
    resource: str = ""


@dataclass(frozen=True, slots=True)
class Report:
    witnesses: tuple[Witness, ...]
    failures: tuple[Failure, ...]


@dataclass(frozen=True, slots=True)
class Declaration:
    name: str
    start_line: int
    end_line: int
    executable_test: bool


@dataclass(frozen=True, slots=True)
class ScanAccepted:
    declarations: tuple[Declaration, ...]


@dataclass(frozen=True, slots=True)
class ScanRejected:
    pass


Scan = ScanAccepted | ScanRejected


def _record(value: object) -> TypeGuard[dict[str, object]]:
    return isinstance(value, dict)


def _list(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def _citation(
    root: Path, page: str, claim_id: str, resource: str, observations: Mapping[str, Scan]
) -> Witness | Failure:
    match = re.fullmatch(r"repo://([^#]+)(?:#L([1-9][0-9]*)-L([1-9][0-9]*))?", resource)
    if match is None:
        return Failure(page, claim_id, Reason.INVALID_RESOURCE, resource)
    raw, first, last = match.groups()
    path = PurePosixPath(raw)
    if path.is_absolute() or ".." in path.parts or str(path) != raw or "\\" in raw:
        return Failure(page, claim_id, Reason.INVALID_RESOURCE, resource)
    target = (root / raw).resolve()
    if not target.is_relative_to(root.resolve()) or not target.is_file():
        return Failure(page, claim_id, Reason.MISSING_SOURCE, resource)
    if target.suffix not in {".py", ".kt", ".kts", ".js", ".mjs", ".ts", ".tsx", ".jsx"}:
        return Failure(page, claim_id, Reason.UNSUPPORTED_LANGUAGE, resource)
    try:
        content = target.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        return Failure(page, claim_id, Reason.INVALID_SOURCE, resource)
    lines = content.splitlines()
    test_file = (
        (target.name.startswith(("test_", "test-")) or target.name.endswith("_test.py"))
        if target.suffix == ".py"
        else any(
            part.lower() in {"test", "tests", "nativetest", "semantictest"}
            for part in path.parts[:-1]
        )
        or ".test." in target.name
    )
    if first is None or last is None:
        if not test_file:
            return Failure(page, claim_id, Reason.INVALID_RANGE, resource)
        start, end = 1, len(lines)
    else:
        start, end = int(first), int(last)
        if start > end or end > len(lines):
            return Failure(page, claim_id, Reason.INVALID_RANGE, resource)
    if target.suffix in {".kt", ".kts", ".js", ".mjs", ".ts", ".tsx", ".jsx"}:
        observation = observations.get(raw)
        if observation is None:
            return Failure(page, claim_id, Reason.PARSER_UNAVAILABLE, resource)
        if isinstance(observation, ScanRejected):
            return Failure(page, claim_id, Reason.INVALID_SOURCE, resource)
        for declaration in observation.declarations:
            if start <= declaration.start_line and declaration.end_line <= end:
                is_test = test_file and declaration.executable_test
                if first is not None or is_test:
                    return Witness(
                        page,
                        claim_id,
                        resource,
                        declaration.name,
                        WitnessKind.TEST if is_test else WitnessKind.DECLARATION,
                    )
        return Failure(page, claim_id, Reason.NO_DECLARATION, resource)
    try:
        tree = ast.parse(content)
    except SyntaxError:
        return Failure(page, claim_id, Reason.INVALID_SOURCE, resource)
    test_nodes = {
        node
        for child in tree.body
        for node in (
            [child]
            if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef))
            else child.body
            if isinstance(child, ast.ClassDef)
            and (
                child.name.startswith("Test")
                or any(
                    isinstance(base, ast.Attribute)
                    and base.attr == "TestCase"
                    or isinstance(base, ast.Name)
                    and base.id == "TestCase"
                    for base in child.bases
                )
            )
            else []
        )
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name.startswith("test_")
    }
    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            continue
        if not start <= node.lineno <= end:
            continue
        is_test = test_file and node in test_nodes
        if first is None and not is_test:
            continue
        return Witness(
            page,
            claim_id,
            resource,
            node.name,
            WitnessKind.TEST if is_test else WitnessKind.DECLARATION,
        )
    return Failure(page, claim_id, Reason.NO_DECLARATION, resource)


def _scan_kotlin(
    root: Path, index: Mapping[tuple[str, str], list[dict[str, object]]]
) -> Mapping[str, Scan]:
    classpath = os.environ.get("KAST_KNOWLEDGE_PARSER_CLASSPATH")
    if not classpath:
        try:
            classpath = (
                (root / "build/reports/documentation/knowledge-parser-classpath.txt")
                .read_text(encoding="utf-8")
                .strip()
            )
        except (OSError, UnicodeError):
            classpath = ""
    java = shutil.which("java")
    if not classpath or java is None:
        return {}
    paths: set[str] = set()
    for claims in index.values():
        for claim in claims:
            evidence = claim.get("evidence")
            if not _list(evidence):
                continue
            for item in evidence:
                if not _record(item) or not isinstance(item.get("resource"), str):
                    continue
                resource = item["resource"]
                if not isinstance(resource, str):
                    continue
                match = re.fullmatch(
                    r"repo://([^#]+\.kts?)(?:#L[1-9][0-9]*-L[1-9][0-9]*)?", resource
                )
                if match is None:
                    continue
                raw = match.group(1)
                path = PurePosixPath(raw)
                target = (root / raw).resolve()
                if (
                    not path.is_absolute()
                    and ".." not in path.parts
                    and str(path) == raw
                    and "\\" not in raw
                    and target.is_relative_to(root.resolve())
                    and target.is_file()
                ):
                    paths.add(raw)
    if not paths:
        return {}
    ordered = sorted(paths)
    try:
        result = subprocess.run(
            [
                java,
                "-cp",
                classpath,
                "conventions.jsoncontracts.KnowledgeSymbolsMain",
                *[str(root / path) for path in ordered],
            ],
            capture_output=True,
            text=True,
            timeout=120,
            check=True,
        )
        decoded: object = json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, UnicodeError, ValueError):
        return {}
    return _decode_scans(root, ordered, decoded)


def _decode_scans(root: Path, ordered: list[str], decoded: object) -> Mapping[str, Scan]:
    if not _list(decoded) or len(decoded) != len(ordered):
        return {}
    observations: dict[str, Scan] = {}
    for raw, item in zip(ordered, decoded, strict=True):
        if not _record(item) or item.get("path") != str(root / raw):
            return {}
        scan = item.get("result")
        if not _record(scan):
            return {}
        if scan.get("type") == "rejected":
            observations[raw] = ScanRejected()
            continue
        declarations = scan.get("declarations")
        if scan.get("type") != "accepted" or not _list(declarations):
            return {}
        parsed: list[Declaration] = []
        for declaration in declarations:
            if not _record(declaration):
                return {}
            name, start, end, test = (
                declaration.get(k) for k in ("name", "startLine", "endLine", "executableTest")
            )
            if (
                not isinstance(name, str)
                or not name
                or type(start) is not int
                or type(end) is not int
                or not isinstance(test, bool)
            ):
                return {}
            if start < 1 or end < start:
                return {}
            parsed.append(Declaration(name, start, end, test))
        observations[raw] = ScanAccepted(tuple(parsed))
    return observations


def _scan_typescript(
    root: Path, index: Mapping[tuple[str, str], list[dict[str, object]]]
) -> Mapping[str, Scan]:
    node = shutil.which("node")
    if node is None:
        return {}
    paths: set[str] = set()
    for claims in index.values():
        for claim in claims:
            evidence = claim.get("evidence")
            if not _list(evidence):
                continue
            for item in evidence:
                if not _record(item):
                    continue
                resource = item.get("resource")
                if not isinstance(resource, str):
                    continue
                match = re.fullmatch(
                    r"repo://([^#]+\.(?:[cm]?js|[jt]sx?|jsx))(?:#L[1-9][0-9]*-L[1-9][0-9]*)?",
                    resource,
                )
                if match is None:
                    continue
                raw = match.group(1)
                target = (root / raw).resolve()
                if target.is_relative_to(root.resolve()) and target.is_file():
                    paths.add(raw)
    if not paths:
        return {}
    ordered = sorted(paths)
    try:
        result = subprocess.run(
            [
                node,
                str(Path(__file__).with_name("markdown") / "source-declarations.ts"),
                *[str(root / p) for p in ordered],
            ],
            capture_output=True,
            text=True,
            timeout=120,
            check=True,
        )
        decoded: object = json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, UnicodeError, ValueError):
        return {}
    return _decode_scans(root, ordered, decoded)


def assess(
    root: Path,
    native: object,
    manifest: object,
    parser_observations: Mapping[str, Scan] | None = None,
) -> Report:
    invalid = Report((), (Failure("", "", Reason.INVALID_INPUT),))
    if not _record(native) or native.get("status") != "completed" or not _record(manifest):
        return invalid
    native_claims = native.get("claims")
    views = manifest.get("views")
    associations = manifest.get("associations")
    if not _list(native_claims) or not _list(views) or not _list(associations):
        return invalid
    pages: set[str] = set()
    selected: set[tuple[str, str]] = set()
    for view in views:
        if not _record(view):
            return invalid
        concepts = view.get("concepts")
        if not _list(concepts):
            return invalid
        for page in concepts:
            if not isinstance(page, str) or not page:
                return invalid
            pages.add(page.lstrip("/"))
    for association in associations:
        if not _record(association):
            return invalid
        refs = association.get("claims")
        if not _list(refs):
            return invalid
        for ref in refs:
            if not _record(ref):
                return invalid
            page = ref.get("page")
            claim_id = ref.get("claimId")
            if not isinstance(page, str) or not isinstance(claim_id, str):
                return invalid
            selected.add((page.lstrip("/"), claim_id))
    index: dict[tuple[str, str], list[dict[str, object]]] = {}
    for claim in native_claims:
        if not _record(claim):
            return invalid
        page = claim.get("page")
        claim_id = claim.get("id")
        if not isinstance(page, str) or not isinstance(claim_id, str):
            return invalid
        key = (page.lstrip("/"), claim_id)
        if key[0] in pages or key in selected:
            index.setdefault(key, []).append(claim)
            if key[0] in pages:
                selected.add(key)
    observations = (
        parser_observations
        if parser_observations is not None
        else {**_scan_kotlin(root, index), **_scan_typescript(root, index)}
    )
    witnesses: list[Witness] = []
    failures: list[Failure] = [
        Failure(page, "", Reason.MISSING_CLAIM)
        for page in sorted(pages - {page for page, _ in index})
    ]
    for page, claim_id in sorted(selected):
        claims = index.get((page, claim_id), [])
        if len(claims) != 1:
            failures.append(
                Failure(
                    page, claim_id, Reason.MISSING_CLAIM if not claims else Reason.DUPLICATE_CLAIM
                )
            )
            continue
        evidence = claims[0].get("evidence")
        if not _list(evidence):
            failures.append(Failure(page, claim_id, Reason.INVALID_INPUT))
            continue
        attempts: list[Failure] = []
        for item in evidence:
            if not _record(item):
                if len(attempts) < 8:
                    attempts.append(Failure(page, claim_id, Reason.INVALID_INPUT))
                continue
            resource = item.get("resource")
            if not isinstance(resource, str):
                if len(attempts) < 8:
                    attempts.append(Failure(page, claim_id, Reason.INVALID_INPUT))
                continue
            result = _citation(root, page, claim_id, resource, observations)
            if isinstance(result, Witness):
                witnesses.append(result)
                break
            if len(attempts) < 8:
                attempts.append(result)
        else:
            failures.extend(attempts or [Failure(page, claim_id, Reason.NO_WITNESS)])
    return Report(tuple(witnesses), tuple(failures))
