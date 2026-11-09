#!/usr/bin/env python3
"""Prove one installed workspace can answer an exact compiler query before agent handoff."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
from dataclasses import asdict, dataclass, field
from enum import StrEnum
from pathlib import Path
from typing import Callable, Literal


class Stage(StrEnum):
    ADMISSION = "ADMISSION"
    PREPARATION = "PREPARATION"
    QUERY = "QUERY"


class Failure(StrEnum):
    INVALID_ROOT = "INVALID_ROOT"
    INVALID_WORKSPACE = "INVALID_WORKSPACE"
    COMMAND_FAILED = "COMMAND_FAILED"
    WORK_UNCERTAIN = "WORK_UNCERTAIN"
    IO_FAILED = "IO_FAILED"
    INVALID_DOCUMENT = "INVALID_DOCUMENT"
    INCOMPLETE = "INCOMPLETE"
    UNEXPECTED_SYMBOL = "UNEXPECTED_SYMBOL"


@dataclass(frozen=True, slots=True)
class Rejected:
    stage: Stage
    failure: Failure
    exitCode: int | None = None
    # Opaque public Kast failure document; retain the child's finite reason unchanged.
    nativeDocument: object = None
    type: Literal["rejected"] = field(default="rejected", init=False)


@dataclass(frozen=True, slots=True)
class Ready:
    workspace: str
    qualifiedName: str
    type: Literal["ready"] = field(default="ready", init=False)


@dataclass(frozen=True, slots=True)
class CommandResult:
    code: int
    stdout: str


@dataclass(frozen=True, slots=True)
class VerifiedSymbol:
    qualifiedName: str


Executor = Callable[[tuple[str, ...], Path, int], CommandResult]


def execute(arguments: tuple[str, ...], workspace: Path, timeout: int) -> CommandResult:
    result = subprocess.run(
        arguments, cwd=workspace, capture_output=True, text=True, timeout=timeout, check=False
    )
    return CommandResult(result.returncode, result.stdout)


def complete_symbol(raw: object, expected: str) -> VerifiedSymbol | Failure:
    if not isinstance(raw, dict) or raw.get("type") != "complete":
        return Failure.INCOMPLETE
    document = raw.get("document")
    if not isinstance(document, dict):
        return Failure.INVALID_DOCUMENT
    summary, coverage = document.get("original_evidence_summary"), document.get("coverage")
    question = document.get("question")
    completion = question.get("completion") if isinstance(question, dict) else None
    if (
        document.get("status") != "complete"
        or not isinstance(coverage, dict)
        or coverage.get("exhaustive") is not True
        or not isinstance(summary, dict)
        or type(summary.get("failure_count")) is not int
        or summary["failure_count"] != 0
        or type(summary.get("omission_count")) is not int
        or summary["omission_count"] != 0
        or not isinstance(completion, dict)
        or completion.get("type") != "COMPLETE_ONLY"
        or completion.get("model") != "COMPILER_RESOLVED_STATIC_V1"
    ):
        return Failure.INCOMPLETE
    rows = document.get("items")
    if not isinstance(rows, list) or len(rows) != 1 or not isinstance(rows[0], dict):
        return Failure.UNEXPECTED_SYMBOL
    row = rows[0]
    signature = row.get("signature")
    if (
        row.get("type") != "exact-symbol"
        or not isinstance(signature, dict)
        or signature.get("qualifiedIdentity") != expected
    ):
        return Failure.UNEXPECTED_SYMBOL
    return VerifiedSymbol(expected)


def rejected_command(stage: Stage, result: CommandResult) -> Rejected:
    try:
        document: object = json.loads(result.stdout)
    except (ValueError, UnicodeError):
        document = None
    if result.code < 0:
        return Rejected(stage, Failure.WORK_UNCERTAIN, 128 - result.code, document)
    return Rejected(stage, Failure.COMMAND_FAILED, result.code, document)


def verify(
    root: Path,
    workspace: Path,
    name: str,
    directory: str,
    source_set: str,
    expected: str,
    wait_ms: int,
    executor: Executor = execute,
) -> Ready | Rejected:
    stage = Stage.ADMISSION
    try:
        if (
            not root.is_absolute()
            or not root.is_dir()
            or root.resolve(strict=True) != root
            or (root / ".kast-headless-owned").is_symlink()
            or not (root / ".kast-headless-owned").is_file()
            or (root / ".kast-headless-owned").read_text() != "kast-managed-headless-v1\n"
            or (root / "bin/kast").is_symlink()
            or (root / "bin").is_symlink()
            or not (root / "bin/kast").is_file()
            or not os.access(root / "bin/kast", os.X_OK)
        ):
            return Rejected(stage, Failure.INVALID_ROOT)
        if not workspace.is_absolute() or not workspace.is_dir() or workspace.resolve(strict=True) != workspace:
            return Rejected(stage, Failure.INVALID_WORKSPACE)
        command = str(root / "bin/kast")
        stage = Stage.PREPARATION
        prepared = executor(
            (command, "workspace", "wait", "--workspace", str(workspace), "--wait", str(wait_ms)),
            workspace,
            (wait_ms + 999) // 1000 + 30,
        )
        if prepared.code != 0:
            return rejected_command(stage, prepared)
        preparation: object = json.loads(prepared.stdout)
        if not isinstance(preparation, dict) or not isinstance(preparation.get("preparation"), dict):
            return Rejected(stage, Failure.INVALID_DOCUMENT, nativeDocument=preparation)
        if preparation["preparation"].get("type") != "ready":
            return Rejected(stage, Failure.INCOMPLETE, nativeDocument=preparation)
        stage = Stage.QUERY
        queried = executor(
            (
                command, "query", "--workspace", str(workspace), "--name", name,
                "--in", directory, "--source-set", source_set, "--fields", "name,signature,location",
                "--max-results", "2", "--max-returned-bytes", "65536", "--max-elapsed-ms", "60000", "--json",
            ),
            workspace,
            90,
        )
        if queried.code != 0:
            return rejected_command(stage, queried)
        document: object = json.loads(queried.stdout)
        admitted = complete_symbol(document, expected)
        return Rejected(stage, admitted, nativeDocument=document) if isinstance(admitted, Failure) else Ready(str(workspace), admitted.qualifiedName)
    except subprocess.TimeoutExpired:
        # Killing a CLI child does not establish settlement of native work. Never retry here.
        return Rejected(stage, Failure.WORK_UNCERTAIN)
    except (ValueError, UnicodeError):
        return Rejected(stage, Failure.INVALID_DOCUMENT)
    except OSError:
        return Rejected(stage, Failure.IO_FAILED)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--workspace", required=True, type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--in", dest="directory", required=True)
    parser.add_argument("--source-set", default="main")
    parser.add_argument("--expected-qualified-name", required=True)
    parser.add_argument("--wait-ms", type=int, default=1_020_000)
    args = parser.parse_args()
    if not 1 <= args.wait_ms <= 3_600_000:
        parser.error("--wait-ms must be in 1..3600000")
    result = verify(
        args.root, args.workspace, args.name, args.directory, args.source_set,
        args.expected_qualified_name, args.wait_ms,
    )
    print(json.dumps(asdict(result)))
    return 0 if isinstance(result, Ready) else 4


if __name__ == "__main__":
    raise SystemExit(main())
