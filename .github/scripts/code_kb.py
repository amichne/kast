#!/usr/bin/env python3
"""Repository citation and link checks for OpenWiki knowledge pages."""

from __future__ import annotations

import argparse
import ast
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path, PurePosixPath
from typing import Any, Protocol, TypeGuard
from urllib.parse import unquote

import yaml

RESERVED_FILENAMES = {"index.md", "log.md"}
ISO_DATE_HEADING_RE = re.compile(r"^## \d{4}-\d{2}-\d{2}(?:\s|$)")
GITHUB_BLOB_PATH_RE = re.compile(r"/blob/[^/]+/(?P<path>[^#)]+)")
MARKDOWN_LINK_RE = re.compile(r"(?<!!)\[[^]]+\]\((?P<target>[^)]+)\)")
IDENTIFIER_RE = re.compile(r"[A-Za-z_][A-Za-z_0-9]*\Z")


def repo_relative(path: Path, repo: Path) -> str:
    try:
        return path.resolve().relative_to(repo.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def concept_id(path: Path, docs: Path) -> str:
    return path.relative_to(docs).with_suffix("").as_posix()


def normalize_relative(value: str) -> str | None:
    if not value or "\\" in value or "\0" in value:
        return None
    if "://" in value:
        match = GITHUB_BLOB_PATH_RE.search(value)
        if not match:
            return None
        value = match.group("path")
    pure = PurePosixPath(value)
    if pure.is_absolute() or ".." in pure.parts or pure.as_posix() == ".":
        return None
    return pure.as_posix()


def markdown_files(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(path for path in root.rglob("*.md") if path.is_file())


class UniqueKeyLoader(yaml.SafeLoader):
    """Safe YAML boundary that rejects duplicate keys instead of erasing facts."""


class YamlConstructor(Protocol):
    def construct_object(self, node: yaml.Node, deep: bool = False) -> object: ...


def _mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def _record(value: object) -> TypeGuard[dict[str, Any]]:
    return _mapping(value) and all(isinstance(key, str) for key in value)


def _objects(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def _strings(value: object) -> TypeGuard[list[str]]:
    return _objects(value) and all(isinstance(item, str) for item in value)


def unique_mapping(loader: YamlConstructor, node: yaml.MappingNode) -> dict[str, object]:
    mapping: dict[str, object] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=True)
        if not isinstance(key, str):
            raise yaml.constructor.ConstructorError(
                None, None, "YAML keys must be strings", key_node.start_mark
            )
        if key in mapping:
            raise yaml.constructor.ConstructorError(
                None, None, f"duplicate YAML field: {key}", key_node.start_mark
            )
        mapping[key] = loader.construct_object(value_node, deep=True)
    return mapping


UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def parse_frontmatter_mapping(lines: list[str]) -> tuple[dict[str, Any], list[str]]:
    loader = UniqueKeyLoader("\n".join(lines))
    try:
        mapping = loader.get_single_data()
    except yaml.YAMLError as error:
        return {}, [f"invalid YAML frontmatter: {error}"]
    finally:
        loader.dispose()
    if not _record(mapping):
        return {}, ["YAML frontmatter must be an object"]
    return mapping, []


def openwiki_sources(
    frontmatter: dict[str, Any], repo: Path, resolution: list[str] | None = None
) -> tuple[list[str], list[str]]:
    """Read OpenWiki's source resources for impact without owning Claims schemas."""
    sources = frontmatter.get("sources", [])
    if not _objects(sources):
        return [], ["sources must be a list"]
    paths: list[str] = []
    issues: list[str] = []
    for source in sources:
        resource = source.get("resource") if _record(source) else None
        if not isinstance(resource, str) or not resource.startswith("repo://"):
            issues.append("sources resource must use repo://")
            continue
        raw_path, separator, span = resource[7:].partition("#")
        normalized = normalize_relative(raw_path)
        if (
            normalized is None
            or normalized != raw_path
            or (separator and not re.fullmatch(r"L[1-9][0-9]*-L[1-9][0-9]*", span))
        ):
            issues.append(f"sources resource is invalid: {resource}")
            continue
        paths.append(normalized)
        if separator and int(span.split("-")[0][1:]) > int(span.split("-")[1][1:]):
            issues.append(f"sources resource is invalid: {resource}")
            continue
        if not (repo / normalized).resolve().is_relative_to(repo.resolve()):
            issues.append(f"sources path escapes repository: {normalized}")
        elif not (repo / normalized).is_file():
            (issues if resolution is None else resolution).append(
                f"sources path does not exist: {normalized}"
            )
    return paths, issues


def split_frontmatter(text: str) -> tuple[dict[str, Any] | None, str, list[str]]:
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return None, text, ["missing YAML frontmatter"]
    for index in range(1, len(lines)):
        if lines[index].strip() == "---":
            mapping, issues = parse_frontmatter_mapping(lines[1:index])
            body = "\n".join(lines[index + 1 :])
            return mapping, body, issues
    return None, text, ["unterminated YAML frontmatter"]


def kotlin_symbols(paths: list[Path]) -> dict[str, dict[str, Any]]:
    if not paths:
        return {}
    classpath = os.environ.get("KAST_KNOWLEDGE_PARSER_CLASSPATH")
    if not classpath:
        raise RuntimeError(
            "Kotlin declaration checks require the PSI parser; run ./gradlew verifyKnowledgeBase"
        )
    java = shutil.which("java")
    if java is None:
        raise RuntimeError("Java is unavailable for Kotlin declaration checks")
    result = subprocess.run(
        [
            java,
            "-cp",
            classpath,
            "conventions.jsoncontracts.KnowledgeSymbolsMain",
            *map(str, paths),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode:
        raise RuntimeError(f"Kotlin declaration parser failed: {result.stderr.strip()}")
    return {entry["path"]: entry["result"] for entry in json.loads(result.stdout)}


def declared_symbols(path: Path, kotlin: dict[str, dict[str, Any]]) -> set[str]:
    if path.suffix in {".kt", ".kts"}:
        scan = kotlin[str(path)]
        if scan["type"] == "rejected":
            raise ValueError(scan["reason"])
        return set(scan["names"])
    if path.suffix == ".py":
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        return {
            node.name
            for node in ast.walk(tree)
            if isinstance(node, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef))
        }
    raise ValueError("symbols require Kotlin or Python source")


def code_sources(
    frontmatter: dict[str, Any] | None,
    repo: Path,
    kotlin: dict[str, dict[str, Any]] | None,
    resolution: list[str] | None = None,
) -> tuple[list[str], list[str]]:
    if not frontmatter:
        return [], []
    raw_sources = frontmatter.get("code_sources", [])
    if raw_sources in ("", None):
        return [], []
    if not _objects(raw_sources):
        return [], ["code_sources must be a list"]
    paths: list[str] = []
    issues: list[str] = []
    for source in raw_sources:
        if not _record(source):
            issues.append("code_sources entry is not an object")
            continue
        raw_path = source.get("path")
        normalized = normalize_relative(raw_path) if isinstance(raw_path, str) else None
        if normalized is None:
            issues.append(f"code_sources path is invalid: {source.get('path')!r}")
            continue
        paths.append(normalized)
        source_path = repo.joinpath(normalized)
        if not source_path.resolve().is_relative_to(repo.resolve()):
            issues.append(f"code_sources path escapes repository: {normalized}")
            continue
        symbols: list[str] = []
        if "symbols" in source:
            raw_symbols: object = source["symbols"]
            if (
                not _strings(raw_symbols)
                or not raw_symbols
                or any(not IDENTIFIER_RE.fullmatch(symbol) for symbol in raw_symbols)
            ):
                issues.append(
                    f"code_sources symbols must be a non-empty identifier list: {normalized}"
                )
                continue
            symbols = raw_symbols
        if not source_path.is_file():
            (issues if resolution is None else resolution).append(
                f"code_sources path does not exist: {normalized}"
            )
            continue
        if symbols and kotlin is not None:
            try:
                declared = declared_symbols(source_path, kotlin)
            except (ValueError, SyntaxError, OSError) as error:
                (issues if resolution is None else resolution).append(
                    f"code_sources cannot parse {normalized}: {error}"
                )
                continue
            for symbol in symbols:
                if symbol not in declared:
                    (issues if resolution is None else resolution).append(
                        f"code_sources symbol is not declared in {normalized}: {symbol}"
                    )
    return sorted(set(paths)), issues


def validate_index(path: Path, docs: Path, text: str) -> list[str]:
    if not text.startswith("---"):
        return []
    if path.relative_to(docs).as_posix() == "index.md":
        frontmatter, _, issues = split_frontmatter(text)
        if frontmatter is not None:
            unknown = sorted(key for key in frontmatter if key != "okf_version")
            issues.extend(
                f"root index frontmatter field is not OKF-defined: {key}" for key in unknown
            )
        return issues
    return ["index.md frontmatter is only allowed at the bundle root for okf_version"]


def validate_log(text: str) -> list[str]:
    issues: list[str] = []
    if text.startswith("---"):
        issues.append("log.md must not contain frontmatter")
    for line in text.splitlines():
        if line.startswith("## ") and not ISO_DATE_HEADING_RE.match(line):
            issues.append(f"log heading must use YYYY-MM-DD: {line}")
    return issues


@dataclass(frozen=True)
class HistoricalSourceLinks:
    revision: str
    paths: frozenset[str]


class HistoricalLinkFailure(str, Enum):
    UNAVAILABLE = "history-unavailable"
    INVALID = "invalid-history-evidence"
    CHANGED_PAGE = "page-not-identical"


def historical_source_links(
    repo: Path, page: Path, content: bytes, targets: frozenset[str]
) -> HistoricalSourceLinks | HistoricalLinkFailure:
    """Read one page's immutable identity and exact regular-blob targets."""
    git = shutil.which("git")
    if git is None or page.resolve() != page or not page.is_relative_to(repo):
        return HistoricalLinkFailure.UNAVAILABLE
    command = [git, "--no-replace-objects", "--literal-pathspecs", "-C", str(repo)]
    environment = {**os.environ, "GIT_NO_LAZY_FETCH": "1", "GIT_OPTIONAL_LOCKS": "0"}
    relative = page.relative_to(repo).as_posix()
    try:
        shallow = subprocess.run(
            [*command, "rev-parse", "--is-shallow-repository"],
            capture_output=True,
            check=True,
            timeout=10,
            env=environment,
        )
        if shallow.stdout.strip() != b"false":
            return HistoricalLinkFailure.UNAVAILABLE
        history = subprocess.run(
            [*command, "log", "-1", "--format=%H", "HEAD", "--", relative],
            capture_output=True,
            check=True,
            timeout=10,
            env=environment,
        )
        revision = history.stdout.decode("ascii").strip()
        if re.fullmatch(r"[a-f0-9]{40}", revision) is None:
            return HistoricalLinkFailure.INVALID
        tree = subprocess.run(
            [*command, "ls-tree", "-z", revision, "--", relative, *sorted(targets)],
            capture_output=True,
            check=True,
            timeout=10,
            env=environment,
        )
        regular: dict[str, str] = {}
        for entry in tree.stdout.split(b"\0"):
            if not entry:
                continue
            header, separator, name = entry.partition(b"\t")
            fields = header.split()
            if (
                not separator
                or len(fields) != 3
                or re.fullmatch(rb"[a-f0-9]{40}", fields[2]) is None
            ):
                return HistoricalLinkFailure.INVALID
            if fields[0] in {b"100644", b"100755"} and fields[1] == b"blob":
                regular[name.decode("utf-8")] = fields[2].decode("ascii")
        if relative not in regular:
            return HistoricalLinkFailure.INVALID
        objects = tuple(sorted(set(regular.values())))
        available = subprocess.run(
            [*command, "cat-file", "--batch-check=%(objectname) %(objecttype)"],
            input=("\n".join(objects) + "\n").encode("ascii"),
            capture_output=True,
            check=True,
            timeout=10,
            env=environment,
        )
        if available.stdout.splitlines() != [f"{oid} blob".encode("ascii") for oid in objects]:
            return HistoricalLinkFailure.UNAVAILABLE
        recorded = subprocess.run(
            [*command, "cat-file", "blob", regular[relative]],
            capture_output=True,
            check=True,
            timeout=10,
            env=environment,
        )
        if recorded.stdout != content:
            return HistoricalLinkFailure.CHANGED_PAGE
        return HistoricalSourceLinks(revision, frozenset(targets & regular.keys()))
    except (OSError, UnicodeError, subprocess.SubprocessError):
        return HistoricalLinkFailure.UNAVAILABLE


def validate_markdown_links(
    path: Path,
    text: str,
    repo: Path | None = None,
    source_paths: frozenset[str] = frozenset(),
    resolution: list[str] | None = None,
) -> list[str]:
    issues: list[str] = []
    historical_candidates: list[tuple[str, str]] = []
    for match in MARKDOWN_LINK_RE.finditer(text):
        raw_target = match.group("target").strip()
        if raw_target.startswith("<") and ">" in raw_target:
            target = raw_target[1 : raw_target.index(">")]
        else:
            target = raw_target.split(maxsplit=1)[0]
        if not target or target.startswith(("#", "//")) or "://" in target:
            continue
        target = unquote(target).split("#", 1)[0].split("?", 1)[0]
        if not target:
            continue
        if "\0" in target or "\\" in target:
            issues.append(f"link target is invalid: {target!r}")
            continue
        try:
            resolved = (path.parent / target).resolve()
        except (OSError, RuntimeError):
            issues.append(f"link target is unsafe: {target}")
            continue
        if not resolved.exists():
            message = f"link target does not exist: {target}"
            if (
                resolution is not None
                and repo is not None
                and not Path(target).is_absolute()
                and resolved.suffix.lower() not in {".md", ".mdx"}
                and resolved.is_relative_to(repo)
                and resolved == Path(os.path.abspath(path.parent / target))
            ):
                relative = resolved.relative_to(repo).as_posix()
                if relative in source_paths:
                    resolution.append(message)
                else:
                    historical_candidates.append((relative, message))
            else:
                issues.append(message)
    if historical_candidates and repo is not None and resolution is not None:
        historical = historical_source_links(
            repo, path, text.encode("utf-8"), frozenset(name for name, _ in historical_candidates)
        )
        for name, message in historical_candidates:
            if isinstance(historical, HistoricalSourceLinks) and name in historical.paths:
                resolution.append(f"{message} (regular source blob at {historical.revision})")
            elif isinstance(historical, HistoricalLinkFailure):
                issues.append(f"{message} ({historical.value})")
            else:
                issues.append(f"{message} (not a recorded regular source blob)")
    return issues


def parse_page(
    path: Path,
    repo: Path,
    docs: Path,
    kotlin: dict[str, dict[str, Any]] | None,
    separate_resolution: bool = False,
) -> dict[str, Any]:
    text = path.read_bytes().decode("utf-8")
    relative = repo_relative(path, repo)
    docs_relative = path.relative_to(docs).as_posix()
    issues: list[str] = []
    resolution: list[str] = []
    resolution_sink = resolution if separate_resolution else None
    frontmatter: dict[str, Any] | None = None
    source_paths: list[str] = []
    kind = "concept"

    if path.name == "index.md":
        kind = "index"
        issues.extend(validate_index(path, docs, text))
    elif path.name == "log.md":
        kind = "log"
        issues.extend(validate_log(text))
    else:
        frontmatter, _, frontmatter_issues = split_frontmatter(text)
        issues.extend(frontmatter_issues)
        if frontmatter is not None:
            type_value = frontmatter.get("type")
            if not isinstance(type_value, str) or not type_value.strip():
                issues.append("frontmatter type is required")
            paths, source_issues = code_sources(frontmatter, repo, kotlin, resolution_sink)
            source_paths.extend(paths)
            issues.extend(source_issues)
            paths, source_issues = openwiki_sources(frontmatter, repo, resolution_sink)
            source_paths.extend(paths)
            issues.extend(source_issues)

    issues.extend(
        validate_markdown_links(path, text, repo, frozenset(source_paths), resolution_sink)
    )
    return {
        "path": relative,
        "docsPath": docs_relative,
        "conceptId": concept_id(path, docs) if kind == "concept" else None,
        "kind": kind,
        "frontmatter": frontmatter,
        "sourcePaths": sorted(set(source_paths)),
        "issues": issues,
        "resolution": resolution,
    }


def collect_pages(
    repo: Path, docs: Path, check_symbols: bool = False, separate_resolution: bool = False
) -> list[dict[str, Any]]:
    pages = markdown_files(docs)
    kotlin_paths: set[Path] = set()
    if check_symbols:
        for path in pages:
            frontmatter, _, _ = split_frontmatter(path.read_text(encoding="utf-8"))
            sources = (frontmatter or {}).get("code_sources", [])
            if not _objects(sources):
                continue
            for source in sources:
                if not _record(source) or "symbols" not in source:
                    continue
                normalized = normalize_relative(str(source.get("path", "")))
                if normalized is not None:
                    source_path = repo / normalized
                    if source_path.is_file() and source_path.suffix in {".kt", ".kts"}:
                        kotlin_paths.add(source_path)
    kotlin = kotlin_symbols(sorted(kotlin_paths)) if check_symbols else None
    return [parse_page(path, repo, docs, kotlin, separate_resolution) for path in pages]


def changed_files_from_git(repo: Path) -> list[str]:
    git = shutil.which("git")
    if git is None:
        raise RuntimeError("Git is unavailable for source impact")
    result = subprocess.run(
        [git, "-C", str(repo), "status", "--porcelain=v1", "-z", "--untracked-files=all"],
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        return []
    values = result.stdout.decode("utf-8", errors="replace").split("\0")
    changed: set[str] = set()
    index = 0
    while index < len(values):
        entry = values[index]
        index += 1
        if not entry or len(entry) < 4:
            continue
        status = entry[:2]
        path = normalize_relative(entry[3:])
        if path:
            changed.add(path)
        if ("R" in status or "C" in status) and index < len(values):
            original = normalize_relative(values[index])
            index += 1
            if original:
                changed.add(original)
    return sorted(changed)


def impacted_pages(pages: list[dict[str, Any]], changed: list[str]) -> list[dict[str, Any]]:
    changed_set = set(changed)
    impacted: list[dict[str, Any]] = []
    for page in pages:
        matching = sorted(changed_set.intersection(page["sourcePaths"]))
        if matching or page["path"] in changed_set:
            impacted.append(
                {
                    "path": page["path"],
                    "conceptId": page["conceptId"],
                    "matchedSources": matching,
                    "pageChanged": page["path"] in changed_set,
                }
            )
    return impacted


def write_output(payload: dict[str, Any], output_format: str) -> None:
    if output_format == "json":
        print(json.dumps(payload, indent=2, sort_keys=True))
        return
    command = payload.get("command")
    if command == "check":
        print(
            f"OpenWiki citations: {payload['concepts']} concept(s), "
            f"{payload['reservedFiles']} reserved file(s), {payload['issueCount']} issue(s)"
        )
        for issue in payload["issues"]:
            print(f"- {issue['path']}: {issue['message']}")
    elif command == "impact":
        print(
            f"OpenWiki impact: {len(payload['changedFiles'])} changed file(s), "
            f"{len(payload['impactedPages'])} impacted concept(s)"
        )
        for page in payload["impactedPages"]:
            sources = ", ".join(page["matchedSources"]) or "concept changed"
            print(f"- {page['path']}: {sources}")


class KnowledgeEvidence(str, Enum):
    EXACT_DECLARATIONS = "EXACT_DECLARATIONS"
    DOCUMENTATION_INPUTS = "DOCUMENTATION_INPUTS"
    SOURCE_INTEGRITY = "SOURCE_INTEGRITY"


class AssessmentStatus(str, Enum):
    CURRENT = "current"
    UNRESOLVED = "unresolved"
    NOT_ASSESSED = "not-assessed"


@dataclass(frozen=True)
class KnowledgeIssue:
    path: str
    message: str


@dataclass(frozen=True)
class CurrentAssessment:
    status: AssessmentStatus
    issues: tuple[KnowledgeIssue, ...]


@dataclass(frozen=True)
class KnowledgeCheckReport:
    command: str
    evidence: KnowledgeEvidence
    repo: str
    docs: str
    pages: int
    concepts: int
    reservedFiles: int
    issueCount: int
    issues: list[KnowledgeIssue]
    assessment: CurrentAssessment


def command_check(args: argparse.Namespace) -> int:
    repo = args.repo.resolve()
    docs = (repo / args.docs).resolve()
    evidence = {
        "check": KnowledgeEvidence.EXACT_DECLARATIONS,
        "check-documentation-inputs": KnowledgeEvidence.DOCUMENTATION_INPUTS,
        "check-source-integrity": KnowledgeEvidence.SOURCE_INTEGRITY,
    }[args.command]
    pages = collect_pages(
        repo,
        docs,
        check_symbols=evidence is not KnowledgeEvidence.DOCUMENTATION_INPUTS,
        separate_resolution=evidence is KnowledgeEvidence.SOURCE_INTEGRITY,
    )
    bundle_issues: list[KnowledgeIssue] = []
    if args.strict:
        if not docs.is_dir():
            bundle_issues.append(
                KnowledgeIssue(repo_relative(docs, repo), "bundle root does not exist"),
            )
        elif not docs.joinpath("index.md").is_file():
            bundle_issues.append(
                KnowledgeIssue(repo_relative(docs, repo), "root index.md is required"),
            )
        if not any(page["kind"] == "concept" for page in pages):
            bundle_issues.append(
                KnowledgeIssue(repo_relative(docs, repo), "at least one concept is required"),
            )
    issues = bundle_issues + [
        KnowledgeIssue(page["path"], issue) for page in pages for issue in page["issues"]
    ]
    resolution = tuple(
        KnowledgeIssue(page["path"], item) for page in pages for item in page["resolution"]
    )
    report = KnowledgeCheckReport(
        args.command,
        evidence,
        str(repo),
        repo_relative(docs, repo),
        len(pages),
        sum(1 for page in pages if page["kind"] == "concept"),
        sum(1 for page in pages if page["kind"] != "concept"),
        len(issues),
        issues,
        CurrentAssessment(
            AssessmentStatus.NOT_ASSESSED
            if evidence is KnowledgeEvidence.DOCUMENTATION_INPUTS
            else AssessmentStatus.UNRESOLVED
            if resolution or issues
            else AssessmentStatus.CURRENT,
            resolution,
        ),
    )
    write_output(asdict(report), args.format)
    return 1 if args.strict and issues else 0


def command_impact(args: argparse.Namespace) -> int:
    repo = args.repo.resolve()
    docs = (repo / args.docs).resolve()
    pages = collect_pages(repo, docs)
    changed = [path for path in args.changed_file if normalize_relative(path)]
    if args.from_git:
        changed.extend(changed_files_from_git(repo))
    changed = sorted(set(changed))
    payload = {
        "command": "impact",
        "repo": str(repo),
        "docs": repo_relative(docs, repo),
        "changedFiles": changed,
        "impactedPages": impacted_pages(pages, changed),
    }
    write_output(payload, args.format)
    return 0


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    subcommands = root.add_subparsers(dest="command", required=True)

    check = subcommands.add_parser("check", help="Validate an OKF bundle.")
    check.add_argument("--repo", type=Path, default=Path("."))
    check.add_argument("--docs", default="docs")
    check.add_argument("--format", choices=["human", "json"], default="human")
    check.add_argument("--strict", action="store_true")
    check.set_defaults(func=command_check)

    documentation = subcommands.add_parser(
        "check-documentation-inputs",
        help="Examine source paths and links without declaration claims.",
    )
    documentation.add_argument("--repo", type=Path, default=Path("."))
    documentation.add_argument("--docs", default="openwiki")
    documentation.add_argument("--format", choices=["human", "json"], default="human")
    documentation.add_argument("--strict", action="store_true")
    documentation.set_defaults(func=command_check)

    integrity = subcommands.add_parser(
        "check-source-integrity",
        help="Reject structural defects and report current declaration drift separately.",
    )
    integrity.add_argument("--repo", type=Path, default=Path("."))
    integrity.add_argument("--docs", default="openwiki")
    integrity.add_argument("--format", choices=["human", "json"], default="json")
    integrity.add_argument("--strict", action="store_true")
    integrity.set_defaults(func=command_check)

    impact = subcommands.add_parser("impact", help="Find OKF concepts affected by changed files.")
    impact.add_argument("--repo", type=Path, default=Path("."))
    impact.add_argument("--docs", default="docs")
    impact.add_argument("--changed-file", action="append", default=[])
    impact.add_argument("--from-git", action="store_true")
    impact.add_argument("--format", choices=["human", "json"], default="human")
    impact.set_defaults(func=command_impact)
    return root


def main(argv: list[str]) -> int:
    args = parser().parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
