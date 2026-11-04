"""Independently derive a finite rendered scope from the canonical OpenAPI resource."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any, Literal


@dataclass(frozen=True)
class SchemaNode:
    occurrence: str
    pointer: str
    depth: int
    expanded: bool | None
    facts: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class SchemaScope:
    depth: Literal[2]
    sourceSha256: str
    expected: tuple[SchemaNode, ...]
    observed: tuple[SchemaNode, ...]

    @property
    def complete(self) -> bool:
        return self.expected == self.observed and bool(self.expected)


def expected_scope(source: bytes, operation: str) -> tuple[SchemaNode, ...]:
    # Dynamic OpenAPI/schema maps stay at the resource boundary, not in measurement DTOs.
    document: dict[str, Any] = json.loads(source)
    roots = document["paths"]["/callables/" + operation]["post"]
    result: list[SchemaNode] = []

    def walk(pointer: str, occurrence: str, depth: int) -> None:
        schema: Any = document
        for part in pointer.removeprefix("#/").split("/"):
            key = part.replace("~1", "/").replace("~0", "~")
            schema = schema[int(key)] if isinstance(schema, list) else schema[key]
        children: list[str] = []
        facts: list[tuple[str, str]] = []
        if isinstance(schema, bool):
            facts.append(("schema", json.dumps(schema)))
        else:
            for key, value in schema.items():
                base = pointer + "/" + key.replace("~", "~0").replace("/", "~1")
                if key == "$ref":
                    children.append(value)
                elif key in ("oneOf", "anyOf", "allOf", "prefixItems") and isinstance(value, list):
                    children.extend(base + "/" + str(index) for index in range(len(value)))
                elif key in (
                    "properties",
                    "patternProperties",
                    "dependentSchemas",
                    "$defs",
                    "definitions",
                ) and isinstance(value, dict):
                    children.extend(
                        base + "/" + name.replace("~", "~0").replace("/", "~1") for name in value
                    )
                elif key in (
                    "items",
                    "additionalProperties",
                    "not",
                    "if",
                    "then",
                    "else",
                    "contains",
                    "propertyNames",
                ) and isinstance(value, (dict, bool)):
                    children.append(base)
                else:
                    facts.append((key, json.dumps(value, ensure_ascii=False)))
        result.append(
            SchemaNode(occurrence, pointer, depth, depth < 2 if children else None, tuple(facts))
        )
        if depth < 2:
            for index, child in enumerate(children):
                walk(child, occurrence + "-" + str(index), depth + 1)

    for index, suffix in enumerate(
        (
            "/requestBody/content/application~1json/schema",
            "/responses/200/content/application~1json/schema",
        )
    ):
        # Inspect the real operation before computing its exact schema pointers.
        if not roots:
            raise ValueError("SCHEMA_OPERATION_MISSING")
        pointer = "#/paths/~1callables~1" + operation + "/post" + suffix
        walk(pointer, "root-" + str(index), 0)
    return tuple(sorted(result, key=lambda node: node.occurrence))


def measurement(source: bytes, expected: tuple[SchemaNode, ...], raw: object) -> SchemaScope:
    if not isinstance(raw, list) or len(raw) > 500:
        raise ValueError("SCHEMA_DOM_INVALID")
    observed: list[SchemaNode] = []
    for node in raw:
        if not isinstance(node, dict) or set(node) != {
            "occurrence",
            "pointer",
            "depth",
            "expanded",
            "facts",
        }:
            raise ValueError("SCHEMA_DOM_INVALID")
        occurrence, pointer, depth, expanded, facts = (
            node[key] for key in ("occurrence", "pointer", "depth", "expanded", "facts")
        )
        if (
            not isinstance(occurrence, str)
            or not isinstance(pointer, str)
            or type(depth) is not int
            or not (expanded is None or type(expanded) is bool)
        ):
            raise ValueError("SCHEMA_DOM_INVALID")
        if not isinstance(facts, list) or not all(
            isinstance(pair, list)
            and len(pair) == 2
            and all(isinstance(item, str) for item in pair)
            for pair in facts
        ):
            raise ValueError("SCHEMA_DOM_INVALID")
        observed.append(
            SchemaNode(
                occurrence, pointer, depth, expanded, tuple((pair[0], pair[1]) for pair in facts)
            )
        )
    return SchemaScope(
        2,
        hashlib.sha256(source).hexdigest(),
        expected,
        tuple(sorted(observed, key=lambda node: node.occurrence)),
    )
