#!/usr/bin/env python3
"""Render the embedded JSON-RPC contract catalog in the protocol reference."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Any


BEGIN_MARKER = "<!-- BEGIN GENERATED RPC CONTRACT SUITE -->"
END_MARKER = "<!-- END GENERATED RPC CONTRACT SUITE -->"

REPO_ROOT = Path(__file__).resolve().parents[2]
COMMAND_PATH_CANDIDATES = [
    REPO_ROOT / "cli-rs/resources/kast-skill/references/commands.json",
]
COMMANDS_PATH = None
DOC_PATH = REPO_ROOT / "cli-rs/protocol/api-specification.md"
COMMANDS_PATH_LABEL = "`cli-rs/resources/kast-skill/references/commands.json`"
CANONICAL_COMMANDS_PATH_LABEL = "`cli-rs/resources/kast-skill/references/commands.json`"

CATEGORY_PURPOSES = {
    "system": "Runtime readiness, backend state, and capability discovery.",
    "symbol": "Name-based orchestration for agent and script workflows.",
    "raw": "Position- and file-based backend primitives.",
    "database": "Source-index queries for metrics and impact views.",
}

FLOW_BLOCKS = [
    (
        "Check runtime",
        "Confirm the daemon is reachable, ready, and honest about supported work.",
        ["health", "runtime/status", "capabilities"],
    ),
    (
        "Choose targets",
        "Query indexed declarations or bounded symbol/text searches before optional workspace file inspection.",
        [
            "symbol/query",
            "raw/workspace-symbol",
            "raw/workspace-search",
            "raw/workspace-files",
            "symbol/resolve",
            "raw/file-outline",
        ],
    ),
    (
        "Inspect semantics",
        "Resolve declarations, inspect scopes, and read implementation or completion context.",
        [
            "raw/resolve",
            "raw/semantic-insertion-point",
            "raw/implementations",
            "raw/code-actions",
            "raw/completions",
        ],
    ),
    (
        "Trace relationships",
        "Move from one declaration to usages, callers, callees, and type relationships.",
        [
            "symbol/references",
            "raw/references",
            "symbol/callers",
            "raw/call-hierarchy",
            "raw/type-hierarchy",
        ],
    ),
    (
        "Plan changes",
        "Ask Kast to derive edit plans or generation context before mutating files.",
        ["symbol/scaffold", "symbol/rename", "raw/rename", "raw/optimize-imports"],
    ),
    (
        "Apply and validate",
        "Write prepared changes, refresh affected workspace state, and re-run diagnostics.",
        ["symbol/write-and-validate", "raw/apply-edits", "raw/workspace-refresh", "raw/diagnostics"],
    ),
    (
        "Read the index",
        "Use the source-index metrics reader for coupling, dead-code, search, graph, and impact questions.",
        ["database/metrics"],
    ),
]


def resolve_commands_path() -> tuple[Path | str, str]:
    global COMMANDS_PATH
    for path in COMMAND_PATH_CANDIDATES:
        if path.exists():
            COMMANDS_PATH = path
            return path, CANONICAL_COMMANDS_PATH_LABEL
    raise FileNotFoundError(f"missing commands catalog; expected one of: {[str(path) for path in COMMAND_PATH_CANDIDATES]}")


def open_catalog(raw_source: Any) -> dict[str, Any]:
    if isinstance(raw_source, Path):
        with raw_source.open(encoding="utf-8") as handle:
            return json.load(handle)
    if isinstance(raw_source, str):
        return json.loads(raw_source)
    raise TypeError(f"unsupported catalog source type: {type(raw_source)!r}")
def load_catalog() -> dict[str, Any]:
    raw_source, label = resolve_commands_path()
    global COMMANDS_PATH_LABEL
    COMMANDS_PATH_LABEL = label

    catalog = open_catalog(raw_source)
    if not isinstance(catalog.get("commands"), dict):
        raise ValueError(f"{COMMANDS_PATH} must contain a commands object")
    if not isinstance(catalog.get("categories"), dict):
        raise ValueError(f"{COMMANDS_PATH} must contain a categories object")
    return catalog


def ordered_methods(catalog: dict[str, Any]) -> list[str]:
    commands = catalog["commands"]
    seen: set[str] = set()
    ordered: list[str] = []

    for category, methods in catalog["categories"].items():
        if not isinstance(methods, list):
            raise ValueError(f"category {category!r} must list methods")
        for method in methods:
            if method not in commands:
                raise ValueError(f"category {category!r} references unknown method {method!r}")
            actual_category = commands[method].get("category")
            if actual_category != category:
                raise ValueError(
                    f"method {method!r} is listed under {category!r} but declares {actual_category!r}"
                )
            if method in seen:
                raise ValueError(f"method {method!r} is listed more than once")
            seen.add(method)
            ordered.append(method)

    missing = set(commands) - seen
    if missing:
        raise ValueError(f"commands missing from categories: {sorted(missing)}")
    return ordered


def command(catalog: dict[str, Any], method: str) -> dict[str, Any]:
    return catalog["commands"][method]


def fields_for(spec: dict[str, Any]) -> dict[str, Any]:
    request = spec.get("request") or {}
    fields = request.get("fields") or {}
    if not isinstance(fields, dict):
        raise ValueError(f"{spec.get('method')} request.fields must be an object")
    return fields


def required_fields(spec: dict[str, Any]) -> set[str]:
    request = spec.get("request") or {}
    required = request.get("required") or []
    if not isinstance(required, list):
        raise ValueError(f"{spec.get('method')} request.required must be a list")
    return set(required)


def type_label(field: dict[str, Any]) -> str:
    base = str(field.get("type", "unknown"))
    if base == "array":
        item = field.get("items")
        if item:
            return f"array of {item}"
    return base


def param_list(names: list[str]) -> str:
    if not names:
        return "none"
    return "<br>".join(f"`{name}`" for name in names)


def method_list(methods: list[str]) -> str:
    if not methods:
        return "none"
    return "<br>".join(f"`{method}`" for method in methods)


def enum_values(field: dict[str, Any]) -> str:
    values = field.get("enum")
    if not values:
        return ""
    return "<br>".join(f"`{value}`" for value in values)


def response_variants(spec: dict[str, Any]) -> str:
    success = spec.get("successType")
    failure = spec.get("failureType")
    if success and failure:
        return f"`{success}`<br>`{failure}`"
    return "single result"


def text_cell(value: Any) -> str:
    text = str(value)
    return text.replace("|", "\\|").replace("\n", "<br>")


def render_table(headers: list[str], rows: list[list[Any]]) -> list[str]:
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join(["---"] * len(headers)) + " |",
    ]
    for row in rows:
        lines.append("| " + " | ".join(text_cell(value) for value in row) + " |")
    return lines


def validate_flow_blocks(catalog: dict[str, Any]) -> None:
    commands = catalog["commands"]
    for name, _, methods in FLOW_BLOCKS:
        missing = [method for method in methods if method not in commands]
        if missing:
            raise ValueError(f"flow block {name!r} references unknown methods: {missing}")


def render_summary(catalog: dict[str, Any]) -> str:
    validate_flow_blocks(catalog)
    methods = ordered_methods(catalog)
    commands = catalog["commands"]

    lines: list[str] = [
        BEGIN_MARKER,
        "### Browse the JSON-RPC suite",
        "",
        f"This section is generated from {COMMANDS_PATH_LABEL}",
        "so the page exposes the internal JSON-RPC catalog used by typed",
        "`kast agent` commands and installed skills. It embeds the command",
        "families, flow-oriented building blocks, and request fields that",
        "callers compose into larger automation flows.",
        "",
        f"Catalog version: `{catalog.get('version', 'unknown')}`. Methods: `{len(methods)}`.",
        "",
        "#### Method families",
        "",
        "The families below are internal JSON-RPC namespaces, not public CLI commands.",
        "",
    ]

    family_rows: list[list[Any]] = []
    for category, category_methods in catalog["categories"].items():
        sources = sorted({commands[method].get("dataSource", "unknown") for method in category_methods})
        family_rows.append(
            [
                f"`{category}`",
                CATEGORY_PURPOSES.get(category, "Cataloged JSON-RPC methods."),
                ", ".join(sources),
                method_list(category_methods),
            ]
        )
    lines.extend(render_table(["Family", "Role", "Source", "Methods"], family_rows))

    lines.extend(
        [
            "",
            "#### Composition building blocks",
            "",
            "Use these groups as a starting point for composing multi-step flows.",
            "Each method listed here is validated against the generated catalog.",
            "",
        ]
    )
    flow_rows = [[name, purpose, method_list(methods)] for name, purpose, methods in FLOW_BLOCKS]
    lines.extend(render_table(["Block", "Use it for", "Methods"], flow_rows))

    lines.extend(
        [
            "",
            "#### Command catalog",
            "",
            "The table below summarizes every method, its backing source, request",
            "shape, response type, and success/failure variants when the method",
            "uses a discriminated response envelope.",
            "",
        ]
    )
    command_rows: list[list[Any]] = []
    for method in methods:
        spec = command(catalog, method)
        fields = fields_for(spec)
        required = required_fields(spec)
        optional = [name for name in fields if name not in required]
        command_rows.append(
            [
                f"`{method}`",
                f"`{spec.get('category', '')}`",
                spec.get("dataSource", ""),
                spec.get("summary", ""),
                param_list([name for name in fields if name in required]),
                param_list(optional),
                f"`{spec.get('responseType', 'none')}`",
                response_variants(spec),
            ]
        )
    lines.extend(
        render_table(
            [
                "Method",
                "Family",
                "Source",
                "Summary",
                "Required params",
                "Optional params",
                "Response",
                "Variants",
            ],
            command_rows,
        )
    )

    lines.extend(
        [
            "",
            "#### Command field details",
            "",
            "Open a method to inspect the request fields declared in the catalog.",
            "",
        ]
    )
    for method in methods:
        spec = command(catalog, method)
        fields = fields_for(spec)
        required = required_fields(spec)
        summary = spec.get("summary", "")
        lines.extend(
            [
                '<details markdown="1">',
                f"<summary><code>{method}</code> - {text_cell(summary)}</summary>",
                "",
            ]
        )
        if fields:
            detail_rows = []
            for field_name, field in fields.items():
                detail_rows.append(
                    [
                        f"`{field_name}`",
                        f"`{type_label(field)}`",
                        "yes" if field_name in required else "no",
                        "yes" if field.get("nullable") else "no",
                        enum_values(field) or "",
                    ]
                )
            lines.extend(render_table(["Field", "Type", "Required", "Nullable", "Values"], detail_rows))
        else:
            lines.append("No request parameters.")
        lines.extend(
            [
                "",
                f"Response type: `{spec.get('responseType', 'none')}`.",
            ]
        )
        success = spec.get("successType")
        failure = spec.get("failureType")
        if success and failure:
            lines.append(f"Result variants: `{success}`, `{failure}`.")
        notes = spec.get("notes") or []
        if notes:
            lines.extend(["", "Notes:", ""])
            lines.extend(f"- {note}" for note in notes)
        lines.extend(["", "</details>", ""])

    lines.append(END_MARKER)
    return "\n".join(lines).rstrip() + "\n"


def replace_block(document: str, block: str) -> str:
    start = document.find(BEGIN_MARKER)
    end = document.find(END_MARKER)
    if start == -1 or end == -1 or end < start:
        raise ValueError(f"{DOC_PATH} must contain {BEGIN_MARKER} and {END_MARKER}")
    end += len(END_MARKER)
    return document[:start] + block.rstrip() + document[end:]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if api-specification.md is out of date")
    parser.add_argument("--write", action="store_true", help="rewrite the generated block in api-specification.md")
    args = parser.parse_args()

    catalog = load_catalog()
    block = render_summary(catalog)

    if args.check or args.write:
        current = DOC_PATH.read_text()
        expected = replace_block(current, block)
        if args.write:
            if expected != current:
                DOC_PATH.write_text(expected)
            return 0
        if expected != current:
            print(
                "cli-rs/protocol/api-specification.md has drifted from commands.json; "
                "run python3 .github/scripts/render-rpc-contract-summary.py --write",
                file=sys.stderr,
            )
            return 1
        return 0

    print(block, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
