#!/usr/bin/env python3
"""Eval result aggregator and reporter.

Reads per-eval-case results from stdin (a JSON array) and produces an
aggregate report in either human-readable text or machine-readable JSON.

Input format (JSON array on stdin)::

    [
      {
        "case_id": "resolve-symbol-uses-kast",
        "case_name": "Resolve symbol routes through kast-resolve.sh",
        "suite": "kast-routing",
        "assertions": [
          {"assertion": "tool_used(kast-resolve.sh)", "passed": true,
           "expected": "...", "actual": "...", "message": "PASS"},
          ...
        ]
      },
      ...
    ]

CLI::

    python3 report.py [--format=json|text]

Reads from stdin and writes to stdout.
"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Any, Dict, List


# ── types ─────────────────────────────────────────────────────────────

CaseResult = Dict[str, Any]
Assertion = Dict[str, Any]


# ── routing violation detection ───────────────────────────────────────

_KAST_TOOL_PREFIXES = ("kast-",)


def _is_kast_tool_ref(assertion_text: str) -> bool:
    """Return True if the assertion text references a kast tool."""
    return any(prefix in assertion_text for prefix in _KAST_TOOL_PREFIXES)


def _extract_routing_violations(case: CaseResult) -> List[Dict[str, str]]:
    """Identify routing violations in a single case.

    A routing violation occurs when:
    - A ``tool_not_used`` assertion failed (a prohibited tool was used).
    - A ``tool_used`` assertion failed for a kast tool (the agent didn't
      route through kast).
    """
    violations: List[Dict[str, str]] = []
    for a in case.get("assertions", []):
        if a.get("passed"):
            continue

        desc = a.get("assertion", "")

        # tool_not_used failed → prohibited tool was actually used
        if desc.startswith("tool_not_used"):
            # Extract the prohibited tool name from e.g. "tool_not_used(grep)"
            tool = _parenthesized_arg(desc)
            violations.append({
                "case_id": case["case_id"],
                "violation": f"used {tool} instead of kast tool",
            })

        # tool_used failed for a kast tool → agent didn't route through kast
        elif desc.startswith("tool_used") and _is_kast_tool_ref(desc):
            tool = _parenthesized_arg(desc)
            violations.append({
                "case_id": case["case_id"],
                "violation": f"did not route through {tool}",
            })

    return violations


def _parenthesized_arg(text: str) -> str:
    """Extract the argument from ``name(arg)`` notation."""
    start = text.find("(")
    end = text.find(")", start)
    if start != -1 and end != -1:
        return text[start + 1 : end]
    return text


# ── aggregation ───────────────────────────────────────────────────────

def _case_passed(case: CaseResult) -> bool:
    """A case passes when every assertion in it passed."""
    return all(a.get("passed") for a in case.get("assertions", []))


def aggregate(cases: List[CaseResult]) -> Dict[str, Any]:
    """Build the aggregate report dict from a list of case results."""
    suite = cases[0]["suite"] if cases else "unknown"
    total = len(cases)
    passed = sum(1 for c in cases if _case_passed(c))
    failed = total - passed
    score = (passed / total * 100) if total > 0 else 0.0

    all_violations: List[Dict[str, str]] = []
    for case in cases:
        all_violations.extend(_extract_routing_violations(case))

    return {
        "suite": suite,
        "total": total,
        "passed": passed,
        "failed": failed,
        "llm_routing_score": round(score, 1) if score != int(score) else int(score),
        "routing_violations": all_violations,
        "cases": cases,
    }


# ── formatters ────────────────────────────────────────────────────────

def format_text(report: Dict[str, Any]) -> str:
    """Render the aggregate report as human-readable text."""
    lines: List[str] = []
    suite = report["suite"]
    lines.append(f"=== Eval Report: {suite} ===")
    lines.append("")

    for case in report["cases"]:
        assertions = case.get("assertions", [])
        total_a = len(assertions)
        passed_a = sum(1 for a in assertions if a.get("passed"))
        ok = _case_passed(case)
        mark = "✓" if ok else "✗"
        lines.append(
            f"  {mark} {case['case_id']}: {case['case_name']} "
            f"({passed_a}/{total_a} assertions passed)"
        )
        if not ok:
            for a in assertions:
                if not a.get("passed"):
                    lines.append(
                        f"      FAIL: {a['assertion']} — "
                        f"expected: {a['expected']}, actual: {a['actual']}"
                    )

    violations = report["routing_violations"]
    if violations:
        lines.append("")
        lines.append("--- Routing Violations ---")
        for v in violations:
            lines.append(f"  {v['case_id']}: {v['violation']}")

    lines.append("")
    lines.append("--- Summary ---")
    lines.append(f"  Total cases: {report['total']}")
    lines.append(f"  Passed: {report['passed']}")
    lines.append(f"  Failed: {report['failed']}")
    lines.append(f"  llm_routing_score: {report['llm_routing_score']}%")

    return "\n".join(lines)


def format_json(report: Dict[str, Any]) -> str:
    """Render the aggregate report as pretty-printed JSON."""
    return json.dumps(report, indent=2)


# ── CLI ───────────────────────────────────────────────────────────────

def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Aggregate per-eval-case results into a suite report.",
    )
    parser.add_argument(
        "--format",
        choices=["text", "json"],
        default="text",
        dest="output_format",
        help="Output format (default: text)",
    )
    return parser


def main(argv: List[str] | None = None) -> int:
    """CLI entrypoint.  Returns 0 on all-pass, 1 on any failure."""
    parser = _build_parser()
    args = parser.parse_args(argv)

    raw = sys.stdin.read()
    if not raw.strip():
        print("error: no input on stdin", file=sys.stderr)
        return 2

    try:
        cases: List[CaseResult] = json.loads(raw)
    except json.JSONDecodeError as exc:
        print(f"error: invalid JSON input: {exc}", file=sys.stderr)
        return 2

    if not isinstance(cases, list):
        print("error: expected a JSON array of case results", file=sys.stderr)
        return 2

    report = aggregate(cases)

    if args.output_format == "json":
        print(format_json(report))
    else:
        print(format_text(report))

    return 0 if report["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
