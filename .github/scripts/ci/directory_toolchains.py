"""Admit top-level directories only when each one has its declared toolchain, and no other.

`DIRECTORIES` answers which directory is Go and which is Gradle. A toolchain is observed by its build
files and its compiled sources. Python scripts are tools of any directory, so only `pyproject.toml`
marks a Python directory. A support directory ships nothing by itself and may hold Gradle or Python
workspaces. Go stays in its one module.
"""

from __future__ import annotations

import subprocess
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path, PurePosixPath
from typing import assert_never

ROOT = Path(__file__).resolve().parents[3]


class Toolchain(StrEnum):
    GO = "GO"
    GRADLE = "GRADLE"
    PYTHON = "PYTHON"
    SUPPORT = "SUPPORT"


class Failure(StrEnum):
    UNDECLARED_DIRECTORY = "UNDECLARED_DIRECTORY"
    ABSENT_DIRECTORY = "ABSENT_DIRECTORY"
    ABSENT_BUILD = "ABSENT_BUILD"
    FOREIGN_TOOLCHAIN = "FOREIGN_TOOLCHAIN"


@dataclass(frozen=True, slots=True)
class Violation:
    failure: Failure
    location: str
    detail: str


DIRECTORIES: Mapping[str, Toolchain] = {
    ".agents": Toolchain.SUPPORT,
    ".githooks": Toolchain.SUPPORT,
    ".github": Toolchain.SUPPORT,
    "agent-tools": Toolchain.SUPPORT,
    "app-server": Toolchain.GRADLE,
    "bin": Toolchain.SUPPORT,
    "build-logic": Toolchain.GRADLE,
    "buildSrc": Toolchain.GRADLE,
    "change": Toolchain.GRADLE,
    "cli": Toolchain.GRADLE,
    "config": Toolchain.SUPPORT,
    "diagnostic": Toolchain.GRADLE,
    "distribution": Toolchain.GRADLE,
    "docs": Toolchain.SUPPORT,
    "evidence": Toolchain.GRADLE,
    "experiments": Toolchain.SUPPORT,
    "fixtures": Toolchain.SUPPORT,
    "gradle": Toolchain.SUPPORT,
    "kernel": Toolchain.GRADLE,
    "manager": Toolchain.GO,
    "openwiki": Toolchain.SUPPORT,
    "packaging": Toolchain.PYTHON,
    "protocol": Toolchain.GRADLE,
    "query": Toolchain.GRADLE,
    "relation": Toolchain.GRADLE,
    "runtime": Toolchain.GRADLE,
    "source": Toolchain.GRADLE,
    "symbol": Toolchain.GRADLE,
    "topology": Toolchain.GRADLE,
    "traversal": Toolchain.GRADLE,
    "workspace": Toolchain.GRADLE,
}


def build_of(path: PurePosixPath) -> Toolchain | None:
    """The toolchain that a build file selects."""
    if path.name in ("go.mod", "go.work"):
        return Toolchain.GO
    if path.name == "pyproject.toml":
        return Toolchain.PYTHON
    if path.name.endswith((".gradle.kts", ".gradle")):
        return Toolchain.GRADLE
    return None


def observed(path: PurePosixPath) -> Toolchain | None:
    """The toolchain that a build file or a compiled source needs."""
    if path.suffix == ".go":
        return Toolchain.GO
    if path.suffix in (".kt", ".java"):
        return Toolchain.GRADLE
    return build_of(path)


def admitted(declared: Toolchain) -> frozenset[Toolchain]:
    match declared:
        case Toolchain.GO | Toolchain.GRADLE | Toolchain.PYTHON:
            return frozenset({declared})
        case Toolchain.SUPPORT:
            return frozenset({Toolchain.GRADLE, Toolchain.PYTHON})
        case _:
            assert_never(declared)


def violations(paths: Iterable[str], directories: Mapping[str, Toolchain]) -> list[Violation]:
    found: list[Violation] = []
    present: set[str] = set()
    built: set[str] = set()
    for path in map(PurePosixPath, sorted(paths)):
        # The repository root holds the Gradle root build.
        directory = path.parts[0] if len(path.parts) > 1 else None
        if directory is not None:
            if directory not in directories:
                if directory not in present:
                    detail = "declare its toolchain in directory_toolchains.py"
                    found.append(Violation(Failure.UNDECLARED_DIRECTORY, directory, detail))
                present.add(directory)
                continue
            present.add(directory)
        declared = Toolchain.GRADLE if directory is None else directories[directory]
        toolchain = observed(path)
        if toolchain is not None and toolchain not in admitted(declared):
            detail = f"needs {toolchain}, but its directory declares {declared}"
            found.append(Violation(Failure.FOREIGN_TOOLCHAIN, str(path), detail))
        if directory is not None and build_of(path) is declared:
            built.add(directory)
    for directory, declared in directories.items():
        if directory not in present:
            found.append(Violation(Failure.ABSENT_DIRECTORY, directory, "it has no tracked file"))
        elif declared is not Toolchain.SUPPORT and directory not in built:
            found.append(Violation(Failure.ABSENT_BUILD, directory, f"it has no {declared} build file"))
    return found


def tracked(root: Path = ROOT) -> tuple[str, ...]:
    """Every tracked path. Ignored build outputs and local state are outside the layout."""
    completed = subprocess.run(
        ["git", "ls-files", "-z"], cwd=root, stdin=subprocess.DEVNULL, capture_output=True, check=True
    )
    return tuple(path for path in completed.stdout.decode().split("\0") if path)
