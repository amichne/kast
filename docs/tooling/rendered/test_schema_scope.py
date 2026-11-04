"""Canonical bounded scope expectations, independent of viewer graph generation."""

import json
from dataclasses import asdict, replace
from typing import Any

import pytest
from schema_scope import expected_scope, measurement


def source() -> bytes:
    schema: dict[str, Any] = {
        "components": {
            "schemas": {
                "Choice": {
                    "anyOf": [
                        {"type": "integer", "maximum": 9223372036854775807},
                        {"$ref": "#/components/schemas/Choice"},
                        False,
                    ]
                },
            }
        },
        "paths": {
            "/callables/example": {
                "post": {
                    "requestBody": {
                        "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/Choice"}}
                        }
                    },
                    "responses": {
                        "200": {
                            "content": {
                                "application/json": {
                                    "schema": {"$ref": "#/components/schemas/Choice"}
                                }
                            }
                        }
                    },
                }
            }
        },
    }
    return json.dumps(schema).encode()


def observed() -> list[dict[str, Any]]:
    return json.loads(json.dumps([asdict(node) for node in expected_scope(source(), "example")]))


def test_depth_two_counts_refs_and_includes_all_variants_and_repeated_occurrences() -> None:
    nodes = expected_scope(source(), "example")
    assert [node.occurrence for node in nodes] == [
        "root-0",
        "root-0-0",
        "root-0-0-0",
        "root-0-0-1",
        "root-0-0-2",
        "root-1",
        "root-1-0",
        "root-1-0-0",
        "root-1-0-1",
        "root-1-0-2",
    ]
    assert nodes[2].facts == (("type", '"integer"'), ("maximum", "9223372036854775807"))
    assert nodes[3].expanded is False
    assert nodes[4].facts == (("schema", "false"),)
    assert max(node.depth for node in nodes) == 2
    assert measurement(source(), nodes, observed()).complete


@pytest.mark.parametrize("mutation", ["missing", "duplicate", "wrong-fact", "collapsed", "extra"])
def test_incomplete_or_changed_dom_cannot_qualify(mutation: str) -> None:
    raw = observed()
    if mutation == "missing":
        raw.pop()
    elif mutation == "duplicate":
        raw.append(raw[0])
    elif mutation == "wrong-fact":
        raw[2]["facts"][1][1] = "9223372036854776000"
    elif mutation == "collapsed":
        raw[0]["expanded"] = False
    else:
        raw.append({**raw[0], "occurrence": "root-0-0-0-0", "depth": 3})
    assert not measurement(source(), expected_scope(source(), "example"), raw).complete


def test_hidden_or_invalid_dom_cannot_become_empty_success() -> None:
    with pytest.raises(ValueError, match="SCHEMA_DOM_INVALID"):
        measurement(source(), expected_scope(source(), "example"), None)
    scope = measurement(source(), expected_scope(source(), "example"), [])
    assert not scope.complete
    assert not replace(scope, observed=()).complete
