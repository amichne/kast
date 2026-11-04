"""Lossless graph projection and finite reference regressions."""

import html
import json
from pathlib import Path
from typing import Any

import pytest
from callables.build import ROOT, SOURCE, Fact, outputs, pack, project


def resource(schema: dict[str, Any] | bool) -> dict[str, Any]:
    """A minimal dynamic schema resource, not a callable invocation fixture."""
    return {
        "components": {"schemas": {"Value": schema}},
        "paths": {
            "/callables/example": {
                "post": {
                    "operationId": "example",
                    "requestBody": {
                        "content": {
                            "application/json": {"schema": {"$ref": "#/components/schemas/Value"}}
                        }
                    },
                    "responses": {"200": {"content": {"application/json": {"schema": False}}}},
                }
            }
        },
    }


def test_large_integer_and_reference_siblings_retain_exact_assertions() -> None:
    graph = project(
        resource(
            {
                "$ref": "#/components/schemas/Value",
                "maximum": 9223372036854775807,
                "description": "Sibling assertion",
            }
        )
    )
    node = graph.nodes["#/components/schemas/Value"]
    assert node.facts == [
        Fact("maximum", "9223372036854775807"),
        Fact("description", '"Sibling assertion"'),
    ]
    assert node.children[0].pointer == "#/components/schemas/Value"
    assert len(graph.nodes) == 3


def test_mutual_and_repeated_references_do_not_expand_the_graph() -> None:
    document = resource(
        {
            "type": "object",
            "properties": {
                "first": {"$ref": "#/components/schemas/Other"},
                "second": {"$ref": "#/components/schemas/Other"},
            },
        }
    )
    document["components"]["schemas"]["Other"] = {"$ref": "#/components/schemas/Value"}
    graph = project(document)
    assert len(graph.nodes) == 6
    assert (
        graph.nodes["#/components/schemas/Value/properties/first"]
        != graph.nodes["#/components/schemas/Other"]
    )
    assert (
        graph.nodes["#/components/schemas/Value/properties/first"].children
        == graph.nodes["#/components/schemas/Value/properties/second"].children
    )
    assert (
        graph.nodes["#/components/schemas/Other"].children[0].pointer
        == "#/components/schemas/Value"
    )


def test_false_schema_and_escaped_pointer_are_preserved() -> None:
    graph = project(resource({"properties": {"a/b~c": False}}))
    assert graph.nodes["#/components/schemas/Value/properties/a~1b~0c"].facts == [
        Fact("schema", "false")
    ]


def test_dangling_reference_is_rejected() -> None:
    with pytest.raises(ValueError, match="SCHEMA_REFERENCE_MISSING"):
        project(resource({"$ref": "#/components/schemas/Missing"}))


def test_every_packed_pointer_and_fact_matches_the_canonical_resource() -> None:
    source = json.loads((ROOT / SOURCE).read_text())
    graph = project(source)
    packed = pack(graph)

    def pointer(index: int) -> str:
        node = packed.nodes[index]
        return (pointer(node.parent) if node.parent is not None else "") + node.path

    for index, (original, node) in enumerate(graph.nodes.items()):
        assert pointer(index) == original
        schema = source
        for part in original.removeprefix("#/").split("/"):
            key = part.replace("~1", "/").replace("~0", "~")
            schema = schema[int(key)] if isinstance(schema, list) else schema[key]
        for fact in node.facts:
            assert json.loads(fact.value) == (
                schema if fact.keyword == "schema" else schema[fact.keyword]
            )
        assert packed.nodes[index].facts == node.facts
        assert [pointer(edge.node) for edge in packed.nodes[index].children] == [
            edge.pointer for edge in node.children
        ]


def test_generated_pages_keep_operation_guidance_and_selected_route_inventory() -> None:
    source = json.loads((ROOT / SOURCE).read_text())
    files = outputs(ROOT)
    pages = {str(path): content for path, content in files.items() if path.suffix == ".mdx"}
    assert len(pages) == 5
    assert all("health-check" not in path for path in pages)
    for name in (
        "query_symbols",
        "check_diagnostics",
        "add_declaration",
        "replace_body",
        "workspace_lifecycle",
    ):
        content = pages[f"docs/public/reference/callables/{name.replace('_', '-')}.mdx"]
        operation = source["paths"]["/callables/" + name]["post"]
        assert operation["description"].replace("`", "") in html.unescape(content).replace(
            "` ", ""
        ).replace(" `", "").replace("`", "")
        assert operation["x-mint"]["content"] in content
    for path, content in files.items():
        assert (ROOT / path).read_text() == content
    assert len(files[Path("docs/public/snippets/callable-reference.jsx")]) < 10000
