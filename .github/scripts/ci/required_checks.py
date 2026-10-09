#!/usr/bin/env python3
"""A selected check must succeed; only an explicitly excluded check may be skipped."""
from enum import Enum
import json
import os


class Verdict(str, Enum):
    COMPLETE = "complete"
    SELECTOR_FAILED = "selector-failed"
    INVALID_SELECTION = "invalid-selection"
    CHECK_FAILED = "check-failed"


def inspect(needs: dict) -> Verdict:
    scope = needs.get("scope", {})
    if scope.get("result") != "success":
        return Verdict.SELECTOR_FAILED
    outputs = scope.get("outputs", {})
    if (outputs.get("product") != "run" or outputs.get("portable") != "run"
            or outputs.get("gate_graph") not in {"run", "skip"}):
        return Verdict.INVALID_SELECTION
    for check, job in (("product", "kotlin"), ("portable", "portable"), ("documentation", "documentation")):
        decision = outputs.get(check)
        if decision not in {"run", "skip"}:
            return Verdict.INVALID_SELECTION
        expected = "success" if decision == "run" else "skipped"
        if needs.get(job, {}).get("result") != expected:
            return Verdict.CHECK_FAILED
    return Verdict.COMPLETE


def main() -> None:
    try:
        verdict = inspect(json.loads(os.environ["CI_NEEDS"]))
    except (KeyError, TypeError, ValueError, AttributeError):
        verdict = Verdict.INVALID_SELECTION
    print(f"ci-checks: {verdict.value}")
    raise SystemExit(0 if verdict is Verdict.COMPLETE else 1)


if __name__ == "__main__":
    main()
