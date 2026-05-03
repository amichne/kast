#!/usr/bin/env python3
"""Quick validation for skills and their optional eval scaffold."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from .validation import format_report, validate_skill_directory
except ImportError:
    from validation import format_report, validate_skill_directory


def validate_skill(
    skill_path: str | Path,
    *,
    audit_collection: bool = True,
    skills_root: str | Path | None = None,
) -> tuple[bool, str]:
    report = validate_skill_directory(
        Path(skill_path),
        audit_collection=audit_collection,
        skills_root=Path(skills_root) if skills_root is not None else None,
    )
    return report.is_valid, format_report(report, "Skill is valid.")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Validate a skill directory, its eval scaffold, and sibling-scope audit hints.",
    )
    parser.add_argument("skill_directory", help="Path to the skill directory")
    parser.add_argument(
        "--skills-root",
        default=None,
        help="Optional skills root for overlap audits (defaults to the skill directory parent).",
    )
    parser.add_argument(
        "--no-collection-audit",
        action="store_true",
        help="Skip overlap audits against sibling skills.",
    )
    parser.add_argument(
        "--fail-on-warnings",
        action="store_true",
        help="Return a non-zero exit code when warnings are present.",
    )
    args = parser.parse_args()

    report = validate_skill_directory(
        Path(args.skill_directory).expanduser().resolve(),
        audit_collection=not args.no_collection_audit,
        skills_root=Path(args.skills_root).expanduser().resolve()
        if args.skills_root
        else None,
    )
    print(format_report(report, "Skill is valid."))

    should_fail = (not report.is_valid) or (args.fail_on_warnings and bool(report.warnings))
    sys.exit(1 if should_fail else 0)


if __name__ == "__main__":
    main()
