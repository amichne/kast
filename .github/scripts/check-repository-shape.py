#!/usr/bin/env python3
"""Enforce compact, navigable repository source boundaries."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


MAX_PHYSICAL_LINES = 400
MAX_DIRECT_CHILDREN = 10
SOURCE_SUFFIXES = frozenset({".kt", ".kts", ".rs"})
SHAPE_ATTRIBUTE = "repository-shape"
EXCLUDED_KINDS = frozenset({"binary", "generated", "lock"})
REQUIRED_TRACKED_PATHS = frozenset({Path("indexer/build.gradle.kts")})
RETIRED_PATH_PREFIXES = (
    "backend-headless/",
    "backend-idea/",
    "backend-shared/",
    "packaging/jetbrains/",
)
RETIRED_TEXT_MARKERS = (
    "--backend",
    "backend-headless",
    "backend-idea",
    "backend-shared",
    "buildIdeaPlugin",
    "idea-plugin",
    "KastPluginBackend",
    "KastPluginBackendLifecycle",
    "KastPluginService",
    "KastSettingsConfigurable",
    "KastStartupActivity",
    "KastStatusBarWidgetFactory",
    "KastToolWindowFactory",
    "org.jetbrains.intellij.platform",
    "pluginConfiguration",
    "pluginVerification",
    "plugins/kast-headless",
)
RETIREMENT_CONTRACT_PATHS = frozenset(
    {
        Path(".github/scripts/check-repository-shape.py"),
        Path(".github/scripts/test-repository-shape-contract.sh"),
    }
)
RETIRED_TEXT_EXEMPT_PATHS = frozenset(
    {
        # The private indexer runs inside the IntelliJ platform and requires its
        # native descriptor root. It is not a published plugin product.
        Path("indexer/src/main/resources/META-INF/plugin.xml"),
    }
)


class ShapeError(Exception):
    """A repository or shape-metadata failure."""


@dataclass(frozen=True, order=True)
class FileViolation:
    path: str
    physical_lines: int


@dataclass(frozen=True, order=True)
class DirectoryViolation:
    path: str
    direct_children: int


@dataclass(frozen=True, order=True)
class RetiredSurfaceViolation:
    path: str
    marker: str


@dataclass(frozen=True, order=True)
class Candidate:
    path: Path
    binary: bool
    regular: bool


@dataclass(frozen=True)
class ShapeReport:
    governed_files: int
    governed_directories: int
    file_violations: tuple[FileViolation, ...]
    directory_violations: tuple[DirectoryViolation, ...]
    missing_required_paths: tuple[str, ...]
    retired_surface_violations: tuple[RetiredSurfaceViolation, ...]

    @property
    def valid(self) -> bool:
        return not (
            self.file_violations
            or self.directory_violations
            or self.missing_required_paths
            or self.retired_surface_violations
        )


class Parser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        print("ok: false")
        print("code: REPOSITORY_SHAPE_USAGE")
        print(f"message: {json.dumps(message)}")
        print(f"help: {json.dumps(self.format_usage().strip())}")
        raise SystemExit(2)


def run_git(root: Path, arguments: Sequence[str], *, stdin: bytes | None = None) -> bytes:
    try:
        return subprocess.run(
            ["git", "-C", str(root), *arguments],
            input=stdin,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=True,
        ).stdout
    except OSError as error:
        raise ShapeError(f"Git is unavailable while inspecting {root}") from error
    except subprocess.CalledProcessError as error:
        raise ShapeError(f"Git could not inspect repository metadata at {root}") from error


def repository_root(candidate: Path) -> Path:
    resolved = Path(
        run_git(candidate, ["rev-parse", "--show-toplevel"]).decode().strip()
    ).resolve()
    if not resolved.is_dir():
        raise ShapeError(f"repository root is not a directory: {resolved}")
    return resolved


def candidate_paths(root: Path) -> tuple[Candidate, ...]:
    staged = run_git(root, ["ls-files", "--cached", "--stage", "-z"])
    modes: dict[Path, bytes] = {}
    for raw in staged.split(b"\0"):
        if not raw:
            continue
        try:
            metadata, encoded_path = raw.split(b"\t", 1)
            mode, _, stage = metadata.split(b" ", 2)
        except ValueError as error:
            raise ShapeError("git ls-files --stage returned a malformed row") from error
        if stage != b"0":
            raise ShapeError("repository shape cannot be checked with unmerged paths")
        path = Path(encoded_path.decode("utf-8", errors="surrogateescape"))
        modes[path] = mode

    output = run_git(root, ["ls-files", "--cached", "--eol", "-z"])
    candidates: list[Candidate] = []
    for raw in output.split(b"\0"):
        if not raw:
            continue
        try:
            metadata, encoded_path = raw.split(b"\t", 1)
        except ValueError as error:
            raise ShapeError("git ls-files --eol returned a malformed row") from error
        path = Path(encoded_path.decode("utf-8", errors="surrogateescape"))
        mode = modes.get(path)
        if mode is None:
            raise ShapeError(f"git object mode is missing for {path}")
        candidates.append(
            Candidate(
                path=path,
                binary=b"i/-text" in metadata or b"w/-text" in metadata,
                regular=mode in {b"100644", b"100755"},
            )
        )
    return tuple(sorted(candidates))


def excluded_paths(root: Path, candidates: Sequence[Candidate]) -> frozenset[Path]:
    if not candidates:
        return frozenset()
    encoded = b"\0".join(
        str(candidate.path).encode("utf-8", errors="surrogateescape")
        for candidate in candidates
    ) + b"\0"
    output = run_git(
        root,
        ["check-attr", "-z", "--stdin", SHAPE_ATTRIBUTE],
        stdin=encoded,
    )
    fields = output.split(b"\0")
    if fields and not fields[-1]:
        fields.pop()
    if len(fields) % 3:
        raise ShapeError("git check-attr returned a malformed response")

    excluded: set[Path] = set()
    for index in range(0, len(fields), 3):
        path = Path(fields[index].decode("utf-8", errors="surrogateescape"))
        value = fields[index + 2].decode("utf-8", errors="replace")
        if value in EXCLUDED_KINDS:
            excluded.add(path)
        elif value not in {"unspecified", "unset"}:
            allowed = ", ".join(sorted(EXCLUDED_KINDS))
            raise ShapeError(
                f"{path}: {SHAPE_ATTRIBUTE} must be one of {allowed}, got {value!r}"
            )
    return frozenset(excluded)


def physical_lines(path: Path) -> int | None:
    try:
        data = path.read_bytes()
    except (FileNotFoundError, IsADirectoryError):
        return None
    except OSError as error:
        raise ShapeError(f"tracked source could not be read: {path}") from error
    return len(data.splitlines())


def retired_text_markers(path: Path) -> tuple[str, ...]:
    try:
        text = path.read_text(encoding="utf-8")
    except (FileNotFoundError, IsADirectoryError, UnicodeDecodeError):
        return ()
    except OSError as error:
        raise ShapeError(f"tracked text could not be read: {path}") from error
    return tuple(marker for marker in RETIRED_TEXT_MARKERS if marker in text)


def inspect(root: Path) -> ShapeReport:
    candidates = candidate_paths(root)
    excluded = excluded_paths(root, candidates)
    governed: list[tuple[Path, int]] = []
    tracked_paths: list[Path] = []
    retired_surface_violations: set[RetiredSurfaceViolation] = set()
    for candidate in candidates:
        absolute = root / candidate.path
        if not absolute.exists() and not absolute.is_symlink():
            continue
        if candidate.binary or candidate.path in excluded:
            continue
        tracked_paths.append(candidate.path)
        encoded_path = candidate.path.as_posix()
        for prefix in RETIRED_PATH_PREFIXES:
            if encoded_path.startswith(prefix):
                retired_surface_violations.add(
                    RetiredSurfaceViolation(encoded_path, f"path:{prefix}")
                )
        if candidate.regular and candidate.path not in (
            RETIREMENT_CONTRACT_PATHS | RETIRED_TEXT_EXEMPT_PATHS
        ):
            for marker in retired_text_markers(absolute):
                retired_surface_violations.add(
                    RetiredSurfaceViolation(encoded_path, f"text:{marker}")
                )
        if not candidate.regular or candidate.path.suffix not in SOURCE_SUFFIXES:
            continue
        line_count = physical_lines(absolute)
        if line_count is not None:
            governed.append((candidate.path, line_count))

    children: dict[Path, set[str]] = defaultdict(set)
    for path in tracked_paths:
        children[path.parent].add(path.name)
        parents = path.parts[:-1]
        for index, child in enumerate(parents):
            parent = Path() if index == 0 else Path(*parents[:index])
            children[parent].add(child)

    file_violations = tuple(
        sorted(
            FileViolation(str(path), line_count)
            for path, line_count in governed
            if line_count > MAX_PHYSICAL_LINES
        )
    )
    directory_violations = tuple(
        sorted(
            DirectoryViolation(str(path), len(entries))
            for path, entries in children.items()
            if path != Path() and len(entries) > MAX_DIRECT_CHILDREN
        )
    )
    return ShapeReport(
        governed_files=len(governed),
        governed_directories=sum(path != Path() for path in children),
        file_violations=file_violations,
        directory_violations=directory_violations,
        missing_required_paths=tuple(
            sorted(str(path) for path in REQUIRED_TRACKED_PATHS if path not in tracked_paths)
        ),
        retired_surface_violations=tuple(sorted(retired_surface_violations)),
    )


def emit(report: ShapeReport) -> None:
    print(f"ok: {str(report.valid).lower()}")
    if not report.valid:
        print("code: REPOSITORY_SHAPE_CONTRACT_VIOLATED")
    print("limits:")
    print(f"  maxPhysicalLines: {MAX_PHYSICAL_LINES}")
    print(f"  maxDirectChildren: {MAX_DIRECT_CHILDREN}")
    print("scope:")
    print("  files: trackedHandAuthoredKotlinAndRustSource")
    print("  directories: trackedHandAuthoredRepositoryDescendants")
    print("  repositoryRoot: boundary")
    print("summary:")
    print(f"  governedFiles: {report.governed_files}")
    print(f"  governedDirectories: {report.governed_directories}")
    print(f"  fileViolations: {len(report.file_violations)}")
    print(f"  directoryViolations: {len(report.directory_violations)}")
    print(f"  missingRequiredPaths: {len(report.missing_required_paths)}")
    print(f"  retiredSurfaceViolations: {len(report.retired_surface_violations)}")
    if report.file_violations:
        print(
            f"fileViolations[{len(report.file_violations)}]"
            "{path,physicalLines,limit}:"
        )
        for violation in report.file_violations:
            print(
                f"  {json.dumps(violation.path)},"
                f"{violation.physical_lines},{MAX_PHYSICAL_LINES}"
            )
    if report.directory_violations:
        print(
            f"directoryViolations[{len(report.directory_violations)}]"
            "{path,directChildren,limit}:"
        )
        for violation in report.directory_violations:
            print(
                f"  {json.dumps(violation.path)},"
                f"{violation.direct_children},{MAX_DIRECT_CHILDREN}"
            )
    if report.missing_required_paths:
        print(f"missingRequiredPaths[{len(report.missing_required_paths)}]{{path}}:")
        for path in report.missing_required_paths:
            print(f"  {json.dumps(path)}")
    if report.retired_surface_violations:
        print(
            f"retiredSurfaceViolations[{len(report.retired_surface_violations)}]"
            "{path,marker}:"
        )
        for violation in report.retired_surface_violations:
            print(f"  {json.dumps(violation.path)},{json.dumps(violation.marker)}")
    if not report.valid:
        print(
            "help: "
            + json.dumps(
                "Split oversized owners and remove every named retired repository surface."
            )
        )


def main(argv: Sequence[str] | None = None) -> int:
    parser = Parser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path.cwd(),
        help="Git worktree to inspect (default: current repository)",
    )
    arguments = parser.parse_args(argv)
    try:
        report = inspect(repository_root(arguments.root))
    except ShapeError as error:
        print("ok: false")
        print("code: REPOSITORY_SHAPE_CHECK_FAILED")
        print(f"message: {json.dumps(str(error))}")
        print(
            "help: "
            + json.dumps(
                "Pass --root a readable Git worktree, resolve index or filesystem access errors, then retry."
            )
        )
        return 1
    emit(report)
    return 0 if report.valid else 1


if __name__ == "__main__":
    sys.exit(main())
