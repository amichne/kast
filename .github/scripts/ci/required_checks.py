#!/usr/bin/env python3
"""A selected check must succeed; only an explicitly excluded check may be skipped."""

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


@dataclass(frozen=True)
class CompletedChecks:
    scope: Scope


def _mapping(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def admit_checks(needs: object) -> CompletedChecks | Verdict:
    if not _mapping(needs):
        return Verdict.INVALID_SELECTION
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
    for check, job in (("product", "kotlin"), ("portable", "portable"), ("documentation", "documentation")):
        decision = outputs.get(check)
        if decision not in {"run", "skip"}:
            return Verdict.INVALID_SELECTION
        expected = "success" if decision == "run" else "skipped"
        observed = needs.get(job)
        if not _mapping(observed) or observed.get("result") != expected:
            return Verdict.CHECK_FAILED
    return CompletedChecks(selected_scope)


def inspect(needs: object) -> Verdict:
    result = admit_checks(needs)
    return Verdict.COMPLETE if isinstance(result, CompletedChecks) else result


def main() -> None:
    try:
        verdict = inspect(json.loads(os.environ["CI_NEEDS"]))
    except (KeyError, TypeError, ValueError, AttributeError):
        verdict = Verdict.INVALID_SELECTION
    print(f"ci-checks: {verdict.value}")
    raise SystemExit(0 if verdict is Verdict.COMPLETE else 1)


if __name__ == "__main__":
    main()
