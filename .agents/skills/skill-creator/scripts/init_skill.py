#!/usr/bin/env python3
"""Initialize a new skill folder with an optional eval scaffold."""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

from generate_ui_metadata import write_ui_metadata

try:
    from quick_validate import validate_skill
except ModuleNotFoundError:
    from scripts.quick_validate import validate_skill

MAX_SKILL_NAME_LENGTH = 64
ALLOWED_RESOURCES = {"assets", "references", "scripts"}

SKILL_TEMPLATE = """---
name: {skill_name}
description: [TODO: Describe what the skill does and when to use it. Put trigger guidance here, not later in the file.]
---

# {skill_title}

## Overview

[TODO: Explain the outcome this skill should produce.]

## Workflow

1. [TODO: First durable step]
2. [TODO: Second durable step]
3. [TODO: Validation or review step]

## Resources

[TODO: Point to scripts/, references/, or assets/ only when they add real value.]
"""

EXAMPLE_SCRIPT = """#!/usr/bin/env python3
\"\"\"Example helper script for {skill_name}.\"\"\"


def main() -> None:
    print("Replace this placeholder with a real helper script.")


if __name__ == "__main__":
    main()
"""

EXAMPLE_REFERENCE = """# {skill_title} reference

Replace this placeholder with reference material that is too detailed for SKILL.md.
"""

EXAMPLE_ASSET = """This placeholder represents an asset file. Replace it with a real template, fixture, or boilerplate file if needed.
"""


def normalize_skill_name(skill_name: str) -> str:
    normalized = skill_name.strip().lower()
    normalized = re.sub(r"[^a-z0-9]+", "-", normalized)
    normalized = normalized.strip("-")
    normalized = re.sub(r"-{2,}", "-", normalized)
    return normalized


def title_case_skill_name(skill_name: str) -> str:
    return " ".join(word.capitalize() for word in skill_name.split("-"))


def parse_resources(raw_resources: str) -> list[str]:
    if not raw_resources:
        return []
    resources = [item.strip() for item in raw_resources.split(",") if item.strip()]
    invalid = sorted({item for item in resources if item not in ALLOWED_RESOURCES})
    if invalid:
        allowed = ", ".join(sorted(ALLOWED_RESOURCES))
        print(f"[ERROR] Unknown resource type(s): {', '.join(invalid)}")
        print(f"   Allowed: {allowed}")
        sys.exit(1)
    deduped: list[str] = []
    seen: set[str] = set()
    for resource in resources:
        if resource not in seen:
            deduped.append(resource)
            seen.add(resource)
    return deduped


def create_resource_dirs(
    skill_dir: Path,
    skill_name: str,
    skill_title: str,
    resources: list[str],
    include_examples: bool,
) -> None:
    for resource in resources:
        resource_dir = skill_dir / resource
        resource_dir.mkdir(exist_ok=True)
        if resource == "scripts":
            if include_examples:
                example_script = resource_dir / "example.py"
                example_script.write_text(EXAMPLE_SCRIPT.format(skill_name=skill_name))
                example_script.chmod(0o755)
                print("[OK] Created scripts/example.py")
            else:
                print("[OK] Created scripts/")
        elif resource == "references":
            if include_examples:
                example_reference = resource_dir / "overview.md"
                example_reference.write_text(EXAMPLE_REFERENCE.format(skill_title=skill_title))
                print("[OK] Created references/overview.md")
            else:
                print("[OK] Created references/")
        elif resource == "assets":
            if include_examples:
                example_asset = resource_dir / "example.txt"
                example_asset.write_text(EXAMPLE_ASSET)
                print("[OK] Created assets/example.txt")
            else:
                print("[OK] Created assets/")


def create_eval_scaffold(skill_dir: Path, skill_name: str) -> None:
    evals_dir = skill_dir / "evals"
    files_dir = evals_dir / "files"
    history_dir = skill_dir / "history"
    evals_dir.mkdir(exist_ok=True)
    files_dir.mkdir(exist_ok=True)
    history_dir.mkdir(exist_ok=True)

    catalog = {
        "skill_name": skill_name,
        "version": 1,
        "cases": [],
    }
    progression = {
        "skill_name": skill_name,
        "updated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "benchmarks": [],
        "case_history": {},
    }

    (evals_dir / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
    (evals_dir / "pain_points.jsonl").write_text("")
    (history_dir / "progression.json").write_text(json.dumps(progression, indent=2) + "\n")
    print("[OK] Created eval scaffold (evals/ and history/)")


def init_skill(
    skill_name: str,
    path: str,
    resources: list[str],
    include_examples: bool,
    with_evals: bool,
    ui_target: str | None,
    interface_overrides: list[str],
) -> Path | None:
    skill_dir = Path(path).resolve() / skill_name
    if skill_dir.exists():
        print(f"[ERROR] Skill directory already exists: {skill_dir}")
        return None

    try:
        skill_dir.mkdir(parents=True, exist_ok=False)
        print(f"[OK] Created skill directory: {skill_dir}")
    except OSError as exc:
        print(f"[ERROR] Error creating directory: {exc}")
        return None

    skill_title = title_case_skill_name(skill_name)
    try:
        (skill_dir / "SKILL.md").write_text(
            SKILL_TEMPLATE.format(skill_name=skill_name, skill_title=skill_title)
        )
        print("[OK] Created SKILL.md")
    except OSError as exc:
        print(f"[ERROR] Error creating SKILL.md: {exc}")
        return None

    if resources:
        try:
            create_resource_dirs(skill_dir, skill_name, skill_title, resources, include_examples)
        except OSError as exc:
            print(f"[ERROR] Error creating resource directories: {exc}")
            return None

    if with_evals:
        try:
            create_eval_scaffold(skill_dir, skill_name)
        except OSError as exc:
            print(f"[ERROR] Error creating eval scaffold: {exc}")
            return None

    if ui_target or interface_overrides:
        result = write_ui_metadata(
            skill_dir=skill_dir,
            skill_name=skill_name,
            target=ui_target or "openai",
            raw_overrides=interface_overrides,
        )
        if not result:
            return None

    valid, message = validate_skill(skill_dir, skills_root=Path(path).resolve())
    if not valid:
        print("[ERROR] Generated skill failed validation:")
        print(message)
        return None
    print(message)

    print(f"\n[OK] Skill '{skill_name}' initialized successfully at {skill_dir}")
    print("\nNext steps:")
    print("1. Replace the SKILL.md placeholders with real trigger guidance and workflow instructions.")
    print("2. Add only the resources that clearly save future work.")
    if with_evals:
        print("3. Seed evals/catalog.json and treat pain_points.jsonl as the intake queue.")
    if ui_target or interface_overrides:
        print("4. Review agents/ metadata and keep it optional.")
    return skill_dir


def main() -> None:
    parser = argparse.ArgumentParser(description="Create a new skill directory with a portable template.")
    parser.add_argument("skill_name", help="Skill name (normalized to hyphen-case)")
    parser.add_argument("--path", required=True, help="Output directory for the skill")
    parser.add_argument(
        "--resources",
        default="",
        help="Comma-separated list: scripts,references,assets",
    )
    parser.add_argument(
        "--examples",
        action="store_true",
        help="Create example files inside the selected resource directories",
    )
    parser.add_argument(
        "--with-evals",
        action="store_true",
        help="Create evals/catalog.json, evals/pain_points.jsonl, and history/progression.json",
    )
    parser.add_argument(
        "--ui-target",
        default=None,
        help="Optional UI metadata target (for example: openai)",
    )
    parser.add_argument(
        "--interface",
        action="append",
        default=[],
        help="Optional interface override in key=value format (repeatable)",
    )
    args = parser.parse_args()

    raw_skill_name = args.skill_name
    skill_name = normalize_skill_name(raw_skill_name)
    if not skill_name:
        print("[ERROR] Skill name must include at least one letter or digit.")
        sys.exit(1)
    if len(skill_name) > MAX_SKILL_NAME_LENGTH:
        print(
            f"[ERROR] Skill name '{skill_name}' is too long ({len(skill_name)} characters). "
            f"Maximum is {MAX_SKILL_NAME_LENGTH} characters."
        )
        sys.exit(1)
    if skill_name != raw_skill_name:
        print(f"Note: normalized skill name from '{raw_skill_name}' to '{skill_name}'.")

    resources = parse_resources(args.resources)
    if args.examples and not resources:
        print("[ERROR] --examples requires --resources to be set.")
        sys.exit(1)

    result = init_skill(
        skill_name=skill_name,
        path=args.path,
        resources=resources,
        include_examples=args.examples,
        with_evals=args.with_evals,
        ui_target=args.ui_target,
        interface_overrides=args.interface,
    )
    sys.exit(0 if result else 1)


if __name__ == "__main__":
    main()
