#!/usr/bin/env python3
"""Audit overlap across a skills collection."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    from .validation import build_overlap_report
except ImportError:
    from validation import build_overlap_report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Scan a skills root for overlapping sibling skills and emit a reusable report.",
    )
    parser.add_argument("skills_root", help="Path to the directory that contains skill folders.")
    parser.add_argument(
        "--output",
        default=None,
        help="Optional path for overlap_report.json (defaults to stdout only).",
    )
    parser.add_argument(
        "--fail-on-findings",
        action="store_true",
        help="Exit non-zero when any overlap findings are present.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    skills_root = Path(args.skills_root).expanduser().resolve()
    if not skills_root.exists():
        print(f"Skills root not found: {skills_root}")
        sys.exit(1)
    if not skills_root.is_dir():
        print(f"Path is not a directory: {skills_root}")
        sys.exit(1)

    report = build_overlap_report(skills_root)
    report["generated_at"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    if args.output:
        output_path = Path(args.output).expanduser().resolve()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Wrote {output_path}")

    findings = report["findings"]
    if not findings:
        print(f"No likely-overlapping skills found under {skills_root}.")
    else:
        print(f"Found {len(findings)} likely-overlapping skill pair(s) under {skills_root}:")
        for finding in findings:
            shared_terms = ", ".join(finding["shared_high_signal_terms"] or finding["shared_terms"])
            print(
                f"- {finding['skill_a']} <-> {finding['skill_b']} "
                f"(score={finding['score']:.2f}, shared={shared_terms})"
            )

    should_fail = args.fail_on_findings and bool(findings)
    sys.exit(1 if should_fail else 0)


if __name__ == "__main__":
    main()
