#!/usr/bin/env python3
"""Validate one canonical Kast performance audit evidence manifest."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import NoReturn


REPOSITORY_ROOT = Path(__file__).resolve().parents[4]
SCHEMA_PATH = (
    REPOSITORY_ROOT
    / "cli-rs/protocol/benchmarks/audit/kast-audit-evidence.schema.json"
)


def fail(message: str, exit_code: int = 1) -> NoReturn:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(exit_code)


def load_json(path: Path, label: str) -> tuple[str, object]:
    try:
        raw = path.read_text(encoding="utf-8")
        return raw, json.loads(raw)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"{label} is unreadable at {path}: {error}", 2)


def canonical_json(payload: object) -> str:
    return json.dumps(payload, indent=2, sort_keys=True) + "\n"


def json_type_matches(value: object, expected: str) -> bool:
    if expected == "object":
        return isinstance(value, dict)
    if expected == "array":
        return isinstance(value, list)
    if expected == "string":
        return isinstance(value, str)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "boolean":
        return isinstance(value, bool)
    return False


def resolve_reference(root: dict[str, object], reference: str) -> dict[str, object]:
    if not reference.startswith("#/"):
        fail(f"schema uses unsupported reference: {reference}", 2)
    value: object = root
    for raw_component in reference[2:].split("/"):
        component = raw_component.replace("~1", "/").replace("~0", "~")
        if not isinstance(value, dict) or component not in value:
            fail(f"schema reference does not resolve: {reference}", 2)
        value = value[component]
    if not isinstance(value, dict):
        fail(f"schema reference is not an object: {reference}", 2)
    return value


def validate(
    value: object,
    schema: dict[str, object],
    root: dict[str, object],
    path: str,
) -> None:
    reference = schema.get("$ref")
    if reference is not None:
        if not isinstance(reference, str):
            fail(f"schema reference at {path} is not a string", 2)
        validate(value, resolve_reference(root, reference), root, path)
        return

    expected_type = schema.get("type")
    if expected_type is not None:
        if not isinstance(expected_type, str) or not json_type_matches(value, expected_type):
            fail(f"{path}: expected {expected_type}")

    allowed_values = schema.get("enum")
    if allowed_values is not None:
        if not isinstance(allowed_values, list):
            fail(f"schema enum at {path} is not an array", 2)
        if value not in allowed_values:
            fail(f"{path}: value is outside the closed set")

    if isinstance(value, dict):
        required = schema.get("required", [])
        properties = schema.get("properties", {})
        if not isinstance(required, list) or not all(isinstance(item, str) for item in required):
            fail(f"schema required list at {path} is invalid", 2)
        if not isinstance(properties, dict):
            fail(f"schema properties at {path} is invalid", 2)
        missing = [key for key in required if key not in value]
        if missing:
            fail(f"{path}: missing required binding {missing[0]}")
        unexpected = sorted(set(value) - set(properties))
        if schema.get("additionalProperties") is False and unexpected:
            fail(f"{path}: undeclared binding {unexpected[0]}")
        for key in sorted(value):
            child_schema = properties.get(key)
            if child_schema is None:
                continue
            if not isinstance(child_schema, dict):
                fail(f"schema property {path}.{key} is invalid", 2)
            validate(value[key], child_schema, root, f"{path}.{key}")

    if isinstance(value, list):
        minimum_items = schema.get("minItems")
        if isinstance(minimum_items, int) and len(value) < minimum_items:
            fail(f"{path}: array has fewer than {minimum_items} items")
        if schema.get("uniqueItems") is True:
            encoded = [json.dumps(item, sort_keys=True, separators=(",", ":")) for item in value]
            if len(encoded) != len(set(encoded)):
                fail(f"{path}: array items must be unique")
        item_schema = schema.get("items")
        if item_schema is not None:
            if not isinstance(item_schema, dict):
                fail(f"schema items at {path} is invalid", 2)
            for index, item in enumerate(value):
                validate(item, item_schema, root, f"{path}[{index}]")

    if isinstance(value, str):
        minimum_length = schema.get("minLength")
        if isinstance(minimum_length, int) and len(value) < minimum_length:
            fail(f"{path}: string is shorter than {minimum_length}")
        pattern = schema.get("pattern")
        if pattern is not None:
            if not isinstance(pattern, str):
                fail(f"schema pattern at {path} is invalid", 2)
            try:
                matches = re.fullmatch(pattern, value) is not None
            except re.error as error:
                fail(f"schema pattern at {path} is invalid: {error}", 2)
            if not matches:
                fail(f"{path}: string does not match its contract")

    if isinstance(value, int) and not isinstance(value, bool):
        minimum = schema.get("minimum")
        if isinstance(minimum, (int, float)) and value < minimum:
            fail(f"{path}: value is below {minimum}")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--evidence", type=Path, required=True)
    return root


def main() -> int:
    arguments = parser().parse_args()
    _, schema_value = load_json(SCHEMA_PATH, "audit schema")
    evidence_raw, evidence = load_json(arguments.evidence, "audit evidence")
    if not isinstance(schema_value, dict):
        fail("audit schema root must be an object", 2)
    if evidence_raw != canonical_json(evidence):
        fail("$: evidence is not canonical JSON")
    validate(evidence, schema_value, schema_value, "$")
    print("audit evidence: valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
