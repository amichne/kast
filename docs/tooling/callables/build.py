"""Project the unchanged callable contract into a lazy documentation graph."""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import html
import io
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Literal

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SOURCE = Path("docs/public/reference/callables.openapi.json")
ARRAYS = {"anyOf", "oneOf", "allOf", "prefixItems"}
SINGLES = {
    "items",
    "additionalProperties",
    "not",
    "if",
    "then",
    "else",
    "contains",
    "propertyNames",
}
MAPS = {"properties", "patternProperties", "dependentSchemas", "$defs", "definitions"}


def token(value: str) -> str:
    return value.replace("~", "~0").replace("/", "~1")


@dataclass(frozen=True)
class Fact:
    keyword: str
    value: str


@dataclass(frozen=True)
class Edge:
    label: str
    pointer: str


@dataclass(frozen=True)
class Node:
    facts: list[Fact]
    children: list[Edge]


@dataclass(frozen=True)
class Operation:
    roots: list[Edge]


@dataclass(frozen=True)
class Graph:
    nodes: dict[str, Node]
    operations: dict[str, Operation]


# OpenAPI/schema keyword maps are dynamic resources; projected graph shapes are fixed DTOs.
def project(document: dict[str, Any]) -> Graph:
    """Keep references as edges; facts retain exact JSON, including 64-bit integers."""
    nodes: dict[str, Node] = {}

    def visit(schema: object, pointer: str) -> None:
        if not isinstance(schema, (dict, bool)):
            raise ValueError("SCHEMA_NODE_INVALID:" + pointer)
        children: list[Edge] = []
        facts: list[Fact] = []
        if isinstance(schema, bool):
            facts.append(Fact("schema", json.dumps(schema)))
        else:
            for key, value in schema.items():
                nested: list[tuple[str, object, str]] = []
                if key == "$ref":
                    if not isinstance(value, str) or not value.startswith("#/components/schemas/"):
                        raise ValueError("SCHEMA_REFERENCE_UNSUPPORTED:" + pointer)
                    children.append(Edge("Reference: " + value.rsplit("/", 1)[1], value))
                elif key in ARRAYS and isinstance(value, list):
                    nested = [
                        (str(i), child, f"{key} variant {i + 1}") for i, child in enumerate(value)
                    ]
                elif key in MAPS and isinstance(value, dict):
                    nested = [
                        (token(name), child, f"{key}: {name}") for name, child in value.items()
                    ]
                elif key in SINGLES and isinstance(value, (dict, bool)):
                    nested = [("", value, key)]
                else:
                    facts.append(Fact(key, json.dumps(value, ensure_ascii=False)))
                for suffix, child, label in nested:
                    target = pointer + "/" + token(key) + ("/" + suffix if suffix else "")
                    children.append(Edge(label, target))
                    visit(child, target)
            # A reference may have sibling assertions. Keep those facts and edges together.
        nodes[pointer] = Node(facts, children)

    components = document["components"]
    if not isinstance(components, dict):
        raise ValueError("SCHEMA_RESOURCE_INVALID")
    schemas = components["schemas"]
    if not isinstance(schemas, dict):
        raise ValueError("SCHEMA_RESOURCE_INVALID")
    for name, schema in schemas.items():
        visit(schema, "#/components/schemas/" + token(name))
    operations: dict[str, Operation] = {}
    paths = document["paths"]
    if not isinstance(paths, dict):
        raise ValueError("SCHEMA_RESOURCE_INVALID")
    for path, item in paths.items():
        operation = item["post"]
        name = operation["operationId"]
        roots: list[Edge] = []
        for kind, suffix, schema in (
            (
                "Inputs",
                "/requestBody/content/application~1json/schema",
                operation["requestBody"]["content"]["application/json"]["schema"],
            ),
            (
                "Response",
                "/responses/200/content/application~1json/schema",
                operation["responses"]["200"]["content"]["application/json"]["schema"],
            ),
        ):
            pointer = "#/paths/" + token(path) + "/post" + suffix
            visit(schema, pointer)
            roots.append(Edge(kind, pointer))
        operations[name] = Operation(roots)
    for node in nodes.values():
        for edge in node.children:
            if edge.pointer not in nodes:
                raise ValueError("SCHEMA_REFERENCE_MISSING:" + edge.pointer)
    return Graph(nodes, operations)


@dataclass(frozen=True)
class PackedEdge:
    label: str
    node: int


@dataclass(frozen=True)
class PackedNode:
    parent: int | None
    path: str
    facts: list[Fact]
    children: list[PackedEdge]


@dataclass(frozen=True)
class PackedOperation:
    roots: list[PackedEdge]


@dataclass(frozen=True)
class PackedGraph:
    nodes: list[PackedNode]
    operations: dict[str, PackedOperation]
    schemaVersion: Literal[1] = 1


def pack(graph: Graph) -> PackedGraph:
    identities = {pointer: index for index, pointer in enumerate(graph.nodes)}
    nodes: list[PackedNode] = []
    for pointer, node in graph.nodes.items():
        parent = pointer.rpartition("/")[0]
        while parent and parent not in identities:
            parent = parent.rpartition("/")[0]
        nodes.append(
            PackedNode(
                identities[parent] if parent else None,
                pointer[len(parent) :] if parent else pointer,
                node.facts,
                [PackedEdge(edge.label, identities[edge.pointer]) for edge in node.children],
            )
        )
    return PackedGraph(
        nodes,
        {
            name: PackedOperation(
                [PackedEdge(edge.label, identities[edge.pointer]) for edge in operation.roots]
            )
            for name, operation in graph.operations.items()
        },
    )


def description_markdown(description: str) -> str:
    """Preserve canonical text, protecting exact identifiers and JSON from MDX typography."""

    def prose(value: str) -> str:
        escaped = html.escape(value, quote=False).replace("{", "&#123;").replace("}", "&#125;")
        return re.sub(
            r"\b(?:[a-z]+[A-Z][A-Za-z0-9]*|[a-z]+_[a-z0-9_]+|[A-Z][A-Z0-9_]+|nonterminal)\b",
            lambda match: "`" + match.group() + "`",
            escaped,
        )

    pieces: list[str] = []
    start = position = 0
    decoder = json.JSONDecoder()
    while position < len(description):
        if description[position] == "{":
            try:
                _, end = decoder.raw_decode(description, position)
            except json.JSONDecodeError:
                position += 1
                continue
            pieces.append(prose(description[start:position]))
            literal = description[position:end]
            ticks = "`" * (max((len(run) for run in re.findall(r"`+", literal)), default=0) + 1)
            pieces.append(ticks + " " + literal + " " + ticks)
            start = position = end
        else:
            position += 1
    pieces.append(prose(description[start:]))
    return "".join(pieces)


def outputs(root: Path) -> dict[Path, str]:
    document = json.loads((root / SOURCE).read_text())
    graph = project(document)
    data = json.dumps(asdict(pack(graph)), ensure_ascii=False, separators=(",", ":")) + "\n"
    if len(data.encode()) > 20_000_000:
        raise ValueError("CALLABLE_VIEW_DATA_LIMIT_EXCEEDED")
    component = (HERE / "viewer.jsx").read_text()
    snippet = "// Generated by docs/tooling/callables/build.py; edit its sources.\n"
    snippet += "export const CallableReference = ({ operation }) => {\n"
    snippet += (
        "const expectedDataSha = " + json.dumps(hashlib.sha256(data.encode()).hexdigest()) + ";\n"
    )
    compressed = io.BytesIO()
    with gzip.GzipFile(fileobj=compressed, mode="wb", filename="", mtime=0, compresslevel=9) as stream:
        stream.write(data.encode())
    snippet += (
        "const compressedGraphBase64 = "
        + json.dumps(base64.b64encode(compressed.getvalue()).decode())
        + ";\n"
    )
    snippet += component + "\nreturn <Reference operation={operation} />;\n};\n"
    result = {
        Path("docs/public/snippets/callable-reference.jsx"): snippet,
        Path("docs/public/assets/callable-reference.txt"): data,
    }
    for item in document["paths"].values():
        operation = item["post"]
        name = operation["operationId"]
        if name == "health_check":
            continue
        metadata = operation["x-mint"]["metadata"]
        page = (
            "---\n"
            + "\n".join(
                key + ": " + json.dumps(metadata[key])
                for key in ("title", "description", "mode", "icon")
            )
            + '\nkeywords: ["Kast", "callable", "schema"]\n---\n\n'
        )
        page += 'import { CallableReference } from "/snippets/callable-reference.jsx";\n\n'
        page += operation["x-mint"]["content"] + "\n\n"
        page += (
            '<Accordion title="Invocation contract">\n\n'
            + description_markdown(operation["description"])
            + "\n\n</Accordion>\n\n"
        )
        page += "Expand a schema to inspect its fields, constraints, and variants. References open on demand, including recursive references.\n\n"
        page += "[Download the complete contract](/reference/callables.openapi.json). This file retains every schema assertion and transport envelope. [Download schema fields](https://raw.githubusercontent.com/amichne/kast/main/docs/public/assets/callable-reference.txt) for the reference graph.\n\n"
        page += '<span id="body" /><span id="response" /><span id="200" />\n\n'
        page += f'<CallableReference operation="{name}" />\n'
        result[Path("docs/public/reference/callables") / (name.replace("_", "-") + ".mdx")] = page
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    stale: list[str] = []
    for relative, content in outputs(ROOT).items():
        path = ROOT / relative
        if args.check:
            if not path.is_file() or path.read_text() != content:
                stale.append(str(relative))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
    if stale:
        print("CALLABLE_VIEW_DRIFT: " + ", ".join(stale))
    return int(bool(stale))


if __name__ == "__main__":
    raise SystemExit(main())
