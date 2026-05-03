#!/usr/bin/env python3
"""Generate optional UI metadata for a skill.

The core skill format is model-agnostic. This script only writes optional
adapter metadata in agents/ for runtimes that support it.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ACRONYMS = {
    "API",
    "CI",
    "CLI",
    "CSV",
    "GH",
    "JSON",
    "LLM",
    "MCP",
    "PDF",
    "PR",
    "SQL",
    "UI",
    "URL",
}

BRANDS = {
    "datadog": "DataDog",
    "fastapi": "FastAPI",
    "github": "GitHub",
    "openai": "OpenAI",
    "openapi": "OpenAPI",
    "pagerduty": "PagerDuty",
    "sqlite": "SQLite",
}

SMALL_WORDS = {"and", "or", "to", "up", "with"}
ALLOWED_INTERFACE_KEYS = {
    "brand_color",
    "default_prompt",
    "display_name",
    "icon_large",
    "icon_small",
    "short_description",
}
SUPPORTED_TARGETS = {"openai"}


def yaml_quote(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
    return f'"{escaped}"'


def format_display_name(skill_name: str) -> str:
    words = [word for word in skill_name.split("-") if word]
    formatted: list[str] = []
    for index, word in enumerate(words):
        lower = word.lower()
        upper = word.upper()
        if upper in ACRONYMS:
            formatted.append(upper)
            continue
        if lower in BRANDS:
            formatted.append(BRANDS[lower])
            continue
        if index > 0 and lower in SMALL_WORDS:
            formatted.append(lower)
            continue
        formatted.append(word.capitalize())
    return " ".join(formatted)


def generate_short_description(display_name: str) -> str:
    candidates = [
        f"Help with {display_name} tasks",
        f"Help with {display_name} workflows",
        f"{display_name} helper",
        f"{display_name} tools",
    ]
    for candidate in candidates:
        if 25 <= len(candidate) <= 64:
            return candidate
    trimmed = display_name[:57].rstrip()
    return f"{trimmed} helper"


def read_frontmatter_name(skill_dir: Path) -> str | None:
    skill_md = skill_dir / "SKILL.md"
    if not skill_md.exists():
        print(f"[ERROR] SKILL.md not found in {skill_dir}")
        return None
    content = skill_md.read_text()
    match = re.match(r"^---\n(.*?)\n---", content, re.DOTALL)
    if not match:
        print("[ERROR] Invalid SKILL.md frontmatter format.")
        return None
    name = None
    for line in match.group(1).splitlines():
        if line.startswith((" ", "\t")) or ":" not in line:
            continue
        key, value = line.split(":", 1)
        if key.strip() == "name":
            name = value.strip().strip('"').strip("'")
            break
    if not isinstance(name, str) or not name.strip():
        print("[ERROR] Frontmatter 'name' is missing or invalid.")
        return None
    return name.strip()


def parse_interface_overrides(raw_overrides: list[str]) -> tuple[dict[str, str] | None, list[str] | None]:
    overrides: dict[str, str] = {}
    optional_order: list[str] = []
    for item in raw_overrides:
        if "=" not in item:
            print(f"[ERROR] Invalid interface override '{item}'. Use key=value.")
            return None, None
        key, value = item.split("=", 1)
        key = key.strip()
        value = value.strip()
        if key not in ALLOWED_INTERFACE_KEYS:
            allowed = ", ".join(sorted(ALLOWED_INTERFACE_KEYS))
            print(f"[ERROR] Unknown interface field '{key}'. Allowed: {allowed}")
            return None, None
        overrides[key] = value
        if key not in ("display_name", "short_description") and key not in optional_order:
            optional_order.append(key)
    return overrides, optional_order


def write_ui_metadata(
    skill_dir: Path,
    skill_name: str,
    target: str,
    raw_overrides: list[str],
) -> Path | None:
    if target not in SUPPORTED_TARGETS:
        supported = ", ".join(sorted(SUPPORTED_TARGETS))
        print(f"[ERROR] Unsupported target '{target}'. Supported: {supported}")
        return None

    overrides, optional_order = parse_interface_overrides(raw_overrides)
    if overrides is None or optional_order is None:
        return None

    display_name = overrides.get("display_name") or format_display_name(skill_name)
    short_description = overrides.get("short_description") or generate_short_description(display_name)
    if not (25 <= len(short_description) <= 64):
        print(
            "[ERROR] short_description must be 25-64 characters "
            f"(got {len(short_description)})."
        )
        return None

    interface_lines = [
        "interface:",
        f"  display_name: {yaml_quote(display_name)}",
        f"  short_description: {yaml_quote(short_description)}",
    ]
    for key in optional_order:
        value = overrides.get(key)
        if value is not None:
            interface_lines.append(f"  {key}: {yaml_quote(value)}")

    output_path = skill_dir / "agents" / f"{target}.yaml"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(interface_lines) + "\n")
    print(f"[OK] Created {output_path.relative_to(skill_dir)}")
    return output_path


def main() -> None:
    parser = argparse.ArgumentParser(description="Create optional UI metadata for a skill.")
    parser.add_argument("skill_dir", help="Path to the skill directory")
    parser.add_argument("--name", help="Skill name override (defaults to SKILL.md frontmatter)")
    parser.add_argument(
        "--target",
        default="openai",
        choices=sorted(SUPPORTED_TARGETS),
        help="Metadata adapter target",
    )
    parser.add_argument(
        "--interface",
        action="append",
        default=[],
        help="Interface override in key=value format (repeatable)",
    )
    args = parser.parse_args()

    skill_dir = Path(args.skill_dir).resolve()
    if not skill_dir.exists():
        print(f"[ERROR] Skill directory not found: {skill_dir}")
        sys.exit(1)
    if not skill_dir.is_dir():
        print(f"[ERROR] Path is not a directory: {skill_dir}")
        sys.exit(1)

    skill_name = args.name or read_frontmatter_name(skill_dir)
    if not skill_name:
        sys.exit(1)

    result = write_ui_metadata(skill_dir, skill_name, args.target, args.interface)
    sys.exit(0 if result else 1)


if __name__ == "__main__":
    main()
