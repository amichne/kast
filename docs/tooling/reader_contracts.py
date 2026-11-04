#!/usr/bin/env python3
"""Assess reader contracts from their manifest and derive the current content graph."""

from __future__ import annotations

import argparse
import hashlib
import json
import posixpath
import re
import shutil
import subprocess
from collections import Counter
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Literal, TypeGuard
from urllib.parse import unquote, urlsplit

from glossary_json import prose_lines

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = "docs/okf-public.json"
SITE = "docs/public"
WORD = re.compile(r"[^\W_]+(?:['’\-][^\W_]+)*", re.UNICODE)


class Code(StrEnum):
    INPUT = "READER_INPUT"
    IO = "READER_IO"
    INVENTORY = "READER_INVENTORY"
    LINK = "READER_LINK"
    IMPORT = "READER_IMPORT"
    SCENARIO = "READER_SCENARIO"


class Role(StrEnum):
    ORIENTATION = "orientation"
    HOW_TO = "how-to"
    TUTORIAL = "tutorial"
    REFERENCE = "reference"
    EXPLANATION = "explanation"


class Profile(StrEnum):
    OVERVIEW = "overview"
    SETUP = "setup"
    EXAMPLES = "examples"
    HELP = "help"
    INDEX = "index"
    TASK = "task"
    TUTORIAL = "tutorial"
    REFERENCE = "reference"
    EXPLANATION = "explanation"


@dataclass(frozen=True, slots=True)
class Failure:
    code: Code
    detail: str
    type: Literal["FAILED"] = "FAILED"


@dataclass(frozen=True, slots=True)
class DensityDisposition:
    status: Literal["ACCEPTED", "REVIEW_REQUIRED"]
    reason: str


@dataclass(frozen=True, slots=True)
class PageContract:
    route: str
    role: Role
    outcome: str
    scope: tuple[str, ...]
    exclusions: str
    sourceOwner: str
    densityProfile: Profile
    completionEvidence: str
    primaryContinuation: str
    recoveryDestination: str
    densityDisposition: DensityDisposition


@dataclass(frozen=True, slots=True)
class Scenario:
    startingFacts: tuple[str, ...]
    outcome: str
    path: tuple[str, ...]
    unresolvedDecisions: tuple[str, ...]
    requiredConcepts: tuple[str, ...]
    completionEvidence: str


@dataclass(frozen=True, slots=True)
class ReaderContracts:
    pages: tuple[PageContract, ...]
    firstUse: Scenario


@dataclass(frozen=True, slots=True)
class InputDigest:
    path: str
    sha256: str


@dataclass(frozen=True, slots=True)
class ContentImport:
    binding: str
    path: str


@dataclass(frozen=True, slots=True)
class Edge:
    source: str
    destination: str


@dataclass(frozen=True, slots=True)
class Footprint:
    proseWords: int
    codeLines: int
    scope: Literal["STATIC_COMPLETE", "STATIC_PARTIAL"]
    limits: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class PageReport:
    contract: PageContract
    navigationParent: tuple[str, ...]
    outgoing: tuple[str, ...]
    incoming: tuple[str, ...]
    footprint: Footprint


@dataclass(frozen=True, slots=True)
class Complexity:
    scenario: Scenario
    D: int
    K: int
    H: int
    W: int
    L: int
    evidence: Literal["AUTHOR_REVIEW"] = "AUTHOR_REVIEW"
    readerEvaluation: Literal["NOT_RUN"] = "NOT_RUN"
    nativeTask: Literal["NOT_RUN"] = "NOT_RUN"


@dataclass(frozen=True, slots=True)
class Report:
    sourceRevision: str
    inputs: tuple[InputDigest, ...]
    pages: tuple[PageReport, ...]
    edges: tuple[Edge, ...]
    firstUse: Complexity
    type: Literal["COMPLETED"] = "COMPLETED"
    producer: Literal["kast-reader-contracts-v1"] = "kast-reader-contracts-v1"


def _record(value: object) -> TypeGuard[dict[str, object]]:
    return isinstance(value, dict)


def _strings(value: object) -> TypeGuard[list[str]]:
    if not _objects(value):
        return False
    return all(isinstance(item, str) and item.strip() for item in value)


def _objects(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def _route(value: str) -> bool:
    return value == "/" or re.fullmatch(r"/(?:[a-z0-9-]+/)*[a-z0-9-]+", value) is not None


def _destination(value: str) -> bool:
    path, marker, fragment = value.partition("#")
    return _route(path) and (not marker or re.fullmatch(r"[a-z0-9_-]+", fragment) is not None)


def parse_contracts(value: object) -> ReaderContracts | Failure:
    if not _record(value) or set(value) != {"pages", "firstUse"} or not _objects(value["pages"]):
        return Failure(Code.INPUT, "readerContracts needs pages and firstUse")
    pages: list[PageContract] = []
    fields = {
        "route",
        "role",
        "outcome",
        "scope",
        "exclusions",
        "sourceOwner",
        "densityProfile",
        "completionEvidence",
        "primaryContinuation",
        "recoveryDestination",
        "densityDisposition",
    }
    for page in value["pages"]:
        if not _record(page) or set(page) != fields:
            return Failure(Code.INPUT, "Each page needs the complete reader contract")
        text_fields = fields - {"scope", "densityDisposition"}
        if not all(isinstance(page[key], str) and str(page[key]).strip() for key in text_fields):
            return Failure(Code.INPUT, "Reader contract text must be nonempty")
        route, role, outcome = page["route"], page["role"], page["outcome"]
        exclusions, owner, profile = page["exclusions"], page["sourceOwner"], page["densityProfile"]
        completion, continuation, recovery = (
            page["completionEvidence"],
            page["primaryContinuation"],
            page["recoveryDestination"],
        )
        if not all(
            isinstance(item, str)
            for item in (
                route,
                role,
                outcome,
                exclusions,
                owner,
                profile,
                completion,
                continuation,
                recovery,
            )
        ):
            return Failure(Code.INPUT, "Reader contract text must be strings")
        if not isinstance(route, str) or not _route(route):
            return Failure(Code.INPUT, "Reader route must be canonical")
        if role not in {"orientation", "how-to", "tutorial", "reference", "explanation"}:
            return Failure(Code.INPUT, "Unknown page role")
        if profile not in {
            "overview",
            "setup",
            "examples",
            "help",
            "index",
            "task",
            "tutorial",
            "reference",
            "explanation",
        }:
            return Failure(Code.INPUT, "Unknown density profile")
        scope = page["scope"]
        disposition = page["densityDisposition"]
        if not _strings(scope) or not 2 <= len(scope) <= 3:
            return Failure(Code.INPUT, "Page scope needs two or three bullets")
        if not _record(disposition) or set(disposition) != {"status", "reason"}:
            return Failure(Code.INPUT, "Density needs a status and reason")
        status, reason = disposition["status"], disposition["reason"]
        if (
            not isinstance(status, str)
            or status not in {"ACCEPTED", "REVIEW_REQUIRED"}
            or not isinstance(reason, str)
            or not reason.strip()
        ):
            return Failure(Code.INPUT, "Density disposition is invalid")
        # Direct checks retain refinement for each consumer without casts.
        if (
            not isinstance(role, str)
            or not isinstance(outcome, str)
            or not isinstance(exclusions, str)
            or not isinstance(owner, str)
            or not isinstance(profile, str)
            or not isinstance(completion, str)
            or not isinstance(continuation, str)
            or not isinstance(recovery, str)
        ):
            return Failure(Code.INPUT, "Invalid reader contract")
        if status == "ACCEPTED":
            density = DensityDisposition("ACCEPTED", reason)
        else:
            density = DensityDisposition("REVIEW_REQUIRED", reason)
        if not _destination(recovery) or (continuation != "END" and not _destination(continuation)):
            return Failure(Code.INPUT, "Reader destinations must be routes or END")
        pages.append(
            PageContract(
                route,
                Role(role),
                outcome,
                tuple(scope),
                exclusions,
                owner,
                Profile(profile),
                completion,
                continuation,
                recovery,
                density,
            )
        )
    if len({page.route for page in pages}) != len(pages):
        return Failure(Code.INPUT, "Duplicate reader contracts")
    scenario = value["firstUse"]
    if not _record(scenario) or set(scenario) != {
        "startingFacts",
        "outcome",
        "path",
        "unresolvedDecisions",
        "requiredConcepts",
        "completionEvidence",
    }:
        return Failure(Code.INPUT, "firstUse needs an explicit scenario")
    facts, path = scenario["startingFacts"], scenario["path"]
    decisions, concepts = scenario["unresolvedDecisions"], scenario["requiredConcepts"]
    outcome, completion = scenario["outcome"], scenario["completionEvidence"]
    if (
        not _strings(facts)
        or not facts
        or not _strings(path)
        or not path
        or not _strings(decisions)
        or not _strings(concepts)
        or not isinstance(outcome, str)
        or not outcome.strip()
        or not isinstance(completion, str)
        or not completion.strip()
    ):
        return Failure(Code.INPUT, "firstUse contains invalid fields")
    return ReaderContracts(
        tuple(pages),
        Scenario(tuple(facts), outcome, tuple(path), tuple(decisions), tuple(concepts), completion),
    )


def navigation_parents(value: object) -> dict[str, tuple[str, ...]] | Failure:
    found: list[tuple[str, tuple[str, ...]]] = []

    def visit(node: object, parents: tuple[str, ...]) -> Failure | None:
        if isinstance(node, str):
            if node.startswith("POST /callables/"):
                route = "/reference/callables/" + node.removeprefix("POST /callables/").replace(
                    "_", "-"
                )
            else:
                route = "/" if node == "index" else "/" + node
            if not _route(route):
                return Failure(Code.INVENTORY, "Unsupported navigation entry: " + node)
            found.append((route, parents))
        elif _objects(node):
            for child in node:
                result = visit(child, parents)
                if result is not None:
                    return result
        elif _record(node):
            names = [node[key] for key in ("tab", "group") if key in node]
            if not all(isinstance(name, str) for name in names):
                return Failure(Code.INVENTORY, "Invalid navigation label")
            labels = tuple(str(name) for name in names)
            for key in ("tabs", "groups", "pages"):
                if key in node:
                    result = visit(node[key], parents + labels)
                    if result is not None:
                        return result
        else:
            return Failure(Code.INVENTORY, "Invalid navigation node")
        return None

    failure = visit(value, ())
    if failure is not None:
        return failure
    duplicates = sorted(
        route for route, count in Counter(route for route, _ in found).items() if count != 1
    )
    return (
        Failure(Code.INVENTORY, "Duplicate navigation: " + ", ".join(duplicates))
        if duplicates
        else dict(found)
    )


def _without_fences(text: str) -> tuple[str, int]:
    lines: list[str] = []
    fence = ""
    code = 0
    for line in text.splitlines():
        marker = re.match(r"^\s*(`{3,}|~{3,})", line)
        if marker is not None:
            token = marker.group(1)
            if not fence:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence):
                fence = ""
            continue
        if fence:
            code += bool(line.strip())
        else:
            lines.append(line)
    return "\n".join(lines), code


def outgoing_links(text: str) -> tuple[str, ...]:
    prose, _ = _without_fences(text)
    prose = re.sub(r"`[^`\n]*`", "", prose)
    markdown = re.findall(r"(?<!!)\[[^\]\n]*\]\((/[^\s)]*)\)", prose)
    attributes = re.findall(r"\bhref=[\"'](/[^\s\"']*)[\"']", prose)
    return tuple(sorted(set(markdown + attributes)))


def _source_footprint(text: str, limits: tuple[str, ...]) -> Footprint:
    prose, code = _without_fences(text)
    prose = re.sub(r"^import .*$", "", prose, flags=re.MULTILINE)
    # Include frontmatter title/description and markup labels as reader-facing text.
    metadata = re.match(r"\A---\n(.*?)\n---\n", prose, re.DOTALL)
    labels: list[str] = []
    if metadata is not None:
        labels.extend(
            re.findall(
                r"^(?:title|description):\s*[\"']?(.*?)[\"']?$",
                metadata.group(1),
                flags=re.MULTILINE,
            )
        )
        prose = prose[metadata.end() :]
    labels.extend(re.findall(r'\b(?:title|description|label|alt)="([^"]*)"', prose))
    prose = re.sub(r"<!--[\s\S]*?-->|\{/\*[\s\S]*?\*/\}", "", prose)
    prose = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", prose)
    prose = re.sub(r"<[^>]*>", "", prose)
    prose = re.sub(r"https?://\S+", "", prose)
    prose = prose.replace("`", "")
    # Reuse the established MDX lexical comment policy after inline identifiers survive.
    words = len(WORD.findall("\n".join(prose_lines(prose, "mdx")) + "\n" + "\n".join(labels)))
    return Footprint(words, code, "STATIC_PARTIAL" if limits else "STATIC_COMPLETE", limits)


def _expand_component(source: str, name: str, body: str) -> str:
    """Expand rendered uses while preserving component literals in code examples."""
    pattern = re.compile(r"<" + re.escape(name) + r"\s*/>")
    output: list[str] = []
    fence = ""
    for line in source.splitlines(keepends=True):
        marker = re.match(r"^\s*(`{3,}|~{3,})", line)
        if marker is not None:
            token = marker.group(1)
            if not fence:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence):
                fence = ""
            output.append(line)
        elif fence:
            output.append(line)
        else:
            parts = re.split(r"(`[^`\n]*`)", line)
            output.append(
                "".join(
                    part if index % 2 else pattern.sub(lambda _: body, part)
                    for index, part in enumerate(parts)
                )
            )
    return "".join(output)


def local_imports(source: str, path: str) -> tuple[ContentImport, ...] | Failure:
    """Resolve local imports once for footprint and publication consumers."""
    prose, _ = _without_fences(source)
    found: list[ContentImport] = []
    for line in prose.splitlines():
        line = line.lstrip()
        if re.match(r"""^import(?=\s|[{'"*(])""", line) is None:
            continue
        match = re.fullmatch(r"""import\s*(.*?)\s+from\s+["']([^"']+)["'];?\s*""", line)
        if match is None:
            if re.match(r"""^import\s*[{*'"(]""", line) is not None:
                return Failure(Code.IMPORT, "Unsupported content import: " + path)
            # Wrapped prose can start with the word "import".
            # Mintlify owns syntax. This helper admits complete static imports.
            continue
        binding, target = match.groups()
        if not target.startswith(("/", ".")):
            return Failure(Code.IMPORT, "Nonlocal content import: " + path)
        resolved = (
            SITE + target
            if target.startswith("/")
            else posixpath.normpath(posixpath.join(posixpath.dirname(path), target))
        )
        if not resolved.startswith(SITE + "/") or "/../" in resolved:
            return Failure(Code.IMPORT, "Content import escapes the public source: " + path)
        found.append(ContentImport(binding, resolved))
    return tuple(found)


def content_inputs(root: Path, route: str) -> tuple[InputDigest, ...] | Failure:
    """Bind a page and its local imports without a second content store."""
    if not _route(route):
        return Failure(Code.INPUT, "Invalid content route")
    first = SITE + ("/index.mdx" if route == "/" else route + ".mdx")
    digests: dict[str, str] = {}

    def visit(path: str, stack: tuple[str, ...]) -> Failure | None:
        if path in stack:
            return Failure(Code.IMPORT, "Recursive content import: " + path)
        if path in digests:
            return None
        try:
            candidate = root / path
            if not candidate.resolve().is_relative_to((root / SITE).resolve()):
                return Failure(Code.IMPORT, "Content import escapes the public source: " + path)
            raw = candidate.read_bytes()
            text = raw.decode("utf-8")
        except (OSError, UnicodeError):
            return Failure(Code.IO, path)
        digests[path] = hashlib.sha256(raw).hexdigest()
        if path.endswith((".mdx", ".jsx", ".js", ".tsx", ".ts")):
            imported = local_imports(text, path)
            if isinstance(imported, Failure):
                return imported
            for item in imported:
                failure = visit(item.path, stack + (path,))
                if failure is not None:
                    return failure
        return None

    failure = visit(first, ())
    if failure is None and route.startswith("/reference/callables/"):
        for resource in ("reference/callables.openapi.json", "assets/callable-reference.txt"):
            failure = visit(SITE + "/" + resource, ())
            if failure is not None:
                break
    return (
        failure
        if failure is not None
        else tuple(InputDigest(path, digest) for path, digest in sorted(digests.items()))
    )


def assess(root: Path, revision: str) -> Report | Failure:
    inputs: dict[str, str] = {}

    def read(path: str) -> str | Failure:
        candidate = root / path
        try:
            if not candidate.resolve().is_relative_to(root.resolve()):
                return Failure(Code.IMPORT, path)
            text = candidate.read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            return Failure(Code.IO, path)
        inputs[path] = hashlib.sha256(text.encode()).hexdigest()
        return text

    manifest_text, config_text = read(MANIFEST), read(SITE + "/docs.json")
    if isinstance(manifest_text, Failure):
        return manifest_text
    if isinstance(config_text, Failure):
        return config_text
    try:
        manifest: object = json.loads(manifest_text)
        config: object = json.loads(config_text)
    except json.JSONDecodeError:
        return Failure(Code.INPUT, "Invalid JSON")
    if (
        not _record(manifest)
        or "readerContracts" not in manifest
        or not _record(config)
        or "navigation" not in config
    ):
        return Failure(Code.INPUT, "Missing reader contracts or navigation")
    contracts, parents = (
        parse_contracts(manifest["readerContracts"]),
        navigation_parents(config["navigation"]),
    )
    if isinstance(contracts, Failure):
        return contracts
    if isinstance(parents, Failure):
        return parents
    routes = {page.route for page in contracts.pages}
    if routes != set(parents):
        return Failure(Code.INVENTORY, "Reader contracts differ from current navigation")
    actual = {
        "/"
        if path.stem == "index" and path.parent == root / SITE
        else "/" + path.relative_to(root / SITE).with_suffix("").as_posix()
        for path in (root / SITE).rglob("*.mdx")
        if "snippets" not in path.relative_to(root / SITE).parts
    }
    callable_routes = {route for route in routes if route.startswith("/reference/callables/")}
    if actual != routes:
        return Failure(Code.INVENTORY, "Public MDX inventory differs from reader contracts")
    callable_text = read(SITE + "/reference/callables.openapi.json")
    if isinstance(callable_text, Failure):
        return callable_text
    try:
        callables: object = json.loads(callable_text)
    except json.JSONDecodeError:
        return Failure(Code.INPUT, "Invalid callable artifact")
    if not _record(callables) or not _record(callables.get("paths")):
        return Failure(Code.INPUT, "Missing callable paths")
    expanded: dict[str, str] = {}
    limits: dict[str, tuple[str, ...]] = {}

    def expand(path: str, stack: tuple[str, ...]) -> tuple[str, tuple[str, ...]] | Failure:
        if path in stack:
            return Failure(Code.IMPORT, "Recursive MDX import: " + path)
        source = read(path)
        if isinstance(source, Failure):
            return source
        unresolved: list[str] = []
        imported = local_imports(source, path)
        if isinstance(imported, Failure):
            return imported
        for item in imported:
            if not item.path.endswith(".mdx"):
                continue
            if re.fullmatch(r"[A-Za-z]\w*", item.binding) is None:
                return Failure(Code.IMPORT, "Unsupported MDX component binding: " + path)
            child = expand(item.path, stack + (path,))
            if isinstance(child, Failure):
                return child
            body, child_limits = child
            source = _expand_component(source, item.binding, body)
            unresolved.extend(child_limits)
        if re.search(r'^import .*\.(?:jsx|tsx)["\']', source, re.MULTILINE):
            unresolved.append("JSX text and interaction states need the rendered assessment")
        if re.search(r"^\s*openapi:", source, re.MULTILINE):
            unresolved.append("Schema expansion needs a bounded rendered state")
        return source, tuple(sorted(set(unresolved)))

    for page in contracts.pages:
        closure = content_inputs(root, page.route)
        if isinstance(closure, Failure):
            return closure
        inputs.update((item.path, item.sha256) for item in closure)
        path = SITE + ("/index.mdx" if page.route == "/" else page.route + ".mdx")
        document = expand(path, ())
        if isinstance(document, Failure):
            return document
        expanded[page.route], limits[page.route] = document
        if page.route in callable_routes:
            limits[page.route] += ("Schema expansion needs a bounded rendered state",)
    edges = tuple(
        Edge(route, destination)
        for route, source in sorted(expanded.items())
        for destination in outgoing_links(source)
    )
    for edge in edges:
        destination = unquote(urlsplit(edge.destination).path).rstrip("/") or "/"
        bound_resource = destination.endswith((".json", ".txt")) and SITE + destination in inputs
        if destination not in routes and not bound_resource:
            return Failure(Code.LINK, edge.source + " -> " + edge.destination)
    reports = tuple(
        PageReport(
            page,
            parents[page.route],
            tuple(edge.destination for edge in edges if edge.source == page.route),
            tuple(
                sorted(
                    {
                        edge.source
                        for edge in edges
                        if (urlsplit(edge.destination).path.rstrip("/") or "/") == page.route
                    }
                )
            ),
            _source_footprint(expanded[page.route], limits[page.route]),
        )
        for page in contracts.pages
    )
    for page in contracts.pages:
        if urlsplit(page.recoveryDestination).path not in routes or (
            page.primaryContinuation != "END"
            and urlsplit(page.primaryContinuation).path not in routes
        ):
            return Failure(Code.LINK, "Unknown reader destination: " + page.route)
    scenario = contracts.firstUse
    if not all(route in routes for route in scenario.path) or len(set(scenario.path)) != len(
        scenario.path
    ):
        return Failure(Code.SCENARIO, "Invalid first-use path")
    edge_pairs = {
        (edge.source, urlsplit(edge.destination).path.rstrip("/") or "/") for edge in edges
    }
    if not all(
        (left, right) in edge_pairs
        for left, right in zip(scenario.path, scenario.path[1:], strict=False)
    ):
        return Failure(Code.SCENARIO, "A first-use transition lacks a content link")
    selected = [page for page in reports if page.contract.route in scenario.path]
    if any(page.footprint.scope != "STATIC_COMPLETE" for page in selected):
        return Failure(Code.SCENARIO, "The first-use footprint contains unmeasured content")
    complexity = Complexity(
        scenario,
        len(scenario.unresolvedDecisions),
        len(scenario.requiredConcepts),
        len(scenario.path) - 1,
        sum(page.footprint.proseWords for page in selected),
        sum(page.footprint.codeLines for page in selected),
    )
    return Report(
        revision,
        tuple(InputDigest(path, digest) for path, digest in sorted(inputs.items())),
        reports,
        edges,
        complexity,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--report", type=Path)
    arguments = parser.parse_args()
    git = shutil.which("git")
    if git is None:
        print(json.dumps(asdict(Failure(Code.IO, "Git is unavailable"))))
        return 2
    try:
        revision = subprocess.run(
            [git, "-C", str(arguments.root), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        result = assess(arguments.root.resolve(), revision)
    except (OSError, subprocess.CalledProcessError):
        result = Failure(Code.IO, "Cannot read source revision")
    encoded = json.dumps(asdict(result), indent=2) + "\n"
    if arguments.report is not None:
        arguments.report.parent.mkdir(parents=True, exist_ok=True)
        arguments.report.write_text(encoded)
    else:
        print(encoded, end="")
    return 2 if isinstance(result, Failure) else 0


if __name__ == "__main__":
    raise SystemExit(main())
