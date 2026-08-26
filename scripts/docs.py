#!/usr/bin/env python3
"""Generate, refresh, validate, and impact-check Kast's operation-linked documentation."""

from __future__ import annotations

import argparse
import json
import os
import posixpath
import subprocess
import sys
import tempfile
import tomllib
from collections import defaultdict
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

import yaml


class IndentedSafeDumper(yaml.SafeDumper):
    """Emit block sequences indented under their mapping key."""

    def increase_indent(self, flow: bool = False, indentless: bool = False):
        return super().increase_indent(flow, False)


class FlowList(list):
    """A sequence that must remain inline for the Code Knowledge Base parser."""


def _represent_flow_list(dumper: yaml.SafeDumper, data: FlowList):
    return dumper.represent_sequence("tag:yaml.org,2002:seq", data, flow_style=True)


IndentedSafeDumper.add_representer(FlowList, _represent_flow_list)


def _frontmatter_shape(value: Any, key: str | None = None) -> Any:
    if isinstance(value, dict):
        return {item_key: _frontmatter_shape(item_value, item_key) for item_key, item_value in value.items()}
    if isinstance(value, list):
        shaped = [_frontmatter_shape(item) for item in value]
        return FlowList(shaped) if key == "symbols" else shaped
    return value


ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs" / "public"
DATA = ROOT / "docs" / "_data"
SCHEMA_PATH = DATA / "kast-schema.json"
HELP_PATH = DATA / "kast-help.json"
GRAPH_PATH = DATA / "kast-docs.json"
OPERATIONS_REFERENCE = DOCS / "reference" / "operations.md"
CLI_REFERENCE = DOCS / "reference" / "cli.md"
PUBLIC_SCHEMA = DOCS / "reference" / "kast-schema.json"
CONFIG_PATH = ROOT / "zensical.toml"
RESERVED_FILENAMES = {"index.md", "log.md"}
GENERATED_PATHS = {OPERATIONS_REFERENCE, CLI_REFERENCE}
VALID_OPERATION_ROLES = {"primary", "related"}
LIFECYCLE_DESCRIPTIONS = {
    "start": "Start or reuse the exact-root runtime.",
    "stop": "Stop the exact-root runtime and retire markers.",
    "status": "Read exact-root runtime status.",
    "clean": "Remove stopped runtime markers and state.",
    "reindex": "Stop, clean, and rebuild exact-root semantic state.",
}


class DocsError(RuntimeError):
    pass


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise DocsError(f"Expected a JSON object: {path}")
    return value


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def markdown_files() -> list[Path]:
    return sorted(path for path in DOCS.rglob("*.md") if path.is_file())


def split_frontmatter(path: Path) -> tuple[dict[str, Any] | None, str]:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return None, text
    try:
        closing = next(index for index, line in enumerate(lines[1:], 1) if line.strip() == "---")
    except StopIteration as error:
        raise DocsError(f"Unterminated YAML front matter: {path}") from error
    value = yaml.safe_load("\n".join(lines[1:closing])) or {}
    if not isinstance(value, dict):
        raise DocsError(f"Front matter must be a mapping: {path}")
    return value, "\n".join(lines[closing + 1 :])


def page_records() -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for path in markdown_files():
        relative = path.relative_to(DOCS).as_posix()
        metadata, body = split_frontmatter(path)
        records.append(
            {
                "path": relative,
                "absolute": path,
                "metadata": metadata or {},
                "body": body,
                "reserved": path.name in RESERVED_FILENAMES,
                "generated": path in GENERATED_PATHS,
            }
        )
    return records


def canonical_operations(schema: dict[str, Any]) -> dict[str, dict[str, str]]:
    registry = schema.get("operationRegistry") or {}
    projection = schema.get("cliProjection") or {}
    ids = registry.get("operationIds") or []
    commands = projection.get("commands") or []
    if not isinstance(ids, list) or not all(isinstance(value, str) for value in ids):
        raise DocsError("operationRegistry.operationIds must be a string list")
    if not isinstance(commands, list) or not all(isinstance(value, str) for value in commands):
        raise DocsError("cliProjection.commands must be a string list")
    if len(ids) != len(commands):
        raise DocsError("Operation IDs and CLI command projections differ in length")

    operations: dict[str, dict[str, str]] = {}
    for operation_id, command in zip(ids, commands, strict=True):
        expected_prefix = operation_id.replace(".", " ")
        if command != expected_prefix and not command.startswith(expected_prefix + " "):
            raise DocsError(
                f"CLI command does not project its operation ID: {operation_id!r} -> {command!r}"
            )
        operations[operation_id] = {
            "command": command,
            "anchor": operation_id.replace(".", "-"),
        }
    return operations


def list_of_strings(value: Any, field: str, page: str, issues: list[str]) -> list[str]:
    if value in (None, ""):
        return []
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        issues.append(f"{page}: {field} must be a list of strings")
        return []
    return list(value)


def build_graph(schema: dict[str, Any], help_snapshot: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    operations = canonical_operations(schema)
    lifecycle_commands = schema.get("cliProjection", {}).get("lifecycleCommands") or []
    if not isinstance(lifecycle_commands, list):
        raise DocsError("cliProjection.lifecycleCommands must be a list")

    issues: list[str] = []
    primary: dict[str, list[dict[str, Any]]] = defaultdict(list)
    related: dict[str, list[dict[str, Any]]] = defaultdict(list)
    pages: dict[str, dict[str, Any]] = {}

    for record in page_records():
        if record["generated"]:
            continue
        path = record["path"]
        metadata = record["metadata"]
        operation_ids = list_of_strings(metadata.get("kast_operations"), "kast_operations", path, issues)
        lifecycle = list_of_strings(
            metadata.get("kast_lifecycle_commands"), "kast_lifecycle_commands", path, issues
        )
        role = metadata.get("kast_operation_role")

        unknown_operations = sorted(set(operation_ids) - set(operations))
        for operation_id in unknown_operations:
            issues.append(f"{path}: unknown Kast operation {operation_id}")
        unknown_lifecycle = sorted(set(lifecycle) - set(lifecycle_commands))
        for command in unknown_lifecycle:
            issues.append(f"{path}: unknown Kast lifecycle command {command}")

        if operation_ids:
            if role not in VALID_OPERATION_ROLES:
                issues.append(
                    f"{path}: kast_operation_role must be primary or related when kast_operations is set"
                )
            elif role == "primary":
                if len(operation_ids) != 1:
                    issues.append(f"{path}: a primary page must name exactly one operation")
                else:
                    primary[operation_ids[0]].append(record)
            elif role == "related":
                for operation_id in operation_ids:
                    related[operation_id].append(record)
        elif role is not None:
            issues.append(f"{path}: kast_operation_role requires kast_operations")

        pages[path] = {
            "title": metadata.get("title"),
            "type": metadata.get("type"),
            "operations": operation_ids,
            "operationRole": role,
            "lifecycleCommands": lifecycle,
            "codeSources": metadata.get("code_sources") or [],
        }

    graph_operations: dict[str, dict[str, Any]] = {}
    for operation_id, contract in operations.items():
        primaries = primary.get(operation_id, [])
        if len(primaries) != 1:
            issues.append(
                f"operation {operation_id}: expected exactly one primary page, found {len(primaries)}"
            )
            continue
        page = primaries[0]
        metadata = page["metadata"]
        expected_resource = f"kast://operation/{operation_id}"
        if metadata.get("resource") != expected_resource:
            issues.append(
                f"{page['path']}: primary resource must be {expected_resource!r}"
            )
        graph_operations[operation_id] = {
            **contract,
            "title": metadata.get("title") or operation_id,
            "description": metadata.get("description") or "",
            "primaryPage": page["path"],
            "relatedPages": sorted(record["path"] for record in related.get(operation_id, [])),
            "proofLevel": metadata.get("proof_level") or "contract",
        }

    lifecycle = {
        command: {
            "anchor": f"lifecycle-{command}",
            "description": LIFECYCLE_DESCRIPTIONS.get(command, "Runtime lifecycle command."),
            "relatedPages": sorted(
                record["path"]
                for record in page_records()
                if command in (record["metadata"].get("kast_lifecycle_commands") or [])
            ),
        }
        for command in lifecycle_commands
    }

    runtime = schema.get("semanticRuntime") or {}
    graph = {
        "schemaVersion": 1,
        "generatedAt": help_snapshot.get("capturedAt"),
        "productVersion": runtime.get("productVersion"),
        "runtimeId": runtime.get("runtimeId"),
        "wireSchemaId": schema.get("wireSchema", {}).get("wireSchemaId"),
        "operations": graph_operations,
        "lifecycle": lifecycle,
        "pages": pages,
    }
    return graph, issues


def yaml_frontmatter(metadata: dict[str, Any]) -> str:
    return yaml.dump(
        _frontmatter_shape(metadata),
        Dumper=IndentedSafeDumper,
        sort_keys=False,
        allow_unicode=True,
        default_flow_style=False,
        width=100,
        indent=2,
    ).rstrip()


def generated_page(metadata: dict[str, Any], body: str) -> str:
    return (
        "---\n"
        f"{yaml_frontmatter(metadata)}\n"
        "---\n\n"
        "<!-- Generated by scripts/docs.py. Do not edit. -->\n\n"
        f"{body.rstrip()}\n"
    )


def relative_link(source_path: str, target_path: str) -> str:
    source_directory = posixpath.dirname(source_path) or "."
    return posixpath.relpath(target_path, source_directory)


def render_operations_reference(
    graph: dict[str, Any], help_snapshot: dict[str, Any]
) -> str:
    metadata = {
        "type": "CLI Reference",
        "title": "Operations",
        "description": "Canonical Kast operation IDs, commands, and primary documentation pages.",
        "resource": "kast://operation-registry",
        "tags": ["kast", "cli", "operations", "generated"],
        "timestamp": help_snapshot.get("capturedAt"),
        "status": "generated",
        "code_sources": [
            {
                "path": "protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt",
                "symbols": ["CanonicalOperation"],
            },
            {
                "path": "protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt",
                "symbols": ["CanonicalOperationDefinitions"],
            },
        ],
    }
    rows = ["| Operation | CLI command | Primary page | Proof |", "| --- | --- | --- | --- |"]
    sections: list[str] = []
    for operation_id, operation in graph["operations"].items():
        page_link = relative_link("reference/operations.md", operation["primaryPage"])
        rows.append(
            f"| [`{operation_id}`](#{operation['anchor']}) | `kast {operation['command']}` | "
            f"[{operation['title']}]({page_link}) | `{operation['proofLevel']}` |"
        )
        related = operation["relatedPages"]
        related_text = (
            "\n".join(
                f"- [{graph['pages'][path]['title'] or path}]({relative_link('reference/operations.md', path)})"
                for path in related
            )
            if related
            else "No related concept pages."
        )
        sections.append(
            f"## `{operation_id}` {{ #{operation['anchor']} }}\n\n"
            f"**Command:** `kast {operation['command']}`\n\n"
            f"**Primary page:** [{operation['title']}]({page_link})\n\n"
            f"{operation['description']}\n\n"
            f"### Related pages\n\n{related_text}"
        )
    body = (
        "# Operations\n\n"
        "This page is generated from the installed schema and page front matter. "
        "An operation is publishable only when it has one primary page.\n\n"
        + "\n".join(rows)
        + "\n\n"
        + "\n\n".join(sections)
    )
    return generated_page(metadata, body)


def indent_block(value: str, spaces: int = 4) -> str:
    prefix = " " * spaces
    return "\n".join(prefix + line for line in value.splitlines())


def render_cli_reference(
    schema: dict[str, Any], graph: dict[str, Any], help_snapshot: dict[str, Any]
) -> str:
    metadata = {
        "type": "CLI Reference",
        "title": "CLI",
        "description": "Installed Kast command projection, lifecycle commands, and machine schema.",
        "resource": "kast://cli",
        "tags": ["kast", "cli", "schema", "generated"],
        "timestamp": help_snapshot.get("capturedAt"),
        "status": "generated",
        "code_sources": [
            {
                "path": "cli/src/main/kotlin/io/github/amichne/kast/cli/command/CliCommandGraph.kt",
                "symbols": ["CliCommandGraphFactory"],
            },
            {
                "path": "protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalOperationDefinitions.kt",
                "symbols": ["CanonicalOperationDefinitions"],
            },
        ],
    }
    runtime = schema.get("semanticRuntime") or {}
    command_rows = ["| Operation | Command |", "| --- | --- |"]
    for operation_id, operation in graph["operations"].items():
        command_rows.append(
            f"| [`{operation_id}`](operations.md#{operation['anchor']}) | `kast {operation['command']}` |"
        )

    lifecycle_sections = []
    for command, lifecycle in graph["lifecycle"].items():
        lifecycle_sections.append(
            f"## `kast {command}` {{ #{lifecycle['anchor']} }}\n\n{lifecycle['description']}"
        )

    root_help = str(help_snapshot.get("root") or "No root help snapshot is available.")
    body = (
        "# CLI\n\n"
        "The command table comes from `kast --schema`. The expandable help snapshot comes from "
        "the same installed product capture.\n\n"
        "## Installed contract\n\n"
        "| Field | Value |\n| --- | --- |\n"
        f"| Product version | `{runtime.get('productVersion')}` |\n"
        f"| Semantic runtime | `{runtime.get('runtimeId')}` |\n"
        f"| Wire schema | `{schema.get('wireSchema', {}).get('wireSchemaId')}` |\n"
        f"| Platform | `{runtime.get('platform')}/{runtime.get('architecture')}` |\n\n"
        "[Download the machine schema](kast-schema.json){ .md-button }\n\n"
        "## Semantic commands\n\n"
        + "\n".join(command_rows)
        + "\n\n## Lifecycle commands\n\n"
        + "\n\n".join(lifecycle_sections)
        + "\n\n??? abstract \"Captured root help\"\n\n"
        + indent_block("```text\n" + root_help + "\n```", 4)
    )
    return generated_page(metadata, body)


def render_outputs() -> tuple[dict[Path, str], dict[str, Any], list[str]]:
    schema = read_json(SCHEMA_PATH)
    help_snapshot = read_json(HELP_PATH)
    graph, issues = build_graph(schema, help_snapshot)
    outputs = {
        GRAPH_PATH: json.dumps(graph, indent=2, sort_keys=False) + "\n",
        OPERATIONS_REFERENCE: render_operations_reference(graph, help_snapshot),
        CLI_REFERENCE: render_cli_reference(schema, graph, help_snapshot),
        PUBLIC_SCHEMA: json.dumps(schema, indent=2, sort_keys=False) + "\n",
    }
    return outputs, graph, issues


def generate() -> tuple[dict[str, Any], list[str]]:
    outputs, graph, issues = render_outputs()
    if issues:
        return graph, issues
    for path, content in outputs.items():
        write_text(path, content)
    return graph, []


def command_tokens(command: str) -> list[str]:
    prefix = command.split(" --", 1)[0]
    return prefix.split()


def run_cli(kast: Path, args: Iterable[str]) -> str:
    result = subprocess.run(
        [str(kast), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        timeout=300,
    )
    if result.returncode != 0:
        raise DocsError(
            f"{' '.join([str(kast), *args])} exited {result.returncode}: {result.stderr.strip()}"
        )
    return result.stdout.strip()


def refresh(kast: Path) -> tuple[dict[str, Any], list[str]]:
    if not kast.is_absolute() or not kast.is_file() or not os.access(kast, os.X_OK):
        raise DocsError("--kast must be an absolute executable file")
    schema_text = run_cli(kast, ["--schema"])
    schema = json.loads(schema_text)
    operations = canonical_operations(schema)
    lifecycle = schema.get("cliProjection", {}).get("lifecycleCommands") or []
    families = sorted({operation_id.split(".", 1)[0] for operation_id in operations})

    from datetime import datetime, timezone

    help_snapshot: dict[str, Any] = {
        "schemaVersion": 1,
        "capturedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "capturedFrom": str(kast),
        "productVersion": schema.get("semanticRuntime", {}).get("productVersion"),
        "version": run_cli(kast, ["--version"]),
        "root": run_cli(kast, ["--help"]),
        "families": {},
        "commands": {},
        "lifecycle": {},
    }
    for family in families:
        help_snapshot["families"][family] = run_cli(kast, [family, "--help"])
    for operation_id, operation in operations.items():
        help_snapshot["commands"][operation_id] = run_cli(
            kast, [*command_tokens(operation["command"]), "--help"]
        )
    for command in lifecycle:
        help_snapshot["lifecycle"][command] = run_cli(kast, [command, "--help"])

    write_text(SCHEMA_PATH, json.dumps(schema, indent=2, sort_keys=False) + "\n")
    write_text(HELP_PATH, json.dumps(help_snapshot, indent=2, sort_keys=False) + "\n")
    return generate()


def flatten_nav(value: Any) -> list[str]:
    paths: list[str] = []
    if isinstance(value, str):
        if value.endswith(".md"):
            paths.append(value)
    elif isinstance(value, list):
        for item in value:
            paths.extend(flatten_nav(item))
    elif isinstance(value, dict):
        for item in value.values():
            paths.extend(flatten_nav(item))
    return paths


def valid_repo_relative(value: str) -> bool:
    pure = PurePosixPath(value)
    return bool(value) and not pure.is_absolute() and ".." not in pure.parts


def check(repo_root: Path, skip_source_existence: bool) -> list[str]:
    issues: list[str] = []
    try:
        config = tomllib.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except Exception as error:
        return [f"zensical.toml: {error}"]

    nav_paths = set(flatten_nav(config.get("project", {}).get("nav", [])))
    for nav_path in sorted(nav_paths):
        if not (DOCS / nav_path).is_file():
            issues.append(f"zensical.toml: navigation target does not exist: {nav_path}")

    records = page_records()
    primary_paths: set[str] = set()
    for record in records:
        path = record["path"]
        metadata = record["metadata"]
        if record["reserved"]:
            if path == "index.md":
                unknown = sorted(set(metadata) - {"okf_version"})
                for field in unknown:
                    issues.append(f"{path}: root index field is not reserved OKF metadata: {field}")
            elif metadata:
                issues.append(f"{path}: reserved files must not contain concept front matter")
            continue

        type_value = metadata.get("type")
        if not isinstance(type_value, str) or not type_value.strip():
            issues.append(f"{path}: front matter type is required")
        for field in ("title", "description", "resource", "tags", "timestamp"):
            if field not in metadata:
                issues.append(f"{path}: recommended OKF field is missing: {field}")

        operations = metadata.get("kast_operations") or []
        lifecycle = metadata.get("kast_lifecycle_commands") or []
        if operations or lifecycle:
            if "{{ kast_contract_links(" not in record["body"]:
                issues.append(f"{path}: operation-linked page must render kast_contract_links")
        if metadata.get("kast_operation_role") == "primary":
            primary_paths.add(path)

        sources = metadata.get("code_sources") or []
        if not isinstance(sources, list):
            issues.append(f"{path}: code_sources must be a list")
            continue
        for source in sources:
            if not isinstance(source, dict):
                issues.append(f"{path}: code_sources entries must be mappings")
                continue
            source_path = source.get("path")
            if not isinstance(source_path, str) or not valid_repo_relative(source_path):
                issues.append(f"{path}: invalid code_sources path: {source_path!r}")
                continue
            if not skip_source_existence and not (repo_root / source_path).exists():
                issues.append(f"{path}: code_sources path does not exist: {source_path}")

    missing_nav = sorted(primary_paths - nav_paths)
    for path in missing_nav:
        issues.append(f"zensical.toml: primary operation page is absent from navigation: {path}")

    outputs, _, graph_issues = render_outputs()
    issues.extend(graph_issues)
    for path, expected in outputs.items():
        if not path.is_file():
            issues.append(f"{path.relative_to(ROOT)}: generated file is missing")
        elif path.read_text(encoding="utf-8") != expected:
            issues.append(f"{path.relative_to(ROOT)}: generated file is stale; run scripts/docs.py generate")
    return issues


def impacted_pages(
    graph: dict[str, Any], operation_ids: list[str], lifecycle_commands: list[str], changed: list[str]
) -> list[dict[str, Any]]:
    impacted: dict[str, dict[str, Any]] = {}

    def add(path: str, reason: str) -> None:
        entry = impacted.setdefault(
            path,
            {
                "path": path,
                "title": graph.get("pages", {}).get(path, {}).get("title"),
                "reasons": [],
            },
        )
        if reason not in entry["reasons"]:
            entry["reasons"].append(reason)

    for operation_id in operation_ids:
        operation = graph.get("operations", {}).get(operation_id)
        if operation is None:
            raise DocsError(f"Unknown operation: {operation_id}")
        add(operation["primaryPage"], f"operation:{operation_id}")
        for path in operation["relatedPages"]:
            add(path, f"operation:{operation_id}")

    for command in lifecycle_commands:
        lifecycle = graph.get("lifecycle", {}).get(command)
        if lifecycle is None:
            raise DocsError(f"Unknown lifecycle command: {command}")
        for path in lifecycle["relatedPages"]:
            add(path, f"lifecycle:{command}")

    changed_set = set(changed)
    for path, page in graph.get("pages", {}).items():
        for source in page.get("codeSources") or []:
            if isinstance(source, dict) and source.get("path") in changed_set:
                add(path, f"source:{source['path']}")
        if path in changed_set or f"docs/public/{path}" in changed_set:
            add(path, f"page:{path}")

    return sorted(impacted.values(), key=lambda item: item["path"])


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("generate", help="Generate the operation graph and reference pages.")

    refresh_parser = commands.add_parser("refresh", help="Refresh the schema and help from an installed Kast CLI.")
    refresh_parser.add_argument("--kast", type=Path, required=True)

    check_parser = commands.add_parser("check", help="Validate the OKF/Zensical bundle and generated links.")
    check_parser.add_argument("--repo", type=Path, default=ROOT)
    check_parser.add_argument("--skip-source-existence", action="store_true")

    impact_parser = commands.add_parser("impact", help="Find pages affected by operations or source paths.")
    impact_parser.add_argument("--operation", action="append", default=[])
    impact_parser.add_argument("--lifecycle", action="append", default=[])
    impact_parser.add_argument("--changed-file", action="append", default=[])
    impact_parser.add_argument("--format", choices=["human", "json"], default="human")

    args = parser.parse_args(argv)
    try:
        if args.command == "generate":
            graph, issues = generate()
            if issues:
                for issue in issues:
                    print(f"- {issue}", file=sys.stderr)
                return 1
            print(f"generated {len(graph['operations'])} operation mappings")
            return 0

        if args.command == "refresh":
            graph, issues = refresh(args.kast)
            if issues:
                for issue in issues:
                    print(f"- {issue}", file=sys.stderr)
                return 1
            print(f"refreshed Kast {graph['productVersion']} documentation contract")
            return 0

        if args.command == "check":
            issues = check(args.repo.resolve(), args.skip_source_existence)
            print(f"documentation check: {len(issues)} issue(s)")
            for issue in issues:
                print(f"- {issue}")
            return 1 if issues else 0

        if args.command == "impact":
            graph = read_json(GRAPH_PATH)
            impacted = impacted_pages(
                graph, args.operation, args.lifecycle, args.changed_file
            )
            payload = {
                "operations": args.operation,
                "lifecycleCommands": args.lifecycle,
                "changedFiles": args.changed_file,
                "impactedPages": impacted,
            }
            if args.format == "json":
                print(json.dumps(payload, indent=2, sort_keys=False))
            else:
                print(f"documentation impact: {len(impacted)} page(s)")
                for page in impacted:
                    print(f"- {page['path']}: {', '.join(page['reasons'])}")
            return 0
    except (DocsError, json.JSONDecodeError, OSError, subprocess.SubprocessError) as error:
        print(f"docs: {error}", file=sys.stderr)
        return 1
    return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
