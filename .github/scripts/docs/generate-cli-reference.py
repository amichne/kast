#!/usr/bin/env python3
"""Generate the public semantic-operation reference from Kast's command catalog."""

from __future__ import annotations

import argparse
import difflib
import json
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
CATALOG_PATH = REPO_ROOT / "cli-rs/protocol/source/commands.json"
OUTPUT_PATH = REPO_ROOT / "docs/public/reference/semantic-operations.md"
CATEGORY_ORDER = ("symbol", "graph", "repository", "mutation")
CATEGORY_TITLES = {
    "symbol": "Symbol evidence",
    "graph": "Graph coverage",
    "repository": "Repository relationships",
    "mutation": "Semantic changes",
}


def markdown_code(value: object) -> str:
    escaped = str(value).replace("`", "\\`")
    return f"`{escaped}`"


def field_type(field: dict[str, object]) -> str:
    value = field.get("type", "variant")
    if isinstance(value, list):
        return " or ".join(markdown_code(item) for item in value)
    return markdown_code(value)


def render_command(command: dict[str, object]) -> list[str]:
    method = command["method"]
    request = command.get("request", {})
    fields = request.get("fields", {}) if isinstance(request, dict) else {}
    required = set(request.get("required", [])) if isinstance(request, dict) else set()
    exclusive = request.get("exclusiveRequired", []) if isinstance(request, dict) else []

    lines = [
        f"### {markdown_code(method)}",
        "",
        f"{command['summary']}.",
        "",
        f"- Data source: {markdown_code(command['dataSource'])}",
        f"- Response type: {markdown_code(command['responseType'])}",
    ]

    response_variants = command.get("responseVariants", [])
    if response_variants:
        variants = ", ".join(markdown_code(item) for item in response_variants)
        lines.append(f"- Response variants: {variants}")

    lines.extend(["", "#### Request fields", ""])
    if fields:
        lines.extend(
            [
                "| Field | Requirement | Type | Closed values |",
                "| --- | --- | --- | --- |",
            ]
        )
        for name, field in fields.items():
            requirement = "required" if name in required else "optional"
            enum = field.get("enum", []) if isinstance(field, dict) else []
            values = ", ".join(markdown_code(item) for item in enum) or "—"
            lines.append(
                f"| {markdown_code(name)} | {requirement} | "
                f"{field_type(field)} | {values} |"
            )
    else:
        lines.append("This operation has no request fields.")

    if exclusive:
        names = ", ".join(markdown_code(item) for item in exclusive)
        lines.extend(["", f"Exactly one of these fields is required: {names}."])

    notes = command.get("notes", [])
    if notes:
        lines.extend(["", "#### Contract guarantees", ""])
        lines.extend(f"- {note}" for note in notes)

    lines.append("")
    return lines


def render(catalog: dict[str, object]) -> str:
    lines = [
        "---",
        "type: Generated Reference",
        "title: Semantic Operation Contract",
        "description: Generated request and response facts for Kast's semantic operations.",
        "tags: [generated, reference, semantic-evidence]",
        "code_sources:",
        "  - path: cli-rs/protocol/source/commands.json",
        "---",
        "",
        "> Generated file. Do not edit this page directly.",
        "",
        "# Semantic Operation Contract",
        "",
        "This page records mechanically knowable operation facts. It is generated from",
        "`cli-rs/protocol/source/commands.json`; change that typed catalog, then regenerate",
        "this page.",
        "",
        "The reference describes the semantic contract behind Kast. It is not a guide for",
        "operating an agent or a substitute for the release-matched CLI help.",
        "",
    ]

    categories = catalog["categories"]
    commands = catalog["commands"]
    for category in CATEGORY_ORDER:
        lines.extend([f"## {CATEGORY_TITLES[category]}", ""])
        for method in categories[category]:
            lines.extend(render_command(commands[method]))

    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail when the checked-in reference differs from the command catalog",
    )
    args = parser.parse_args()

    catalog = json.loads(CATALOG_PATH.read_text())
    expected = render(catalog)

    if args.check:
        actual = OUTPUT_PATH.read_text() if OUTPUT_PATH.exists() else ""
        if actual == expected:
            print("Generated semantic-operation reference is current")
            return 0
        diff = difflib.unified_diff(
            actual.splitlines(),
            expected.splitlines(),
            fromfile=str(OUTPUT_PATH),
            tofile=f"{OUTPUT_PATH} (generated)",
            lineterm="",
        )
        print("\n".join(diff))
        return 1

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(expected)
    print(f"Wrote {OUTPUT_PATH.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
