#!/usr/bin/env python3
"""A selected check must succeed; only an explicitly excluded check may be skipped."""

import argparse
import json
import os
from dataclasses import dataclass
from enum import Enum
from typing import TypeGuard

from validation_scope import SCOPE_CHECKS, Check, Scope


class Verdict(str, Enum):
    COMPLETE = "complete"
    SELECTOR_FAILED = "selector-failed"
    INVALID_SELECTION = "invalid-selection"
    CHECK_FAILED = "check-failed"


class Gate(str, Enum):
    COMPLETE = "complete"
    SOURCE_FEEDBACK = "source-feedback"


@dataclass(frozen=True)
class CompletedSourceChecks:
    scope: Scope


@dataclass(frozen=True)
class CompletedChecks:
    scope: Scope


def _mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def selected_scope(needs: dict[object, object]) -> Scope | Verdict:
    scope = needs.get("scope")
    if not _mapping(scope) or scope.get("result") != "success":
        return Verdict.SELECTOR_FAILED
    outputs = scope.get("outputs")
    if not _mapping(outputs) or not isinstance(value := outputs.get("scope"), str):
        return Verdict.INVALID_SELECTION
    try:
        selected_scope = Scope(value)
        selected = SCOPE_CHECKS[selected_scope]
    except ValueError:
        return Verdict.INVALID_SELECTION
    if any(outputs.get(check.value) != ("run" if check in selected else "skip") for check in Check):
        return Verdict.INVALID_SELECTION
    return selected_scope


def source_proof(needs: object) -> Verdict:
    """Linux owns source proof independently of native candidate completion."""
    if not _mapping(needs):
        return Verdict.INVALID_SELECTION
    scope = selected_scope(needs)
    if isinstance(scope, Verdict):
        return scope
    selected = SCOPE_CHECKS[scope]
    if Check.SOURCE not in selected:
        return Verdict.INVALID_SELECTION
    observed = needs.get("source-work")
    if not _mapping(observed) or observed.get("result") != "success":
        return Verdict.CHECK_FAILED
    return Verdict.COMPLETE


def admit_checks(needs: object, gate: Gate = Gate.COMPLETE) -> CompletedChecks | CompletedSourceChecks | Verdict:
    if not _mapping(needs):
        return Verdict.INVALID_SELECTION
    selected_scope_value = selected_scope(needs)
    if isinstance(selected_scope_value, Verdict):
        return selected_scope_value
    selected = SCOPE_CHECKS[selected_scope_value]
    for check, job in ((Check.SOURCE, "source"), (Check.PRODUCT, "kotlin"), (Check.PORTABLE, "portable"), (Check.DOCUMENTATION, "documentation")):
        if gate is Gate.SOURCE_FEEDBACK and check is Check.PRODUCT:
            continue
        expected = "success" if check in selected else "skipped"
        observed = needs.get(job)
        if not _mapping(observed) or observed.get("result") != expected:
            return Verdict.CHECK_FAILED
    return CompletedSourceChecks(selected_scope_value) if gate is Gate.SOURCE_FEEDBACK else CompletedChecks(selected_scope_value)


def inspect(needs: object, gate: Gate = Gate.COMPLETE) -> Verdict:
    result = admit_checks(needs, gate)
    return Verdict.COMPLETE if isinstance(result, (CompletedChecks, CompletedSourceChecks)) else result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--source-feedback", action="store_true")
    mode.add_argument("--source-proof", action="store_true")
    args = parser.parse_args()
    gate = Gate.SOURCE_FEEDBACK if args.source_feedback else Gate.COMPLETE
    try:
        needs: object = json.loads(os.environ["CI_NEEDS"])
        verdict = source_proof(needs) if args.source_proof else inspect(needs, gate)
    except (KeyError, TypeError, ValueError, AttributeError):
        verdict = Verdict.INVALID_SELECTION
    print(f"ci-checks: {verdict.value}")
    raise SystemExit(0 if verdict is Verdict.COMPLETE else 1)


if __name__ == "__main__":
    main()
