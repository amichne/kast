#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

TEMPLATE_PATTERN = re.compile(r"\{\{\s*([A-Z0-9_]+)((?:\.[A-Za-z0-9_]+)+)\s*\}\}")


def load_json(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON in {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{path} must contain a JSON object.")
    return payload


def resolve_binding(binding: dict[str, Any], expression: str) -> Any:
    match = re.fullmatch(r"([A-Z0-9_]+)((?:\.[A-Za-z0-9_]+)+)", expression)
    if not match:
        raise ValueError(f"Invalid template expression: {expression}")
    slot_name = match.group(1)
    fields = [field for field in match.group(2).split(".") if field]
    slots = binding.get("slots")
    if not isinstance(slots, dict):
        raise ValueError("Bindings must contain a 'slots' object.")
    if slot_name not in slots:
        raise ValueError(f"Template references unknown slot '{slot_name}'.")

    value: Any = slots[slot_name]
    for field in fields:
        if not isinstance(value, dict) or field not in value:
            raise ValueError(f"Template references unknown binding field '{slot_name}.{'.'.join(fields)}'.")
        value = value[field]
    return value


def stringify_value(value: Any) -> str:
    if isinstance(value, list):
        return ", ".join(str(item) for item in value)
    if isinstance(value, (str, int, float, bool)):
        return str(value)
    raise ValueError(f"Template values must render to scalars or arrays, got {type(value).__name__}.")


def render_string(text: str, bindings: dict[str, Any]) -> str:
    def replace(match: re.Match[str]) -> str:
        slot = match.group(1)
        fields = match.group(2)
        return stringify_value(resolve_binding(bindings, f"{slot}{fields}"))

    return TEMPLATE_PATTERN.sub(replace, text)


def render_value(value: Any, bindings: dict[str, Any]) -> Any:
    if isinstance(value, str):
        return render_string(value, bindings)
    if isinstance(value, list):
        return [render_value(item, bindings) for item in value]
    if isinstance(value, dict):
        return {key: render_value(item, bindings) for key, item in value.items()}
    return value


UNRESOLVED_PATTERN = re.compile(r"\{\{[^{}]+\}\}")


def render_catalog(catalog: dict[str, Any], bindings: dict[str, Any]) -> dict[str, Any]:
    cases = catalog.get("cases")
    if not isinstance(cases, list):
        raise ValueError("Catalog must contain a 'cases' array.")
    rendered = render_value(catalog, bindings)
    rendered["bindings"] = {
        "target_repo": bindings.get("target_repo", ""),
        "workspace_root": bindings.get("workspace_root", ""),
        "git_sha": bindings.get("git_sha", ""),
    }
    leftovers = _find_unresolved(rendered)
    if leftovers:
        sample = ", ".join(sorted(leftovers)[:5])
        raise ValueError(
            f"Rendered catalog still contains {len(leftovers)} unresolved template placeholder(s): {sample}. "
            "Add the missing slot/field to the bindings file before continuing."
        )
    return rendered


def _find_unresolved(payload: Any) -> set[str]:
    found: set[str] = set()
    if isinstance(payload, str):
        found.update(UNRESOLVED_PATTERN.findall(payload))
    elif isinstance(payload, list):
        for item in payload:
            found.update(_find_unresolved(item))
    elif isinstance(payload, dict):
        for value in payload.values():
            found.update(_find_unresolved(value))
    return found


def main() -> None:
    parser = argparse.ArgumentParser(description="Render a value-proof catalog with concrete codebase bindings.")
    parser.add_argument("--catalog", required=True, type=Path, help="Path to catalog.json")
    parser.add_argument("--bindings", required=True, type=Path, help="Path to bindings JSON")
    parser.add_argument("--output", required=True, type=Path, help="Path to rendered catalog output")
    args = parser.parse_args()

    rendered = render_catalog(load_json(args.catalog), load_json(args.bindings))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(rendered, indent=2) + "\n")
    print(f"Rendered {len(rendered['cases'])} prompts to {args.output}")


if __name__ == "__main__":
    main()
