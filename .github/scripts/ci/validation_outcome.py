#!/usr/bin/env python3
"""Bind a successful selected gate to its exact producer and qualification scope."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path
from typing import TypeGuard

from required_checks import CompletedChecks, admit_checks
from validation_scope import ROOT, SHA, Reason, Scope, select

WORKFLOW = ".github/workflows/ci.yml"
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")


class Outcome(str, Enum):
    DOCS_VALIDATED = "DOCS_VALIDATED"
    SOURCE_VALIDATED = "SOURCE_VALIDATED"
    PRODUCT_VALIDATED = "PRODUCT_VALIDATED"


class Failure(str, Enum):
    INVALID_RECORD = "INVALID_RECORD"
    WRONG_PRODUCER = "WRONG_PRODUCER"
    PRODUCER_NOT_SUCCESSFUL = "PRODUCER_NOT_SUCCESSFUL"
    SOURCE_TREE_MISMATCH = "SOURCE_TREE_MISMATCH"
    CHECKOUT_MISMATCH = "CHECKOUT_MISMATCH"
    GATE_NOT_COMPLETE = "GATE_NOT_COMPLETE"
    SELECTION_MISMATCH = "SELECTION_MISMATCH"


@dataclass(frozen=True)
class ValidationRecord:
    schemaVersion: int
    outcome: Outcome
    scope: Scope
    repository: str
    sourceRevision: str
    sourceTree: str
    baseRevision: str
    runId: int
    runNumber: int
    runAttempt: int
    event: str


@dataclass(frozen=True)
class DocumentationValidated:
    record: ValidationRecord


@dataclass(frozen=True)
class ProductValidated:
    record: ValidationRecord


@dataclass(frozen=True)
class SourceValidated:
    record: ValidationRecord


SCOPE_OUTCOMES = {
    Scope.DOCUMENTATION: Outcome.DOCS_VALIDATED,
    Scope.SOURCE: Outcome.SOURCE_VALIDATED,
    Scope.PRODUCT: Outcome.PRODUCT_VALIDATED,
    Scope.FULL: Outcome.PRODUCT_VALIDATED,
}


def _mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def decode(document: object) -> ValidationRecord | Failure:
    if not _mapping(document) or len(document) != 11:
        return Failure.INVALID_RECORD
    match document:
        case {
            "schemaVersion": int() as schema,
            "outcome": str() as outcome,
            "scope": str() as scope,
            "repository": str() as repository,
            "sourceRevision": str() as revision,
            "sourceTree": str() as tree,
            "baseRevision": str() as base,
            "runId": int() as run_id,
            "runNumber": int() as number,
            "runAttempt": int() as attempt,
            "event": str() as event,
        }:
            if (
                type(schema) is not int
                or schema != 1
                or not REPOSITORY.fullmatch(repository)
                or any(not SHA.fullmatch(value) or value == "0" * 40 for value in (revision, tree))
                or any(type(value) is not int or value <= 0 for value in (run_id, number, attempt))
                or number > 2147483647
                or event not in {"pull_request", "merge_group", "push", "workflow_dispatch"}
            ):
                return Failure.INVALID_RECORD
            try:
                selected, result = Scope(scope), Outcome(outcome)
            except ValueError:
                return Failure.INVALID_RECORD
            if SCOPE_OUTCOMES[selected] is not result or (
                selected in {Scope.DOCUMENTATION, Scope.SOURCE}
                and (event not in {"push", "pull_request"} or not SHA.fullmatch(base) or base in {revision, "0" * 40})
            ):
                return Failure.INVALID_RECORD
            return ValidationRecord(
                schema, result, selected, repository, revision, tree, base, run_id, number, attempt, event
            )
        case _:
            return Failure.INVALID_RECORD


def admit(
    record: ValidationRecord, run: object, repository: str, revision: str, tree: str
) -> DocumentationValidated | SourceValidated | ProductValidated | Failure:
    """A successful exact main producer can authorize publication or explicit no-publication."""
    match run:
        case {
            "repository": {"full_name": str() as producer_repository},
            "path": str() as workflow,
            "event": "push",
            "head_branch": "main",
            "head_sha": str() as source,
            "id": int() as run_id,
            "run_number": int() as number,
            "run_attempt": int() as attempt,
            "status": str() as status,
            "conclusion": str() as conclusion,
        }:
            if (
                producer_repository != repository
                or record.repository != repository
                or workflow != WORKFLOW
                or record.event != "push"
                or source != revision
                or record.sourceRevision != revision
                or run_id != record.runId
                or number != record.runNumber
                or attempt != record.runAttempt
                or any(type(value) is not int for value in (run_id, number, attempt))
            ):
                return Failure.WRONG_PRODUCER
            if status != "completed" or conclusion != "success":
                return Failure.PRODUCER_NOT_SUCCESSFUL
        case _:
            return Failure.WRONG_PRODUCER
    if record.sourceTree != tree:
        return Failure.SOURCE_TREE_MISMATCH
    if record.outcome is Outcome.DOCS_VALIDATED:
        return DocumentationValidated(record)
    if record.outcome is Outcome.SOURCE_VALIDATED:
        return SourceValidated(record)
    if record.outcome is Outcome.PRODUCT_VALIDATED:
        return ProductValidated(record)
    return Failure.INVALID_RECORD


def record_gate(
    repository: str,
    revision: str,
    tree: str,
    base: str,
    run_id: int,
    number: int,
    attempt: int,
    event: str,
    needs: object,
    root: Path = ROOT,
) -> ValidationRecord | Failure:
    completed = admit_checks(needs)
    if not isinstance(completed, CompletedChecks):
        return Failure.GATE_NOT_COMPLETE
    selection = select(event, base, revision, root)
    if selection.reason is Reason.CHECKOUT_MISMATCH or completed.scope is not selection.scope:
        return Failure.SELECTION_MISMATCH
    result = SCOPE_OUTCOMES[selection.scope]
    record = ValidationRecord(
        1, result, selection.scope, repository, revision, tree, base, run_id, number, attempt, event
    )
    return decode(asdict(record))


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    document = dict(pairs)
    if len(document) != len(pairs):
        raise ValueError("duplicate field")
    return document


def read(path: Path) -> ValidationRecord | Failure:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 4096:
        return Failure.INVALID_RECORD
    try:
        return decode(json.loads(path.read_text(), object_pairs_hook=unique_object))
    except (OSError, ValueError):
        return Failure.INVALID_RECORD


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", type=Path, required=True)
    args = parser.parse_args()
    try:
        checkout = subprocess.check_output(
            ["git", "rev-parse", "HEAD", "HEAD^{tree}"], cwd=ROOT, text=True
        ).splitlines()
        if len(checkout) != 2 or checkout[0] != os.environ["CHECKOUT_SHA"]:
            raise SystemExit(f"validation-outcome: {Failure.CHECKOUT_MISMATCH.value}")
        tree = checkout[1]
        record = record_gate(
            os.environ["GITHUB_REPOSITORY"],
            os.environ["CHECKOUT_SHA"],
            tree,
            os.environ.get("BASE_SHA", ""),
            int(os.environ["GITHUB_RUN_ID"]),
            int(os.environ["GITHUB_RUN_NUMBER"]),
            int(os.environ["GITHUB_RUN_ATTEMPT"]),
            os.environ["GITHUB_EVENT_NAME"],
            json.loads(os.environ["CI_NEEDS"]),
        )
        if isinstance(record, Failure):
            raise SystemExit(f"validation-outcome: {record.value}")
        args.record.parent.mkdir(parents=True, exist_ok=True)
        args.record.write_text(json.dumps(asdict(record), indent=2) + "\n")
        print(f"validation-outcome: {record.outcome.value}")
    except (OSError, ValueError, KeyError, TypeError, AttributeError, subprocess.CalledProcessError):
        raise SystemExit(f"validation-outcome: {Failure.INVALID_RECORD.value}") from None


if __name__ == "__main__":
    main()
